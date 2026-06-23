package id.funix.mFAutoRestart.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when an active restart countdown is cancelled.
 */
public class AutoRestartCancelEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String cancelledBy;

    public AutoRestartCancelEvent(final String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    /**
     * Gets the name of the initiator who cancelled the restart (e.g. player name or "Console").
     *
     * @return The name of the cancelling entity.
     */
    public @NotNull String getCancelledBy() {
        return cancelledBy;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
