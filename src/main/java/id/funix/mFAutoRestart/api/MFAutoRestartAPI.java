package id.funix.mFAutoRestart.api;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Public API for the MF-AutoRestart plugin.
 * Other plugins can retrieve an instance of this API via the Bukkit Services Manager.
 */
public interface MFAutoRestartAPI {

    /**
     * Checks if a restart countdown is currently running.
     *
     * @return true if a countdown is active, false otherwise.
     */
    boolean isRestartActive();

    /**
     * Gets the remaining seconds of the active restart countdown.
     *
     * @return an Optional containing the seconds remaining, or empty if no countdown is active.
     */
    Optional<Integer> getSecondsRemaining();

    /**
     * Gets the date and time of the next scheduled restart.
     *
     * @return the ZonedDateTime of the next scheduled restart, or null if none are scheduled.
     */
    ZonedDateTime getNextScheduledRestart();

    /**
     * Programmatically starts a restart countdown.
     *
     * @param seconds The countdown duration in seconds.
     * @param manual  Whether this was triggered by a user action.
     * @return true if the restart successfully started, false if one was already active.
     */
    boolean startRestart(int seconds, boolean manual);

    /**
     * Programmatically cancels the active restart countdown.
     *
     * @return true if a countdown was successfully cancelled, false if none was active.
     */
    boolean cancelRestart();
}
