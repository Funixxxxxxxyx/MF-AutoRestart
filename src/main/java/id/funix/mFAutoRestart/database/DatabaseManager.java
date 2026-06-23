package id.funix.mFAutoRestart.database;

import id.funix.mFAutoRestart.MFAutoRestart;
import id.funix.mFAutoRestart.model.RestartSchedule;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Manages the SQLite database connection for MF-AutoRestart.
 *
 * <p>The database file is stored at {@code plugins/MF-AutoRestart/data.db}.
 * All schema initialization (table creation, migration) happens synchronously on enable
 * and all subsequent queries are executed via {@link SQLiteExecutor} off the main thread.</p>
 *
 * <p>Tables managed:</p>
 * <ul>
 *   <li>{@code dynamic_schedules} — runtime-added restart schedules.</li>
 *   <li>{@code restart_logs} — historical record of all restart events.</li>
 *   <li>{@code schema_version} — tracks migration version for future upgrades.</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #open()} and {@link #close()} are {@code @MainThread}.
 * All query methods are {@code @Async} — they run on the {@link SQLiteExecutor}'s thread.</p>
 */
public final class DatabaseManager {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Current schema version. Increment when adding new tables or columns. */
    private static final int SCHEMA_VERSION = 1;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;
    private final SQLiteExecutor executor;

    /** Persistent connection reused across the plugin's lifetime. */
    private Connection connection;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@link DatabaseManager}.
     *
     * @param plugin   The plugin instance.
     * @param executor The single-thread async executor to run queries on.
     */
    public DatabaseManager(final MFAutoRestart plugin, final SQLiteExecutor executor) {
        this.plugin = plugin;
        this.executor = executor;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Opens the SQLite connection and runs schema initialization/migration.
     * This is called synchronously during {@code onEnable()}.
     *
     * @throws SQLException If the connection cannot be established.
     * @MainThread
     */
    public void open() throws SQLException {
        // Ensure the plugin data folder exists
        final File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        final File dbFile = new File(dataFolder, "data.db");
        final String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        // Load the shaded SQLite JDBC driver
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath.", e);
        }

        connection = DriverManager.getConnection(url);
        // Disable auto-commit for manual transaction control
        connection.setAutoCommit(true);

        plugin.getLogger().info("[DatabaseManager] Connected to SQLite: " + dbFile.getName());
        initSchema();
    }

    /**
     * Closes the SQLite connection gracefully.
     * Called synchronously during {@code onDisable()}.
     *
     * @MainThread
     */
    public void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("[DatabaseManager] SQLite connection closed.");
                }
            } catch (final SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DatabaseManager] Error closing SQLite connection.", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Schema Initialization
    // -----------------------------------------------------------------------

    /**
     * Creates all required tables if they do not exist, and runs any pending migrations.
     * Called synchronously during {@link #open()}.
     *
     * @throws SQLException If table creation or migration fails.
     * @MainThread
     */
    private void initSchema() throws SQLException {
        try (final Statement stmt = connection.createStatement()) {

            // Schema version tracker
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER NOT NULL
                    )
                    """);

            // Dynamic schedules added via /ar schedule add
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dynamic_schedules (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        type        VARCHAR(16) NOT NULL,
                        day_of_week VARCHAR(16),
                        time_str    VARCHAR(8)  NOT NULL
                    )
                    """);

            // Historical log of all restart events
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS restart_logs (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp    BIGINT      NOT NULL,
                        trigger_type VARCHAR(16) NOT NULL,
                        status       VARCHAR(16) NOT NULL
                    )
                    """);
        }

        // Check and set schema version
        try (final Statement stmt = connection.createStatement();
             final ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            if (!rs.next()) {
                // First time — insert initial version
                try (final PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO schema_version (version) VALUES (?)")) {
                    insert.setInt(1, SCHEMA_VERSION);
                    insert.execute();
                }
            }
        }

        plugin.getLogger().info("[DatabaseManager] Schema initialized at version " + SCHEMA_VERSION + ".");
    }

    // -----------------------------------------------------------------------
    // Dynamic Schedules CRUD
    // -----------------------------------------------------------------------

    /**
     * Inserts a new dynamic schedule into the database asynchronously.
     *
     * @param schedule The schedule to insert. Must not be null.
     * @return A {@link CompletableFuture} that resolves to the generated row ID.
     * @Async
     */
    public CompletableFuture<Integer> insertSchedule(final RestartSchedule schedule) {
        return CompletableFuture.supplyAsync(() -> {
            final String sql = "INSERT INTO dynamic_schedules (type, day_of_week, time_str) VALUES (?, ?, ?)";
            try (final PreparedStatement ps = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, schedule.getType().name());
                ps.setString(2, schedule.getDayOfWeek() != null ? schedule.getDayOfWeek().name() : null);
                ps.setString(3, String.format("%02d:%02d", schedule.getHour(), schedule.getMinute()));
                ps.execute();

                try (final ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            } catch (final SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "[DatabaseManager] Failed to insert schedule: " + schedule, e);
            }
            return -1;
        }, executor.getExecutor());
    }

    /**
     * Loads all dynamic schedules from the database asynchronously.
     *
     * @return A {@link CompletableFuture} resolving to the list of persisted {@link RestartSchedule} entries.
     * @Async
     */
    public CompletableFuture<List<RestartSchedule>> loadAllSchedules() {
        return CompletableFuture.supplyAsync(() -> {
            final List<RestartSchedule> result = new ArrayList<>();
            final String sql = "SELECT id, type, day_of_week, time_str FROM dynamic_schedules";
            try (final Statement stmt = connection.createStatement();
                 final ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    final int id = rs.getInt("id");
                    final RestartSchedule.ScheduleType type =
                            RestartSchedule.ScheduleType.valueOf(rs.getString("type"));
                    final String dayStr = rs.getString("day_of_week");
                    final DayOfWeek day = (dayStr != null && !dayStr.isEmpty())
                            ? DayOfWeek.valueOf(dayStr) : null;
                    final String[] timeParts = rs.getString("time_str").split(":");
                    final int hour = Integer.parseInt(timeParts[0]);
                    final int minute = Integer.parseInt(timeParts[1]);

                    result.add(new RestartSchedule(type, day, hour, minute, RestartSchedule.Origin.DATABASE, id));
                }
            } catch (final SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to load schedules.", e);
            }
            return result;
        }, executor.getExecutor());
    }

    /**
     * Deletes a dynamic schedule from the database by its row ID asynchronously.
     *
     * @param id The SQLite primary-key ID of the schedule to delete.
     * @return A {@link CompletableFuture} that resolves to {@code true} on success.
     * @Async
     */
    public CompletableFuture<Boolean> deleteSchedule(final int id) {
        return CompletableFuture.supplyAsync(() -> {
            final String sql = "DELETE FROM dynamic_schedules WHERE id = ?";
            try (final PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (final SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "[DatabaseManager] Failed to delete schedule id=" + id, e);
                return false;
            }
        }, executor.getExecutor());
    }

    // -----------------------------------------------------------------------
    // Restart Log
    // -----------------------------------------------------------------------

    /**
     * Inserts a new restart event log record asynchronously.
     *
     * @param timestamp   Unix epoch millisecond timestamp.
     * @param triggerType {@code "AUTOMATIC"} or {@code "MANUAL"}.
     * @param status      {@code "COMPLETED"} or {@code "CANCELLED"}.
     * @return A {@link CompletableFuture} that resolves when the insert is complete.
     * @Async
     */
    public CompletableFuture<Void> logRestart(
            final long timestamp,
            final String triggerType,
            final String status) {
        return CompletableFuture.runAsync(() -> {
            final String sql = "INSERT INTO restart_logs (timestamp, trigger_type, status) VALUES (?, ?, ?)";
            try (final PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, timestamp);
                ps.setString(2, triggerType);
                ps.setString(3, status);
                ps.execute();
            } catch (final SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[DatabaseManager] Failed to log restart event.", e);
            }
        }, executor.getExecutor());
    }
}
