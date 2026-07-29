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
 */
public final class ConfigDirLocator {

    /**
     * Prevents instantiation of this static utility class.
     */
    private ConfigDirLocator() {
    }

    /**
     * Resolves the OS-native config directory for the given OS name and environment.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @param env a {@link Map} of {@link String} to {@link String} environment variables to resolve paths from
     * @return {@link Path} the resolved config directory path
     */
    public static Path locate(String osName, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            if (appData != null) {
                return Path.of(appData + "\\Sluice");
            }
            return Path.of(env.get("USERPROFILE") + "\\AppData\\Roaming\\Sluice");
        }
        if (os.contains("mac")) {
            return Path.of(env.get("HOME") + "/Library/Application Support/Sluice");
        }
        String xdgConfigHome = env.get("XDG_CONFIG_HOME");
        if (xdgConfigHome != null) {
            return Path.of(xdgConfigHome + "/sluice");
        }
        return Path.of(env.get("HOME") + "/.config/sluice");
    }
}
