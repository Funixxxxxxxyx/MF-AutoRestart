package id.funix.mFAutoRestart.command;

import id.funix.mFAutoRestart.MFAutoRestart;
import id.funix.mFAutoRestart.config.ConfigManager;
import id.funix.mFAutoRestart.lang.LanguageManager;
import id.funix.mFAutoRestart.manager.RestartManager;
import id.funix.mFAutoRestart.manager.ScheduleManager;
import id.funix.mFAutoRestart.model.RestartSchedule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Handles all subcommands for the {@code /autorestart} (alias: {@code /ar}) command.
 *
 * <h3>Subcommands</h3>
 * <ul>
 *   <li>{@code /ar status} — Show countdown state and next scheduled restart.</li>
 *   <li>{@code /ar start [seconds]} — Manually initiate a restart countdown.</li>
 *   <li>{@code /ar cancel} — Cancel an active countdown.</li>
 *   <li>{@code /ar schedule add daily <HH:mm>} — Add a daily dynamic schedule.</li>
 *   <li>{@code /ar schedule add weekly <DAY> <HH:mm>} — Add a weekly dynamic schedule.</li>
 *   <li>{@code /ar schedule list} — List all active schedules.</li>
 *   <li>{@code /ar reload} — Reload config and language files.</li>
 * </ul>
 *
 * <p>All commands require the {@code mfautorestart.admin} permission node.</p>
 *
 * <p><b>Threading:</b> All execution is {@code @MainThread} as invoked by the Bukkit command
 * dispatcher. Database-backed operations (e.g., adding schedules) dispatch async internally
 * via {@link ScheduleManager} and {@link id.funix.mFAutoRestart.database.DatabaseManager}.</p>
 */
public final class AutoRestartCommand implements CommandExecutor, TabCompleter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String PERMISSION = "mfautorestart.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm z");

    private static final List<String> DAYS = Arrays.asList(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;
    private final ConfigManager configManager;
    private final ScheduleManager scheduleManager;
    private final RestartManager restartManager;
    private final LanguageManager languageManager;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param plugin          Plugin instance.
     * @param configManager   Config accessor.
     * @param scheduleManager Schedule manager.
     * @param restartManager  Restart engine.
     * @param languageManager Language message resolver.
     */
    public AutoRestartCommand(
            final MFAutoRestart plugin,
            final ConfigManager configManager,
            final ScheduleManager scheduleManager,
            final RestartManager restartManager,
            final LanguageManager languageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduleManager = scheduleManager;
        this.restartManager = restartManager;
        this.languageManager = languageManager;
    }

    // -----------------------------------------------------------------------
    // CommandExecutor
    // -----------------------------------------------------------------------

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {

        // Permission gate for all subcommands
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(languageManager.get("no_permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status"   -> handleStatus(sender);
            case "start"    -> handleStart(sender, args);
            case "cancel"   -> handleCancel(sender);
            case "schedule" -> handleSchedule(sender, args);
            case "reload"   -> handleReload(sender);
            default         -> sender.sendMessage(languageManager.get("unknown_subcommand"));
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Subcommand Handlers
    // -----------------------------------------------------------------------

    /**
     * Displays current countdown state, timezone, and next scheduled restart.
     *
     * @param sender The command sender.
     * @MainThread
     */
    private void handleStatus(final CommandSender sender) {
        sender.sendMessage(languageManager.get("status_header"));

        // Timezone
        sender.sendMessage(languageManager.get("status_timezone",
                Placeholder.unparsed("timezone", configManager.getTimezone().getId())));

        // Countdown state
        if (restartManager.isCountdownActive()) {
            sender.sendMessage(languageManager.get("status_countdown_active",
                    Placeholder.unparsed("seconds", String.valueOf(restartManager.getSecondsRemaining()))));
        } else {
            sender.sendMessage(languageManager.get("status_countdown_none"));
        }

        // Next scheduled restart
        final Optional<ZonedDateTime> next = scheduleManager.getNextScheduledTime();
        if (next.isPresent()) {
            final ZonedDateTime nextTime = next.get().withZoneSameInstant(configManager.getTimezone());
            sender.sendMessage(languageManager.get("status_next_restart",
                    Placeholder.unparsed("time", nextTime.format(TIME_FORMATTER)),
                    Placeholder.unparsed("timezone", configManager.getTimezone().getId())));
        } else {
            sender.sendMessage(languageManager.get("status_no_schedule"));
        }

        // Schedule list
        final List<RestartSchedule> schedules = scheduleManager.getAllSchedules();
        if (!schedules.isEmpty()) {
            sender.sendMessage(languageManager.get("status_schedules_header"));
            for (final RestartSchedule schedule : schedules) {
                sender.sendMessage(languageManager.get("status_schedule_entry",
                        Placeholder.unparsed("type", schedule.getType().name()),
                        Placeholder.unparsed("value", schedule.toDisplayString())));
            }
        }
    }

    /**
     * Initiates a manual restart countdown.
     * Usage: {@code /ar start [seconds]}
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @MainThread
     */
    private void handleStart(final CommandSender sender, final String[] args) {
        if (restartManager.isCountdownActive()) {
            sender.sendMessage(languageManager.get("restart_already_active"));
            return;
        }

        int seconds = configManager.getDefaultCountdown();
        if (args.length >= 2) {
            try {
                seconds = parseDuration(args[1]);
            } catch (final NumberFormatException e) {
                sender.sendMessage(languageManager.get("invalid_number",
                        Placeholder.unparsed("input", args[1])));
                return;
            }
        }

        restartManager.startCountdown(seconds, false);
        sender.sendMessage(languageManager.get("restart_starting",
                Placeholder.unparsed("seconds", String.valueOf(seconds))));
    }

    /**
     * Cancels an active restart countdown.
     *
     * @param sender The command sender.
     * @MainThread
     */
    private void handleCancel(final CommandSender sender) {
        if (restartManager.cancelRestart(sender.getName())) {
            // Broadcast cancellation to all players
            final Component cancelMsg = languageManager.get("restart_cancelled");
            plugin.getServer().broadcast(cancelMsg);
        } else {
            sender.sendMessage(languageManager.get("restart_not_active"));
        }
    }

    /**
     * Handles schedule subcommands: {@code add daily}, {@code add weekly}, {@code list}.
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @MainThread
     */
    private void handleSchedule(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sendScheduleHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add"  -> handleScheduleAdd(sender, args);
            case "list" -> handleScheduleList(sender);
            default     -> sendScheduleHelp(sender);
        }
    }

    /**
     * Handles {@code /ar schedule add <daily|weekly> [day] <HH:mm>}.
     *
     * @param sender The command sender.
     * @param args   Full argument array.
     * @MainThread
     */
    private void handleScheduleAdd(final CommandSender sender, final String[] args) {
        // Minimum: /ar schedule add <type> ...
        if (args.length < 4) {
            sender.sendMessage(languageManager.get("schedule_add_invalid_type"));
            return;
        }

        final String typeStr = args[2].toLowerCase();

        if ("daily".equals(typeStr)) {
            // /ar schedule add daily <HH:mm>
            final String timeStr = args[3];
            final int[] parsed = parseTime(timeStr);
            if (parsed == null) {
                sender.sendMessage(languageManager.get("schedule_add_invalid_time"));
                return;
            }

            final RestartSchedule schedule = new RestartSchedule(
                    RestartSchedule.ScheduleType.DAILY, null,
                    parsed[0], parsed[1], RestartSchedule.Origin.DATABASE, -1);

            scheduleManager.addDynamicSchedule(schedule, persisted ->
                    sender.sendMessage(languageManager.get("schedule_add_success",
                            Placeholder.unparsed("type", "DAILY"),
                            Placeholder.unparsed("time", timeStr))));

        } else if ("weekly".equals(typeStr)) {
            // /ar schedule add weekly <DAY> <HH:mm>
            if (args.length < 5) {
                sender.sendMessage(languageManager.get("schedule_add_missing_day"));
                return;
            }

            final DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(args[3].toUpperCase());
            } catch (final IllegalArgumentException e) {
                sender.sendMessage(languageManager.get("schedule_add_invalid_day"));
                return;
            }

            final String timeStr = args[4];
            final int[] parsed = parseTime(timeStr);
            if (parsed == null) {
                sender.sendMessage(languageManager.get("schedule_add_invalid_time"));
                return;
            }

            final RestartSchedule schedule = new RestartSchedule(
                    RestartSchedule.ScheduleType.WEEKLY, day,
                    parsed[0], parsed[1], RestartSchedule.Origin.DATABASE, -1);

            scheduleManager.addDynamicSchedule(schedule, persisted ->
                    sender.sendMessage(languageManager.get("schedule_add_success",
                            Placeholder.unparsed("type", "WEEKLY " + day.name()),
                            Placeholder.unparsed("time", timeStr))));
        } else {
            sender.sendMessage(languageManager.get("schedule_add_invalid_type"));
        }
    }

    /**
     * Lists all active schedules (config + dynamic).
     *
     * @param sender The command sender.
     * @MainThread
     */
    private void handleScheduleList(final CommandSender sender) {
        final List<RestartSchedule> schedules = scheduleManager.getAllSchedules();
        sender.sendMessage(languageManager.get("schedule_list_header"));

        if (schedules.isEmpty()) {
            sender.sendMessage(languageManager.get("schedule_list_empty"));
            return;
        }

        int index = 1;
        for (final RestartSchedule schedule : schedules) {
            sender.sendMessage(languageManager.get("schedule_list_entry",
                    Placeholder.unparsed("index", String.valueOf(index++)),
                    Placeholder.unparsed("type", schedule.getType().name()),
                    Placeholder.unparsed("value", schedule.toDisplayString()),
                    Placeholder.unparsed("origin", schedule.getOrigin().name())));
        }
    }

    /**
     * Reloads plugin configuration and language files.
     *
     * @param sender The command sender.
     * @MainThread
     */
    private void handleReload(final CommandSender sender) {
        configManager.reload();
        languageManager.reload();
        scheduleManager.clearAndReloadDynamic();
        sender.sendMessage(languageManager.get("reload_success"));
    }

    // -----------------------------------------------------------------------
    // Help Messages (hardcoded as these are structural, not player-facing content)
    // -----------------------------------------------------------------------

    /**
     * Sends the main help page to the command sender.
     *
     * @param sender The command sender.
     * @param label  The command label used.
     */
    private void sendHelp(final CommandSender sender, final String label) {
        sender.sendMessage(MM.deserialize(
                "<dark_gray>--- <yellow><bold>MF-AutoRestart</bold></yellow> <dark_gray>---"));
        sender.sendMessage(MM.deserialize("<gray>/<label> status <dark_gray>— <white>Show server restart status"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> start [seconds] <dark_gray>— <white>Start a manual countdown"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> cancel <dark_gray>— <white>Cancel active countdown"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> schedule add daily <HH:mm> <dark_gray>— <white>Add a daily schedule"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> schedule add weekly <DAY> <HH:mm> <dark_gray>— <white>Add a weekly schedule"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> schedule list <dark_gray>— <white>List all schedules"
                .replace("<label>", label)));
        sender.sendMessage(MM.deserialize("<gray>/<label> reload <dark_gray>— <white>Reload config and languages"
                .replace("<label>", label)));
    }

    /** Sends a brief schedule help hint. */
    private void sendScheduleHelp(final CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>Usage: <gray>/ar schedule <add|list>"));
    }

    // -----------------------------------------------------------------------
    // TabCompleter
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {

        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterStartsWith(args[0],
                    "status", "start", "cancel", "schedule", "reload");
        }

        if (args.length == 2 && "schedule".equalsIgnoreCase(args[0])) {
            return filterStartsWith(args[1], "add", "list");
        }

        if (args.length == 3 && "schedule".equalsIgnoreCase(args[0])
                && "add".equalsIgnoreCase(args[1])) {
            return filterStartsWith(args[2], "daily", "weekly");
        }

        if (args.length == 4 && "schedule".equalsIgnoreCase(args[0])
                && "add".equalsIgnoreCase(args[1])) {
            if ("weekly".equalsIgnoreCase(args[2])) {
                return filterStartsWith(args[3], DAYS.toArray(new String[0]));
            }
            // Daily: suggest time
            return filterStartsWith(args[3], "00:00", "04:00", "08:00", "12:00", "16:00", "20:00");
        }

        if (args.length == 5 && "schedule".equalsIgnoreCase(args[0])
                && "add".equalsIgnoreCase(args[1])
                && "weekly".equalsIgnoreCase(args[2])) {
            return filterStartsWith(args[4], "00:00", "04:00", "08:00", "12:00", "16:00", "20:00");
        }



        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /**
     * Parses a time string in {@code HH:mm} format.
     *
     * @param timeStr The time string to parse.
     * @return An {@code int[]{hour, minute}} or {@code null} if invalid.
     */
    private int[] parseTime(final String timeStr) {
        try {
            final String[] parts = timeStr.split(":");
            if (parts.length != 2) {
                return null;
            }
            final int hour = Integer.parseInt(parts[0]);
            final int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return new int[]{hour, minute};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a duration string with unit suffixes (e.g. "80", "80s", "1m20s", "2m").
     *
     * @param input Raw duration input.
     * @return Duration in seconds.
     * @throws NumberFormatException if the format is invalid or <= 0.
     */
    private int parseDuration(final String input) throws NumberFormatException {
        final String cleaned = input.trim().toLowerCase();
        if (cleaned.matches("^\\d+$")) {
            final int secs = Integer.parseInt(cleaned);
            if (secs <= 0) {
                throw new NumberFormatException();
            }
            return secs;
        }

        long totalSeconds = 0;
        final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)([smh])");
        final java.util.regex.Matcher matcher = pattern.matcher(cleaned);
        boolean matched = false;
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() != lastIndex) {
                throw new NumberFormatException();
            }
            matched = true;
            final int val = Integer.parseInt(matcher.group(1));
            final String unit = matcher.group(2);
            switch (unit) {
                case "s" -> totalSeconds += val;
                case "m" -> totalSeconds += val * 60L;
                case "h" -> totalSeconds += val * 3600L;
            }
            lastIndex = matcher.end();
        }

        if (!matched || lastIndex != cleaned.length() || totalSeconds <= 0 || totalSeconds > Integer.MAX_VALUE) {
            throw new NumberFormatException();
        }

        return (int) totalSeconds;
    }

    /**
     * Filters a list of candidates to those starting with the provided prefix (case-insensitive).
     *
     * @param input      The current input string.
     * @param candidates Candidate strings.
     * @return Filtered list for tab completion.
     */
    private List<String> filterStartsWith(final String input, final String... candidates) {
        final List<String> result = new ArrayList<>();
        for (final String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(input.toLowerCase())) {
                result.add(candidate);
            }
        }
        return result;
    }
}
