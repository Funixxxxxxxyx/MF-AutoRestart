package id.funix.mFAutoRestart.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a restart countdown is initiated (either automatically or manually).
 * This event is cancellable.
 */
public class AutoRestartStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int durationSeconds;
    private final boolean manual;
    private boolean cancelled = false;

    public AutoRestartStartEvent(final int durationSeconds, final boolean manual) {
        this.durationSeconds = durationSeconds;
        this.manual = manual;
    }

    /**
     * Gets the starting duration of the restart countdown in seconds.
     *
     * @return Duration in seconds.
     */
    public int getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * Checks if the restart was triggered manually (by a command/API) or automatically (by the scheduler).
     *
     * @return true if manual, false if automatic scheduler.
     */
    public boolean isManual() {
        return manual;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
