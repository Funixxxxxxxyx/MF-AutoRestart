package id.funix.mFAutoRestart.manager;

import id.funix.mFAutoRestart.MFAutoRestart;
import id.funix.mFAutoRestart.config.ConfigManager;
import id.funix.mFAutoRestart.database.DatabaseManager;
import id.funix.mFAutoRestart.lang.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Core restart engine for MF-AutoRestart.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Runs a persistent async second-tick task that checks scheduled restart times via
 *       {@link ScheduleManager}.</li>
 *   <li>When a restart is triggered (automatic or manual), manages the countdown sequence:
 *       <ul>
 *         <li>Broadcasts Chat, Title, and ActionBar warnings at configured intervals.</li>
 *         <li>Executes pre-restart console commands at their configured second marks.</li>
 *         <li>At {@code 0s}: sends all players to the BungeeCord lobby server via the
 *             {@code BungeeCord} plugin messaging channel.</li>
 *         <li>After the grace period: kicks any remaining players and calls
 *             {@link Bukkit#shutdown()} to safely stop the server.</li>
 *       </ul>
 *   </li>
 *   <li>Supports instant cancellation via {@link #cancelRestart()}.</li>
 * </ol>
 *
 * <h3>Threading Model</h3>
 * <ul>
 *   <li>The schedule-check tick runs {@code @Async} via a Bukkit async repeating task.</li>
 *   <li>The countdown itself runs {@code @MainThread} (via a Bukkit sync repeating task)
 *       to allow safe access to Bukkit Player APIs and plugin messaging.</li>
 * </ul>
 *
 * <p><b>Spark Hotspot Awareness:</b>
 * The broadcast loop iterates over {@link Bukkit#getOnlinePlayers()} once per warning interval.
 * On large servers (200+ players) this is O(n). Since it only fires at specific seconds
 * (not every tick), the TPS impact is negligible.</p>
 */
public final class RestartManager {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Ticks between each async schedule-check iteration (20 ticks = 1 second). */
    private static final long CHECK_INTERVAL_TICKS = 20L;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;
    private final ConfigManager configManager;
    private final ScheduleManager scheduleManager;
    private final LanguageManager languageManager;
    private final DatabaseManager databaseManager;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Atomic flag: true while a countdown is running. Prevents double-triggers. */
    private final AtomicBoolean countdownActive = new AtomicBoolean(false);

    /** Remaining seconds in the active countdown. */
    private final AtomicInteger secondsRemaining = new AtomicInteger(0);

    /** Whether the current countdown was triggered automatically by a schedule. */
    private volatile boolean triggeredBySchedule = false;

    /** The Bukkit task running the async time-checker. */
    private BukkitTask checkerTask;

    /** The Bukkit task running the active countdown. */
    private BukkitTask countdownTask;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@link RestartManager}.
     *
     * @param plugin          Plugin instance.
     * @param configManager   Config accessor.
     * @param scheduleManager Schedule matcher.
     * @param languageManager Language message resolver.
     * @param databaseManager Database manager for restart logging.
     */
    public RestartManager(
            final MFAutoRestart plugin,
            final ConfigManager configManager,
            final ScheduleManager scheduleManager,
            final LanguageManager languageManager,
            final DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduleManager = scheduleManager;
        this.languageManager = languageManager;
        this.databaseManager = databaseManager;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the background schedule-checker task.
     * Runs asynchronously every second. When a schedule fires, hands off to the main thread.
     *
     * @MainThread
     */
    public void startChecker() {
        checkerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            // @Async — only check; do not touch Bukkit API here
            if (countdownActive.get()) {
                return; // Already restarting; do not double-trigger
            }
            final ZonedDateTime now = ZonedDateTime.now(configManager.getTimezone());
            scheduleManager.getMatchingSchedule(now).ifPresent(schedule -> {
                // Hand off to the main thread to start the countdown safely
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().info("[RestartManager] Scheduled restart triggered: "
                            + schedule.toDisplayString());
                    startCountdown(configManager.getDefaultCountdown(), true);
                });
            });
        }, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);

        plugin.getLogger().info("[RestartManager] Schedule checker started.");
    }

    /**
     * Stops the background schedule-checker task.
     * Call this in {@code onDisable()}.
     *
     * @MainThread
     */
    public void stopChecker() {
        if (checkerTask != null && !checkerTask.isCancelled()) {
            checkerTask.cancel();
            checkerTask = null;
        }
    }

    // -----------------------------------------------------------------------
    // Countdown Control
    // -----------------------------------------------------------------------

    /**
     * Initiates the restart countdown.
     *
     * @param seconds  Duration of the countdown in seconds. Must be positive.
     * @param auto     {@code true} if triggered by a schedule, {@code false} if triggered manually.
     * @return {@code true} if countdown was started, {@code false} if already active.
     * @MainThread
     */
    public boolean startCountdown(final int seconds, final boolean auto) {
        if (countdownActive.get()) {
            return false;
        }

        // Fire custom AutoRestartStartEvent
        final id.funix.mFAutoRestart.api.event.AutoRestartStartEvent event =
                new id.funix.mFAutoRestart.api.event.AutoRestartStartEvent(seconds, !auto);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            plugin.getLogger().info("[RestartManager] Restart countdown initiation cancelled by custom event listener.");
            return false;
        }

        if (!countdownActive.compareAndSet(false, true)) {
            // Already running
            return false;
        }

        secondsRemaining.set(seconds);
        triggeredBySchedule = auto;

        plugin.getLogger().info("[RestartManager] Restart countdown started: "
                + seconds + "s (trigger=" + (auto ? "AUTOMATIC" : "MANUAL") + ")");

        // Countdown task runs on the MAIN thread every 20 ticks (1 second)
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            final int remaining = secondsRemaining.getAndDecrement();

            if (remaining <= 0) {
                // Countdown complete — execute the shutdown sequence
                executeShutdownSequence();
                return;
            }

            // --- Execute pre-restart commands for this second mark ---
            final Map<Integer, List<String>> commandMap = configManager.getPreRestartCommands();
            if (commandMap.containsKey(remaining)) {
                for (final String cmd : commandMap.get(remaining)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }

            // --- Broadcast warnings at configured intervals ---
            if (configManager.getWarningIntervals().contains(remaining)) {
                broadcastWarning(remaining);
            }

        }, 0L, 20L); // Start immediately, repeat every 20 ticks (1 second)

        return true;
    }

    /**
     * Cancels an active restart countdown.
     *
     * @return {@code true} if a countdown was running and was successfully cancelled.
     * @MainThread
     */
    public boolean cancelRestart() {
        return cancelRestart("Console");
    }

    /**
     * Cancels an active restart countdown with initiator context.
     *
     * @param cancelledBy The name of the player or Console that cancelled the restart.
     * @return {@code true} if a countdown was running and was successfully cancelled.
     * @MainThread
     */
    public boolean cancelRestart(final String cancelledBy) {
        if (!countdownActive.get()) {
            return false;
        }

        // Fire custom AutoRestartCancelEvent
        final id.funix.mFAutoRestart.api.event.AutoRestartCancelEvent event =
                new id.funix.mFAutoRestart.api.event.AutoRestartCancelEvent(cancelledBy);
        Bukkit.getPluginManager().callEvent(event);

        if (!countdownActive.compareAndSet(true, false)) {
            return false;
        }

        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
            countdownTask = null;
        }

        // Log cancellation to the database
        databaseManager.logRestart(
                System.currentTimeMillis(),
                triggeredBySchedule ? "AUTOMATIC" : "MANUAL",
                "CANCELLED");

        plugin.getLogger().info("[RestartManager] Restart countdown cancelled by " + cancelledBy + ".");
        return true;
    }

    /**
     * Returns the current number of seconds remaining in the countdown.
     *
     * @return Remaining seconds, or {@code 0} if no countdown is active.
     * @AnyThread
     */
    public int getSecondsRemaining() {
        return countdownActive.get() ? secondsRemaining.get() : 0;
    }

    /**
     * Returns whether a countdown is currently active.
     *
     * @return {@code true} if a countdown is in progress.
     * @AnyThread
     */
    public boolean isCountdownActive() {
        return countdownActive.get();
    }

    // -----------------------------------------------------------------------
    // Shutdown Sequence
    // -----------------------------------------------------------------------

    /**
     * Executes the safe server shutdown sequence on the main thread:
     * <ol>
     *   <li>Cancels the countdown task.</li>
     *   <li>Sends all players to the BungeeCord lobby server.</li>
     *   <li>Schedules a grace-period task to kick remaining players and stop the server.</li>
     * </ol>
     *
     * @MainThread
     */
    private void executeShutdownSequence() {
        // Fire custom AutoRestartPreShutdownEvent
        final id.funix.mFAutoRestart.api.event.AutoRestartPreShutdownEvent event =
                new id.funix.mFAutoRestart.api.event.AutoRestartPreShutdownEvent();
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            plugin.getLogger().info("[RestartManager] Shutdown sequence aborted by custom event listener.");
            // Reset the countdown state
            if (countdownTask != null && !countdownTask.isCancelled()) {
                countdownTask.cancel();
                countdownTask = null;
            }
            countdownActive.set(false);
            return;
        }

        // Cancel the countdown task
        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
            countdownTask = null;
        }
        countdownActive.set(false);

        plugin.getLogger().info("[RestartManager] Shutdown sequence initiated — redirecting players.");

        // Redirect all online players to the BungeeCord lobby
        final Component redirectMsg = languageManager.get("restart_redirecting");
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("mfautorestart.bypass")) {
                continue; // Skip bypass players
            }
            player.sendMessage(redirectMsg);
            sendToBungee(player, configManager.getLobbyServer());
        }

        // Schedule grace-period fallback kick + server stop
        final int gracePeriodTicks = configManager.getRedirectionGracePeriod() * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Kick any players who were not redirected (bypass holders stay)
            final Component kickMsg = languageManager.get("restart_kick_fallback");
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission("mfautorestart.bypass")) {
                    player.kick(kickMsg);
                }
            }

            // Log the completed restart
            databaseManager.logRestart(
                    System.currentTimeMillis(),
                    triggeredBySchedule ? "AUTOMATIC" : "MANUAL",
                    "COMPLETED");

            plugin.getLogger().info("[RestartManager] All players redirected/kicked. Stopping server.");
            Bukkit.shutdown();

        }, gracePeriodTicks);
    }

    // -----------------------------------------------------------------------
    // Broadcasting
    // -----------------------------------------------------------------------

    /**
     * Broadcasts restart warning messages to all online players via Chat, Title, and ActionBar.
     *
     * @param secondsLeft Seconds remaining to display in the warning.
     * @MainThread
     */
    private void broadcastWarning(final int secondsLeft) {
        final String timeFormatted = formatTime(secondsLeft);
        // TagResolver for the <time> placeholder used in all warning message keys
        final TagResolver timeResolver = Placeholder.component(
                "time", MM.deserialize("<white>" + timeFormatted + "</white>"));

        // Chat message
        final Component chatMsg = languageManager.get("warn_chat", timeResolver);

        // ActionBar
        final Component actionBar = languageManager.get("warn_actionbar", timeResolver);

        // Title + Subtitle
        final Component titleComp = languageManager.get("warn_title");
        final Component subtitleComp = languageManager.get("warn_subtitle", timeResolver);

        final Title.Times times = Title.Times.times(
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(2000),
                java.time.Duration.ofMillis(500));
        final Title title = Title.title(titleComp, subtitleComp, times);

        // Broadcast to all players (O(n) — cached snapshot, Spark-safe at low intervals)
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(chatMsg);
            player.showTitle(title);
            player.sendActionBar(actionBar);
        }
    }

    // -----------------------------------------------------------------------
    // BungeeCord Redirection
    // -----------------------------------------------------------------------

    /**
     * Sends a player to a BungeeCord server by name using the {@code BungeeCord} plugin channel.
     *
     * <p>The plugin must have registered the {@code BungeeCord} channel outgoing listener
     * (done in {@link MFAutoRestart#onEnable()}).
     * BungeeCord message delivery requires at least one player on the server.</p>
     *
     * @param player     The player to redirect.
     * @param serverName The target server name as defined in BungeeCord's {@code config.yml}.
     * @MainThread
     */
    private void sendToBungee(final Player player, final String serverName) {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final DataOutputStream out = new DataOutputStream(baos)) {

            out.writeUTF("Connect");
            out.writeUTF(serverName);

            // sendPluginMessage requires the channel to be registered and the sender to be online
            player.sendPluginMessage(plugin, "BungeeCord", baos.toByteArray());

        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[RestartManager] Failed to send BungeeCord redirect for player: "
                            + player.getName(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Formats a duration in seconds into a human-readable string.
     * Examples: {@code "5 minutes"}, {@code "1 minute"}, {@code "30 seconds"}, {@code "1 second"}.
     *
     * @param seconds Total seconds to format.
     * @return Formatted string.
     */
    private String formatTime(final int seconds) {
        if (seconds >= 60) {
            final int mins = seconds / 60;
            final int secs = seconds % 60;
            if (secs == 0) {
                return mins + "m";
            }
            return mins + "m " + secs + "s";
        }
        return seconds + "s";
    }
}
