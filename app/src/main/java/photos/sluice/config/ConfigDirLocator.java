package photos.sluice.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class ConfigDirLocator {

    private ConfigDirLocator() {
    }

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
