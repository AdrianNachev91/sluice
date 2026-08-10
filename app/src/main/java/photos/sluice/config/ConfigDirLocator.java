package photos.sluice.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Computes the OS-native directory Sluice's user config file lives in, given the raw OS name and
 * the environment variables to resolve paths from.
 *
 * <p>Windows resolves under {@code %APPDATA%}, macOS under {@code ~/Library/Application Support},
 * and other platforms follow the XDG base directory convention.
 *
 * <p>A map rather than a lookup, unlike the credential tier that reads an override. Every name read
 * here is one the OS itself sets and spells consistently, so nothing is lost by matching it exactly.
 * A user-typed name would be the case where that stops being true.
 */
public final class ConfigDirLocator {

    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final String SECRETS_DIR_NAME = "secrets";

    /**
     * Prevents instantiation of this static utility class.
     */
    private ConfigDirLocator() {
    }

    /**
     * Resolves the user's config file for the given OS name and environment. The file need not
     * exist; an install that has never saved anything has none.
     *
     * <p>Both the code that loads settings and the code that writes them back come through here, so
     * neither can name a file the other does not.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @param env a {@link Map} of {@link String} to {@link String} environment variables to resolve paths from
     * @return {@link Path} the resolved config file path
     */
    public static Path configFile(final String osName, final Map<String, String> env) {
        return locate(osName, env).resolve(CONFIG_FILE_NAME);
    }

    /**
     * Resolves the directory stored credentials live in, beside the config file rather than inside
     * it. A config file is meant to be readable and shareable, and a credential is neither.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @param env a {@link Map} of {@link String} to {@link String} environment variables to resolve paths from
     * @return {@link Path} the resolved credential directory path
     */
    public static Path secretsDir(final String osName, final Map<String, String> env) {
        return locate(osName, env).resolve(SECRETS_DIR_NAME);
    }

    /**
     * Resolves the OS-native config directory for the given OS name and environment.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @param env a {@link Map} of {@link String} to {@link String} environment variables to resolve paths from
     * @return {@link Path} the resolved config directory path
     */
    public static Path locate(final String osName, final Map<String, String> env) {
        final String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            final String appData = env.get("APPDATA");
            if (appData != null) {
                return Path.of(appData + "\\Sluice");
            }
            return Path.of(env.get("USERPROFILE") + "\\AppData\\Roaming\\Sluice");
        }
        if (os.contains("mac")) {
            return Path.of(env.get("HOME") + "/Library/Application Support/Sluice");
        }
        final String xdgConfigHome = env.get("XDG_CONFIG_HOME");
        if (xdgConfigHome != null) {
            return Path.of(xdgConfigHome + "/sluice");
        }
        return Path.of(env.get("HOME") + "/.config/sluice");
    }
}
