package id.funix.mFAutoRestart.integration.papi;

import id.funix.mFAutoRestart.MFAutoRestart;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * PlaceholderAPI expansion for MF-AutoRestart.
 * Exposes various placeholders for display in scoreboards, tablists, and menus.
 */
public final class MFAutoRestartPlaceholderExpansion extends PlaceholderExpansion {

    private final MFAutoRestart plugin;
    private final DateTimeFormatter nextRestartFormatter = DateTimeFormatter.ofPattern("EEE HH:mm");

    public MFAutoRestartPlaceholderExpansion(final MFAutoRestart plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mfautorestart";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Keep registered across PlaceholderAPI reloads
    }

    @Override
    public @Nullable String onRequest(final OfflinePlayer player, final @NotNull String params) {
        switch (params.toLowerCase()) {
            case "active" -> {
                return String.valueOf(plugin.isRestartActive());
            }
            case "seconds_remaining" -> {
                return String.valueOf(plugin.getSecondsRemaining().orElse(0));
            }
            case "time_remaining" -> {
                final int totalSecs = plugin.getSecondsRemaining().orElse(0);
                final int mins = totalSecs / 60;
                final int secs = totalSecs % 60;
                return String.format("%02d:%02d", mins, secs);
            }
            case "time_remaining_formatted" -> {
                final int totalSecs = plugin.getSecondsRemaining().orElse(0);
                if (totalSecs <= 0) {
                    return "";
                }
                if (totalSecs >= 60) {
                    final int mins = totalSecs / 60;
                    final int secs = totalSecs % 60;
                    if (secs == 0) {
                        return mins + "m";
                    }
                    return mins + "m " + secs + "s";
                }
                return totalSecs + "s";
            }
            case "next_restart" -> {
                final ZonedDateTime next = plugin.getNextScheduledRestart();
                if (next == null) {
                    return "None";
                }
                // Return formatted time (e.g. "Sun 04:00") aligned with the plugin timezone
                return next.withZoneSameInstant(plugin.getConfigManager().getTimezone()).format(nextRestartFormatter);
            }
            case "next_remaining" -> {
                final ZonedDateTime next = plugin.getNextScheduledRestart();
                if (next == null) {
                    return "None";
                }
                final ZonedDateTime now = ZonedDateTime.now(plugin.getConfigManager().getTimezone());
                final Duration duration = Duration.between(now, next);
                final long totalSeconds = duration.getSeconds();
                if (totalSeconds <= 0) {
                    return "0s";
                }
                final long hours = totalSeconds / 3600;
                final long minutes = (totalSeconds % 3600) / 60;
                if (hours > 0) {
                    return hours + "h " + minutes + "m";
                }
                return minutes + "m";
            }
            default -> {
                return null;
            }
        }
    }
}
