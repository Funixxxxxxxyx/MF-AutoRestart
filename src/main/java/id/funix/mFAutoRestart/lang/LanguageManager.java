package id.funix.mFAutoRestart.lang;

import id.funix.mFAutoRestart.MFAutoRestart;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Manages localized player-facing messages for MF-AutoRestart.
 *
 * <p>Loads the configured language file from {@code lang/<locale>.yml} inside the plugin's
 * data folder on disk. Falls back to the bundled resource if the file does not exist or if
 * a key is missing from the on-disk copy.</p>
 *
 * <p>All messages use <a href="https://docs.advntr.dev/minimessage/">MiniMessage</a> format.
 * The {@code <prefix>} tag in any message string will automatically be resolved to the
 * localized prefix defined under the key {@code prefix}.</p>
 *
 * <p><b>Threading:</b> All public methods are safe to call from {@code @AnyThread} because they
 * only perform read-only lookups into an immutable {@link YamlConfiguration} snapshot. The
 * {@link #reload()} method must only be called from the main thread or with external synchronization.</p>
 */
public final class LanguageManager {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** The MiniMessage singleton used for all deserialization. */
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final MFAutoRestart plugin;

    /** Loaded language configuration (on-disk file). */
    private YamlConfiguration langConfig;

    /** Bundled fallback configuration (from JAR resources). */
    private YamlConfiguration fallbackConfig;

    /** Resolved MiniMessage prefix component string (e.g., "<bold><yellow>MF-AutoRestart</yellow></bold> ..."). */
    private String prefixRaw;

    // -----------------------------------------------------------------------
    // Constructor & Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Creates and immediately loads the configured language file.
     *
     * @param plugin The plugin instance.
     */
    public LanguageManager(final MFAutoRestart plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Reloads the language file from disk and the bundled fallback.
     * Call this from the main thread only (e.g., on config reload).
     *
     * @MainThread
     */
    public void reload() {
        final String locale = plugin.getConfig().getString("settings.language", "en_us");
        final String fileName = locale + ".yml";

        // --- Load bundled (JAR) fallback first ---
        final InputStream fallbackStream = plugin.getResource("lang/" + fileName);
        if (fallbackStream == null) {
            plugin.getLogger().warning("[LanguageManager] Bundled language file 'lang/" + fileName + "' not found. "
                    + "Falling back to en_us.yml.");
            // Try en_us as a last resort
            final InputStream usStream = plugin.getResource("lang/en_us.yml");
            fallbackConfig = usStream != null
                    ? YamlConfiguration.loadConfiguration(new InputStreamReader(usStream, StandardCharsets.UTF_8))
                    : new YamlConfiguration();
        } else {
            fallbackConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(fallbackStream, StandardCharsets.UTF_8));
        }

        // --- Save the default lang files to disk if missing ---
        final File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        saveLangResource("lang/en_us.yml");
        saveLangResource("lang/en_id.yml");

        final File langFile = new File(langFolder, fileName);

        // --- Load on-disk copy ---
        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        } else {
            langConfig = new YamlConfiguration();
        }

        // --- Copy any missing keys from fallback to disk copy (soft merge) ---
        boolean dirty = false;
        for (final String key : fallbackConfig.getKeys(true)) {
            if (!langConfig.contains(key)) {
                langConfig.set(key, fallbackConfig.get(key));
                dirty = true;
            }
        }
        if (dirty && langFile.exists()) {
            try {
                langConfig.save(langFile);
            } catch (final IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[LanguageManager] Failed to save merged lang file: " + langFile.getName(), e);
            }
        }

        // --- Cache the raw prefix string ---
        prefixRaw = getString("prefix");

        plugin.getLogger().info("[LanguageManager] Loaded language: " + locale);
    }

    // -----------------------------------------------------------------------
    // Core Resolution
    // -----------------------------------------------------------------------

    /**
     * Retrieves the raw (un-deserialized) message string for the given key.
     * Falls back to the bundled JAR config if the key is absent on disk.
     *
     * @param key The message key (e.g., {@code "no_permission"}).
     * @return The raw MiniMessage string, or a red error indicator if not found.
     * @AnyThread — reads from immutable snapshot.
     */
    public String getString(final String key) {
        String value = langConfig.getString(key);
        if (value == null) {
            value = fallbackConfig.getString(key);
        }
        if (value == null) {
            plugin.getLogger().warning("[LanguageManager] Missing language key: '" + key + "'");
            return "<red>[MISSING: " + key + "]</red>";
        }
        return value;
    }

    /**
     * Resolves a message key and returns the deserialized {@link Component}.
     *
     * <p>The special tag {@code <prefix>} is automatically replaced with the plugin prefix
     * defined under the {@code prefix} key in the language file.</p>
     *
     * @param key       The message key.
     * @param resolvers Optional additional {@link TagResolver}s for dynamic placeholders
     *                  (e.g., {@code Placeholder.unparsed("player", name)}).
     * @return The deserialized {@link Component}.
     * @AnyThread
     */
    public Component get(final String key, final TagResolver... resolvers) {
        final String raw = getString(key);

        // Build the resolver chain: prefix tag + caller-supplied resolvers
        final TagResolver prefixResolver = Placeholder.component("prefix", MM.deserialize(prefixRaw));
        final TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));

        return MM.deserialize(raw, combined);
    }

    /**
     * Convenience overload — resolves with only the built-in prefix resolver.
     *
     * @param key The message key.
     * @return The deserialized {@link Component}.
     * @AnyThread
     */
    public Component get(final String key) {
        return get(key, new TagResolver[0]);
    }

    /**
     * Returns the raw MiniMessage prefix string (e.g., for embedding in title strings).
     *
     * @return Raw prefix string.
     * @AnyThread
     */
    public String getPrefixRaw() {
        return prefixRaw;
    }

    /**
     * Unpacks a language resource from the JAR to the plugin folder if it does not already exist.
     *
     * @param path The resource path.
     */
    private void saveLangResource(final String path) {
        final File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            try {
                plugin.saveResource(path, false);
            } catch (final IllegalArgumentException e) {
                plugin.getLogger().warning("[LanguageManager] Could not save default resource: " + path);
            }
        }
    }
}
