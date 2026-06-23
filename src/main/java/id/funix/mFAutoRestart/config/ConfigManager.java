package id.funix.mFAutoRestart.config;

import id.funix.mFAutoRestart.MFAutoRestart;
import id.funix.mFAutoRestart.model.RestartSchedule;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Provides strongly-typed, cached access to all values defined in {@code config.yml}.
 *
 * <p>Call {@link #reload()} to hot-reload the configuration from disk without restarting the server.
 * All getters read from in-memory cached fields and are safe to call from {@code @AnyThread},
 * but {@link #reload()} itself must only be invoked from the main thread.</p>
 */
public final class ConfigManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;

    // Cached config values — populated by reload()
    private ZoneId timezone;
    private String lobbyServer;
    private int redirectionGracePeriod;
    private int defaultCountdown;
    private List<Integer> warningIntervals;
    private List<RestartSchedule> configSchedules;

    /**
     * Map of seconds-remaining → list of console commands to execute at that tick.
     * Key: seconds remaining (e.g., 300, 60, 0).
     */
    private Map<Integer, List<String>> preRestartCommands;

    // -----------------------------------------------------------------------
    // Constructor & Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initializes the {@link ConfigManager} and immediately loads config values.
     *
     * @param plugin The plugin instance.
     * @MainThread
     */
    public ConfigManager(final MFAutoRestart plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Reloads all configuration values from disk.
     * Must be called on the main server thread.
     *
     * @MainThread
     */
    public void reload() {
        // Let Bukkit handle saving the default config.yml if missing
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // --- Timezone ---
        final String tzStr = plugin.getConfig().getString("settings.timezone", "Asia/Jakarta");
        timezone = parseTimezone(tzStr);

        // --- Lobby server ---
        lobbyServer = plugin.getConfig().getString("settings.lobby-server", "lobby");

        // --- Grace period ---
        redirectionGracePeriod = Math.max(1, plugin.getConfig().getInt("settings.redirection-grace-period", 3));

        // --- Default countdown ---
        defaultCountdown = Math.max(5, plugin.getConfig().getInt("default-countdown", 300));

        // --- Warning intervals (sorted descending for efficient iteration) ---
        final List<Integer> rawIntervals = plugin.getConfig().getIntegerList("warning-intervals");
        warningIntervals = new ArrayList<>(rawIntervals);
        warningIntervals.sort(Collections.reverseOrder());

        // --- Config-defined schedules ---
        configSchedules = parseSchedules(plugin.getConfig().getStringList("schedules"));

        // --- Pre-restart command map ---
        preRestartCommands = parsePreRestartCommands();

        plugin.getLogger().info("[ConfigManager] Configuration loaded. "
                + "Timezone: " + timezone.getId()
                + " | Lobby: " + lobbyServer
                + " | Schedules: " + configSchedules.size());
    }

    // -----------------------------------------------------------------------
    // Parsing Helpers
    // -----------------------------------------------------------------------

    /**
     * Parses a timezone string into a {@link ZoneId}.
     * Falls back to {@code Asia/Jakarta} on any error.
     *
     * @param tzStr The timezone string from config (e.g., "Asia/Jakarta", "UTC").
     * @return The resolved {@link ZoneId}.
     */
    private ZoneId parseTimezone(final String tzStr) {
        try {
            return ZoneId.of(tzStr);
        } catch (final Exception e) {
            plugin.getLogger().warning("[ConfigManager] Invalid timezone '" + tzStr
                    + "'. Falling back to Asia/Jakarta.");
            return ZoneId.of("Asia/Jakarta");
        }
    }

    /**
     * Parses the {@code schedules} list from config.yml into {@link RestartSchedule} objects.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li>{@code DAILY:HH:mm}</li>
     *   <li>{@code WEEKLY:DAY:HH:mm}</li>
     * </ul>
     *
     * @param rawSchedules The raw string list from config.
     * @return Parsed list of {@link RestartSchedule} instances.
     */
    private List<RestartSchedule> parseSchedules(final List<String> rawSchedules) {
        final List<RestartSchedule> result = new ArrayList<>();
        for (final String raw : rawSchedules) {
            try {
                final String[] parts = raw.split(":");
                if (parts.length < 2) {
                    plugin.getLogger().warning("[ConfigManager] Invalid schedule format: '" + raw + "'. Skipping.");
                    continue;
                }

                final String type = parts[0].toUpperCase();
                if ("DAILY".equals(type) && parts.length >= 3) {
                    final int hour = Integer.parseInt(parts[1]);
                    final int minute = Integer.parseInt(parts[2]);
                    result.add(new RestartSchedule(
                            RestartSchedule.ScheduleType.DAILY, null,
                            hour, minute, RestartSchedule.Origin.CONFIG, -1));

                } else if ("WEEKLY".equals(type) && parts.length >= 4) {
                    final DayOfWeek day = DayOfWeek.valueOf(parts[1].toUpperCase());
                    final int hour = Integer.parseInt(parts[2]);
                    final int minute = Integer.parseInt(parts[3]);
                    result.add(new RestartSchedule(
                            RestartSchedule.ScheduleType.WEEKLY, day,
                            hour, minute, RestartSchedule.Origin.CONFIG, -1));

                } else {
                    plugin.getLogger().warning("[ConfigManager] Unrecognized schedule: '" + raw + "'. Skipping.");
                }
            } catch (final Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[ConfigManager] Failed to parse schedule: '" + raw + "'.", e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parses the {@code pre-restart-commands} section from config.yml.
     *
     * @return A map from seconds-remaining to the list of commands to execute at that time.
     */
    private Map<Integer, List<String>> parsePreRestartCommands() {
        final Map<Integer, List<String>> map = new HashMap<>();
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("pre-restart-commands");
        if (section == null) {
            return Collections.unmodifiableMap(map);
        }
        for (final String key : section.getKeys(false)) {
            try {
                final int seconds = Integer.parseInt(key);
                final List<String> cmds = section.getStringList(key);
                if (!cmds.isEmpty()) {
                    map.put(seconds, Collections.unmodifiableList(cmds));
                }
            } catch (final NumberFormatException e) {
                plugin.getLogger().warning("[ConfigManager] Invalid pre-restart-commands key: '" + key
                        + "'. Keys must be integers (seconds remaining). Skipping.");
            }
        }
        return Collections.unmodifiableMap(map);
    }

    // -----------------------------------------------------------------------
    // Accessors (all @AnyThread safe — reads cached values)
    // -----------------------------------------------------------------------

    /**
     * Returns the configured timezone.
     *
     * @return The active {@link ZoneId}.
     * @AnyThread
     */
    public ZoneId getTimezone() {
        return timezone;
    }

    /**
     * Returns the BungeeCord lobby server name.
     *
     * @return The lobby server name (e.g., {@code "lobby"}).
     * @AnyThread
     */
    public String getLobbyServer() {
        return lobbyServer;
    }

    /**
     * Returns the grace period (in seconds) between sending BungeeCord redirect messages
     * and kicking any remaining players.
     *
     * @return Grace period in seconds (minimum 1).
     * @AnyThread
     */
    public int getRedirectionGracePeriod() {
        return redirectionGracePeriod;
    }

    /**
     * Returns the default countdown duration in seconds.
     *
     * @return Default countdown seconds.
     * @AnyThread
     */
    public int getDefaultCountdown() {
        return defaultCountdown;
    }

    /**
     * Returns the list of warning broadcast intervals (seconds remaining), sorted descending.
     *
     * @return Immutable list of integers.
     * @AnyThread
     */
    public List<Integer> getWarningIntervals() {
        return warningIntervals;
    }

    /**
     * Returns the restart schedules defined in {@code config.yml}.
     *
     * @return Immutable list of {@link RestartSchedule} instances.
     * @AnyThread
     */
    public List<RestartSchedule> getConfigSchedules() {
        return configSchedules;
    }

    /**
     * Returns the map of pre-restart commands, keyed by seconds remaining.
     *
     * @return Immutable map: seconds → list of console command strings.
     * @AnyThread
     */
    public Map<Integer, List<String>> getPreRestartCommands() {
        return preRestartCommands;
    }
}
