package id.funix.mFAutoRestart.manager;

import id.funix.mFAutoRestart.MFAutoRestart;
import id.funix.mFAutoRestart.config.ConfigManager;
import id.funix.mFAutoRestart.database.DatabaseManager;
import id.funix.mFAutoRestart.model.RestartSchedule;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Manages the combined list of restart schedules from both {@code config.yml}
 * and the SQLite {@code dynamic_schedules} table.
 *
 * <h3>Schedule Sources</h3>
 * <ul>
 *   <li><b>Config schedules</b>: Loaded from {@code config.yml}. Read-only at runtime;
 *       reloaded when an admin runs {@code /ar reload}.</li>
 *   <li><b>Dynamic schedules</b>: Added at runtime via {@code /ar schedule add} and persisted
 *       in SQLite so they survive server restarts.</li>
 * </ul>
 *
 * <h3>Matching Logic</h3>
 * <p>{@link #getMatchingSchedule(ZonedDateTime)} compares the given moment's hour and minute
 * against every active schedule (with day-of-week check for WEEKLY types). It returns the first
 * match, ignoring schedules whose minute has already passed within the current minute window.</p>
 *
 * <p><b>Threading:</b>
 * {@link #checkAndTrigger(ZonedDateTime)} is called from the async time-checker task
 * and is designed to be safe from {@code @Async}. All write operations that reach Bukkit's
 * scheduler from the caller's side are handled by {@link RestartManager}.</p>
 */
public final class ScheduleManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;

    /**
     * CopyOnWriteArrayList allows read-heavy, write-rare concurrent access
     * without locking — safe for both async reads and infrequent writes from any thread.
     * Spark note: reads are O(1) snapshot, no contention risk.
     */
    private final CopyOnWriteArrayList<RestartSchedule> dynamicSchedules = new CopyOnWriteArrayList<>();

    // -----------------------------------------------------------------------
    // Constructor & Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Creates the {@link ScheduleManager}.
     *
     * @param plugin          The plugin instance.
     * @param configManager   The config accessor.
     * @param databaseManager The database manager.
     */
    public ScheduleManager(
            final MFAutoRestart plugin,
            final ConfigManager configManager,
            final DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
    }

    /**
     * Loads all dynamic schedules from the SQLite database asynchronously.
     * Call this once after {@link DatabaseManager#open()} during startup.
     *
     * @Async — result is written into dynamicSchedules on the DB thread.
     */
    public void loadDynamicSchedulesAsync() {
        databaseManager.loadAllSchedules().thenAccept(loaded -> {
            dynamicSchedules.clear();
            dynamicSchedules.addAll(loaded);
            plugin.getLogger().info("[ScheduleManager] Loaded " + loaded.size()
                    + " dynamic schedule(s) from database.");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE,
                    "[ScheduleManager] Failed to load dynamic schedules from database.", ex);
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Schedule Matching
    // -----------------------------------------------------------------------

    /**
     * Checks whether the given {@link ZonedDateTime} moment matches any active schedule.
     *
     * <p>A match occurs when the moment's hour and minute both equal a schedule's target
     * and the second is exactly {@code 0} (to ensure firing once per minute window).
     * WEEKLY schedules additionally require the day-of-week to match.</p>
     *
     * @param now The current {@link ZonedDateTime}, already in the configured timezone.
     * @return An {@link Optional} containing the matching schedule, or empty if none matched.
     * @AnyThread
     */
    public Optional<RestartSchedule> getMatchingSchedule(final ZonedDateTime now) {
        // Only fire at the exact start of the target minute (second == 0)
        if (now.getSecond() != 0) {
            return Optional.empty();
        }

        final int currentHour = now.getHour();
        final int currentMinute = now.getMinute();

        // Check all schedules: config first, then dynamic
        final List<RestartSchedule> allSchedules = getAllSchedules();
        for (final RestartSchedule schedule : allSchedules) {
            if (schedule.getHour() == currentHour && schedule.getMinute() == currentMinute) {
                if (schedule.getType() == RestartSchedule.ScheduleType.DAILY) {
                    return Optional.of(schedule);
                }
                // WEEKLY: also verify the day of week
                if (schedule.getType() == RestartSchedule.ScheduleType.WEEKLY
                        && schedule.getDayOfWeek() == now.getDayOfWeek()) {
                    return Optional.of(schedule);
                }
            }
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Dynamic Schedule Management
    // -----------------------------------------------------------------------

    /**
     * Adds a dynamic schedule, persists it to SQLite, and adds it to the active list.
     *
     * @param schedule The schedule to add. Should have {@link RestartSchedule.Origin#DATABASE}.
     * @return A future resolving to the assigned database row ID, or {@code -1} on failure.
     * @Async
     */
    public void addDynamicSchedule(final RestartSchedule schedule,
                                   final java.util.function.Consumer<RestartSchedule> onSuccess) {
        databaseManager.insertSchedule(schedule).thenAccept(generatedId -> {
            if (generatedId != -1) {
                // Recreate the schedule with the assigned DB id
                final RestartSchedule persisted = new RestartSchedule(
                        schedule.getType(), schedule.getDayOfWeek(),
                        schedule.getHour(), schedule.getMinute(),
                        RestartSchedule.Origin.DATABASE, generatedId);
                dynamicSchedules.add(persisted);
                plugin.getLogger().info("[ScheduleManager] Dynamic schedule added (id=" + generatedId + "): "
                        + persisted.toDisplayString());
                onSuccess.accept(persisted);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "[ScheduleManager] Failed to persist dynamic schedule.", ex);
            return null;
        });
    }

    /**
     * Returns all active schedules (config + dynamic) as a combined, unmodifiable snapshot list.
     *
     * @return Combined list of all schedules.
     * @AnyThread
     */
    public List<RestartSchedule> getAllSchedules() {
        final List<RestartSchedule> all = new ArrayList<>(configManager.getConfigSchedules());
        all.addAll(dynamicSchedules);
        return Collections.unmodifiableList(all);
    }

    /**
     * Returns only the dynamic (database-persisted) schedules.
     *
     * @return Unmodifiable snapshot of dynamic schedules.
     * @AnyThread
     */
    public List<RestartSchedule> getDynamicSchedules() {
        return Collections.unmodifiableList(new ArrayList<>(dynamicSchedules));
    }

    /**
     * Clears all in-memory dynamic schedules (used during reload).
     * Does NOT delete from the database.
     *
     * @MainThread
     */
    public void clearAndReloadDynamic() {
        dynamicSchedules.clear();
        loadDynamicSchedulesAsync();
    }

    /**
     * Calculates the next scheduled restart time based on all active schedules and the current
     * time in the configured timezone.
     *
     * @return An {@link Optional} containing the next scheduled {@link ZonedDateTime}, or empty.
     * @AnyThread
     */
    public Optional<ZonedDateTime> getNextScheduledTime() {
        final ZonedDateTime now = ZonedDateTime.now(configManager.getTimezone());
        ZonedDateTime earliest = null;

        for (final RestartSchedule schedule : getAllSchedules()) {
            ZonedDateTime candidate = now.withHour(schedule.getHour())
                    .withMinute(schedule.getMinute())
                    .withSecond(0)
                    .withNano(0);

            if (schedule.getType() == RestartSchedule.ScheduleType.WEEKLY && schedule.getDayOfWeek() != null) {
                // Advance to the correct day of the week
                while (candidate.getDayOfWeek() != schedule.getDayOfWeek()) {
                    candidate = candidate.plusDays(1);
                }
            }

            // If the candidate time has already passed today/this occurrence, push to next occurrence
            if (!candidate.isAfter(now)) {
                if (schedule.getType() == RestartSchedule.ScheduleType.DAILY) {
                    candidate = candidate.plusDays(1);
                } else {
                    candidate = candidate.plusWeeks(1);
                }
            }

            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
        }

        return Optional.ofNullable(earliest);
    }
}
