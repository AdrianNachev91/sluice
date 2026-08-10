package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDirLocatorTest {

    @Test
    void windowsUsesAppData() {
        final Path dir = ConfigDirLocator.locate("Windows 11", Map.of("APPDATA", "C:\\Users\\pat\\AppData\\Roaming"));
        assertThat(dir).isEqualTo(Path.of("C:\\Users\\pat\\AppData\\Roaming\\Sluice"));
    }

    @Test
    void windowsFallsBackToUserProfileWhenAppDataMissing() {
        final Path dir = ConfigDirLocator.locate("Windows 10", Map.of("USERPROFILE", "C:\\Users\\pat"));
        assertThat(dir).isEqualTo(Path.of("C:\\Users\\pat\\AppData\\Roaming\\Sluice"));
    }

    @Test
    void macUsesApplicationSupport() {
        final Path dir = ConfigDirLocator.locate("Mac OS X", Map.of("HOME", "/Users/pat"));
        assertThat(dir).isEqualTo(Path.of("/Users/pat/Library/Application Support/Sluice"));
    }

    @Test
    void linuxUsesXdgConfigHomeWhenSet() {
        final Path dir = ConfigDirLocator.locate("Linux", Map.of("XDG_CONFIG_HOME", "/home/pat/.config", "HOME",
                "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }

    @Test
    void linuxFallsBackToDotConfigWhenXdgUnset() {
        final Path dir = ConfigDirLocator.locate("Linux", Map.of("HOME", "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }

    // Credentials sit beside the config file rather than inside it, and the difference is one
    // character in a path expression. A directory resolved against the file instead of the folder
    // would only fail at the first save, on a real machine.
    @Test
    void secretsGetTheirOwnDirectoryBesideTheConfigFile() {
        final Map<String, String> env = Map.of("HOME", "/home/pat");

        final Path secrets = ConfigDirLocator.secretsDir("Linux", env);

        assertThat(secrets).isEqualTo(Path.of("/home/pat/.config/sluice/secrets"));
        assertThat(secrets.getParent()).isEqualTo(ConfigDirLocator.configFile("Linux", env).getParent());
    }

    @Test
    void unknownOsFallsBackToDotConfigLikeLinux() {
        final Path dir = ConfigDirLocator.locate("SomeExoticOS", Map.of("HOME", "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }
}
