package id.funix.mFAutoRestart.database;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Wraps a dedicated single-thread {@link ExecutorService} for all SQLite database I/O.
 *
 * <p>Using a dedicated single-thread executor ensures:</p>
 * <ul>
 *   <li>No two queries execute concurrently, preventing SQLite SQLITE_BUSY or write contention.</li>
 *   <li>All database work stays off the server's main tick thread, ensuring zero TPS impact.</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #shutdown()} must be called from {@code @MainThread} during
 * {@code onDisable()} to allow all pending tasks to flush before the JVM exits.</p>
 */
public final class SQLiteExecutor {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Named thread for easy identification in profilers (e.g., Spark). */
    private final ExecutorService executor;

    private final Logger logger;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates the SQLite executor with a single named daemon thread.
     *
     * @param logger Plugin logger for shutdown warnings.
     */
    public SQLiteExecutor(final Logger logger) {
        this.logger = logger;
        // Single-thread: serializes all DB access, preventing SQLite write collisions.
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "mf-autorestart-db");
            thread.setDaemon(true); // Does not prevent JVM shutdown
            return thread;
        });
    }

    // -----------------------------------------------------------------------
    // Accessor
    // -----------------------------------------------------------------------

    /**
     * Returns the underlying {@link ExecutorService} for use with {@link java.util.concurrent.CompletableFuture}.
     *
     * @return The single-thread executor.
     * @AnyThread
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Shuts down the executor gracefully.
     * Waits up to 5 seconds for pending tasks to complete before forcing shutdown.
     * Call this during {@code onDisable()}.
     *
     * @MainThread
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("[SQLiteExecutor] Executor did not terminate in 5 seconds — forcing shutdown.");
                executor.shutdownNow();
            } else {
                logger.info("[SQLiteExecutor] All pending database tasks completed.");
            }
        } catch (final InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
