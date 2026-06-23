package id.funix.mFAutoRestart;

import id.funix.mFAutoRestart.command.AutoRestartCommand;
import id.funix.mFAutoRestart.config.ConfigManager;
import id.funix.mFAutoRestart.database.DatabaseManager;
import id.funix.mFAutoRestart.database.SQLiteExecutor;
import id.funix.mFAutoRestart.lang.LanguageManager;
import id.funix.mFAutoRestart.manager.RestartManager;
import id.funix.mFAutoRestart.manager.ScheduleManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import id.funix.mFAutoRestart.api.MFAutoRestartAPI;

import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Main entry point for the <strong>MF-AutoRestart</strong> plugin.
 *
 * <h3>Enable Sequence</h3>
 * <ol>
 *   <li>Print the ASCII watermark banner.</li>
 *   <li>Initialize {@link SQLiteExecutor} (single-thread DB executor).</li>
 *   <li>Initialize {@link ConfigManager} (loads {@code config.yml}).</li>
 *   <li>Initialize {@link LanguageManager} (loads locale file).</li>
 *   <li>Open {@link DatabaseManager} (creates/migrates SQLite tables).</li>
 *   <li>Initialize {@link ScheduleManager} and load dynamic schedules from DB.</li>
 *   <li>Initialize {@link RestartManager} and start the async schedule-checker task.</li>
 *   <li>Register commands and BungeeCord plugin messaging channel.</li>
 * </ol>
 *
 * <h3>Disable Sequence</h3>
 * <ol>
 *   <li>Stop the schedule-checker task and any active countdown.</li>
 *   <li>Flush and shut down the DB executor (waits up to 5 seconds).</li>
 *   <li>Close the SQLite connection.</li>
 *   <li>Unregister the BungeeCord plugin messaging channel.</li>
 * </ol>
 *
 * <p><b>PlugMan Reload Safety:</b> All static state is avoided; all lifecycle resources
 * are properly initialized in {@code onEnable()} and released in {@code onDisable()}.
 * The plugin is safe to reload with PlugMan.</p>
 */
public final class MFAutoRestart extends JavaPlugin implements MFAutoRestartAPI {

    // -----------------------------------------------------------------------
    // Component References
    // -----------------------------------------------------------------------

    private SQLiteExecutor sqliteExecutor;
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private DatabaseManager databaseManager;
    private ScheduleManager scheduleManager;
    private RestartManager restartManager;

    // -----------------------------------------------------------------------
    // JavaPlugin Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        // 1. Print banner — first action, unconditional
        printBanner();

        // 2. Initialize SQLite thread executor
        sqliteExecutor = new SQLiteExecutor(getLogger());

        // 3. Load configuration
        configManager = new ConfigManager(this);

        // 4. Load language files
        languageManager = new LanguageManager(this);

        // 5. Open SQLite connection and initialize schema
        databaseManager = new DatabaseManager(this, sqliteExecutor);
        try {
            databaseManager.open();
        } catch (final SQLException e) {
            getLogger().severe("[MFAutoRestart] FATAL: Failed to connect to SQLite database. Disabling plugin.");
            getLogger().severe("[MFAutoRestart] Error: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 6. Initialize schedule manager and load dynamic schedules from DB
        scheduleManager = new ScheduleManager(this, configManager, databaseManager);
        scheduleManager.loadDynamicSchedulesAsync();

        // 7. Initialize restart engine and start the background time-checker
        restartManager = new RestartManager(
                this, configManager, scheduleManager, languageManager, databaseManager);
        restartManager.startChecker();

        // 8. Register the BungeeCord outgoing plugin messaging channel
        //    Required for sendPluginMessage("BungeeCord", ...) calls in RestartManager.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // 9. Register commands
        registerCommands();

        // 10. Register API in ServicesManager
        getServer().getServicesManager().register(MFAutoRestartAPI.class, this, this, ServicePriority.Normal);

        // 11. Register PlaceholderAPI expansion if present
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new id.funix.mFAutoRestart.integration.papi.MFAutoRestartPlaceholderExpansion(this).register();
            getLogger().info("[MFAutoRestart] Registered PlaceholderAPI expansion.");
        }

        getLogger().info("[MFAutoRestart] Plugin enabled successfully. "
                + "Timezone: " + configManager.getTimezone().getId());
    }

    @Override
    public void onDisable() {
        getLogger().info("[MFAutoRestart] Disabling plugin...");

        // Stop the schedule checker and cancel any active countdown gracefully
        if (restartManager != null) {
            restartManager.stopChecker();
            restartManager.cancelRestart();
        }

        // Flush and shut down the async DB executor (waits up to 5 seconds for pending tasks)
        if (sqliteExecutor != null) {
            sqliteExecutor.shutdown();
        }

        // Close the SQLite database connection
        if (databaseManager != null) {
            databaseManager.close();
        }

        // Unregister BungeeCord channel
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");

        getLogger().info("[MFAutoRestart] Plugin disabled cleanly.");
    }

    // -----------------------------------------------------------------------
    // Command Registration
    // -----------------------------------------------------------------------

    /**
     * Registers the {@code /autorestart} command executor and tab-completer.
     *
     * @MainThread
     */
    private void registerCommands() {
        final AutoRestartCommand commandHandler = new AutoRestartCommand(
                this, configManager, scheduleManager, restartManager, languageManager);

        final PluginCommand cmd = getCommand("autorestart");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        } else {
            getLogger().severe("[MFAutoRestart] Could not find 'autorestart' command in plugin.yml!");
        }
    }

    // -----------------------------------------------------------------------
    // Console Watermark Banner
    // -----------------------------------------------------------------------

    /**
     * Prints the hardcoded ASCII watermark banner to the server console on startup.
     *
     * <p>This is intentionally hardcoded and MUST NOT be soft-coded.
     * The banner identifies the plugin at startup for debugging and support purposes.</p>
     *
     * @MainThread
     */
    private void printBanner() {
        final Logger log = getLogger();
        log.info("  =============================================");
        log.info("      __  __ ___      _         _           ");
        log.info("     |  \\/  | __|    /_\\   _  _| |_ ___     ");
        log.info("     | |\\/| | _|    / _ \\ | || |  _/ _ \\    ");
        log.info("     |_|  |_|_|    /_/ \\_\\ \\_,_|\\__\\___/    ");
        log.info("          R e s t a r t                     ");
        log.info("  =============================================");
        log.info("    Plugin  : " + getPluginMeta().getName() + " v" + getPluginMeta().getVersion());
        log.info("    Platform: Paper 1.21.x | Java 21");
        log.info("    Authors : " + String.join(", ", getPluginMeta().getAuthors()));
        log.info("  =============================================");
    }

    // -----------------------------------------------------------------------
    // Public API Accessors (for inter-component access if needed)
    // -----------------------------------------------------------------------

    /** @return The active {@link ConfigManager}. */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** @return The active {@link LanguageManager}. */
    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    /** @return The active {@link RestartManager}. */
    public RestartManager getRestartManager() {
        return restartManager;
    }

    /** @return The active {@link ScheduleManager}. */
    public ScheduleManager getScheduleManager() {
        return scheduleManager;
    }

    // -----------------------------------------------------------------------
    // MFAutoRestartAPI Implementation
    // -----------------------------------------------------------------------

    @Override
    public boolean isRestartActive() {
        return restartManager != null && restartManager.isCountdownActive();
    }

    @Override
    public Optional<Integer> getSecondsRemaining() {
        if (restartManager == null || !restartManager.isCountdownActive()) {
            return Optional.empty();
        }
        return Optional.of(restartManager.getSecondsRemaining());
    }

    @Override
    public ZonedDateTime getNextScheduledRestart() {
        if (scheduleManager == null) {
            return null;
        }
        return scheduleManager.getNextScheduledTime().orElse(null);
    }

    @Override
    public boolean startRestart(final int seconds, final boolean manual) {
        if (restartManager == null) {
            return false;
        }
        return restartManager.startCountdown(seconds, !manual);
    }

    @Override
    public boolean cancelRestart() {
        if (restartManager == null) {
            return false;
        }
        return restartManager.cancelRestart("API");
    }
}
