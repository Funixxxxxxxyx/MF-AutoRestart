package id.funix.mFAutoRestart.model;

import java.time.DayOfWeek;

/**
 * Immutable data model representing a single configured restart schedule entry.
 *
 * <p>A schedule is either:</p>
 * <ul>
 *   <li>{@link ScheduleType#DAILY} — fires every day at the given hour and minute.</li>
 *   <li>{@link ScheduleType#WEEKLY} — fires on a specific {@link DayOfWeek} at the given hour and minute.</li>
 * </ul>
 *
 * <p>Instances may originate from {@code config.yml} or from persisted rows in the SQLite
 * {@code dynamic_schedules} table; the origin is tracked by the {@code origin} field.</p>
 */
public final class RestartSchedule {

    /** Indicates where a {@link RestartSchedule} was loaded from. */
    public enum Origin {
        /** Loaded from {@code config.yml}. Not persisted in the database. */
        CONFIG,
        /** Added at runtime via command and persisted in the SQLite database. */
        DATABASE
    }

    /** Classifies whether a schedule repeats daily or on a specific weekday. */
    public enum ScheduleType {
        DAILY,
        WEEKLY
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final ScheduleType type;

    /** Only relevant when {@link #type} is {@link ScheduleType#WEEKLY}. May be {@code null} for DAILY. */
    private final DayOfWeek dayOfWeek;

    /** Target hour in 24-hour format (0–23). */
    private final int hour;

    /** Target minute (0–59). */
    private final int minute;

    /** Whether this schedule came from config.yml or the SQLite database. */
    private final Origin origin;

    /**
     * Optional database primary-key ID; {@code -1} when the schedule originates from config,
     * or when the ID has not yet been assigned by the database.
     */
    private final int dbId;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@link RestartSchedule}.
     *
     * @param type      Schedule recurrence type (DAILY or WEEKLY).
     * @param dayOfWeek Day of the week; must be non-null when {@code type == WEEKLY}.
     * @param hour      Target hour (0–23).
     * @param minute    Target minute (0–59).
     * @param origin    Where this schedule was loaded from.
     * @param dbId      Database row ID, or {@code -1} if not applicable.
     */
    public RestartSchedule(
            final ScheduleType type,
            final DayOfWeek dayOfWeek,
            final int hour,
            final int minute,
            final Origin origin,
            final int dbId) {
        this.type = type;
        this.dayOfWeek = dayOfWeek;
        this.hour = hour;
        this.minute = minute;
        this.origin = origin;
        this.dbId = dbId;
    }

    // -----------------------------------------------------------------------
    // Factory helpers
    // -----------------------------------------------------------------------

    /**
     * Convenience factory for a DAILY schedule originating from config.yml.
     *
     * @param hour   Target hour (0–23).
     * @param minute Target minute (0–59).
     * @return A new {@link RestartSchedule}.
     */
    public static RestartSchedule daily(final int hour, final int minute) {
        return new RestartSchedule(ScheduleType.DAILY, null, hour, minute, Origin.CONFIG, -1);
    }

    /**
     * Convenience factory for a WEEKLY schedule originating from config.yml.
     *
     * @param dayOfWeek Target day.
     * @param hour      Target hour (0–23).
     * @param minute    Target minute (0–59).
     * @return A new {@link RestartSchedule}.
     */
    public static RestartSchedule weekly(final DayOfWeek dayOfWeek, final int hour, final int minute) {
        return new RestartSchedule(ScheduleType.WEEKLY, dayOfWeek, hour, minute, Origin.CONFIG, -1);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** @return Schedule type: DAILY or WEEKLY. */
    public ScheduleType getType() {
        return type;
    }

    /**
     * Returns the day of the week for WEEKLY schedules.
     *
     * @return The target {@link DayOfWeek}, or {@code null} for DAILY schedules.
     */
    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    /** @return Target hour in 24-hour format (0–23). */
    public int getHour() {
        return hour;
    }

    /** @return Target minute (0–59). */
    public int getMinute() {
        return minute;
    }

    /** @return The origin of this schedule (CONFIG or DATABASE). */
    public Origin getOrigin() {
        return origin;
    }

    /**
     * Returns the SQLite primary-key row ID for schedules that originated from the database.
     *
     * @return The row ID, or {@code -1} if this schedule is from config.yml.
     */
    public int getDbId() {
        return dbId;
    }

    // -----------------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a human-readable display string for this schedule entry.
     * Examples: {@code "DAILY 04:00"}, {@code "WEEKLY SUNDAY 04:00"}
     *
     * @return Formatted schedule string.
     */
    public String toDisplayString() {
        final String timeStr = String.format("%02d:%02d", hour, minute);
        if (type == ScheduleType.DAILY) {
            return "DAILY " + timeStr;
        }
        return "WEEKLY " + (dayOfWeek != null ? dayOfWeek.name() : "?") + " " + timeStr;
    }

    @Override
    public String toString() {
        return "RestartSchedule{type=" + type
                + ", dayOfWeek=" + dayOfWeek
                + ", hour=" + hour
                + ", minute=" + minute
                + ", origin=" + origin
                + ", dbId=" + dbId + "}";
    }
}
