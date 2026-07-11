package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDirLocatorTest {

    @Test
    void windowsUsesAppData() {
        Path dir = ConfigDirLocator.locate("Windows 11", Map.of("APPDATA", "C:\\Users\\pat\\AppData\\Roaming"));
        assertThat(dir).isEqualTo(Path.of("C:\\Users\\pat\\AppData\\Roaming\\Sluice"));
    }

    @Test
    void windowsFallsBackToUserProfileWhenAppDataMissing() {
        Path dir = ConfigDirLocator.locate("Windows 10", Map.of("USERPROFILE", "C:\\Users\\pat"));
        assertThat(dir).isEqualTo(Path.of("C:\\Users\\pat\\AppData\\Roaming\\Sluice"));
    }

    @Test
    void macUsesApplicationSupport() {
        Path dir = ConfigDirLocator.locate("Mac OS X", Map.of("HOME", "/Users/pat"));
        assertThat(dir).isEqualTo(Path.of("/Users/pat/Library/Application Support/Sluice"));
    }

    @Test
    void linuxUsesXdgConfigHomeWhenSet() {
        Path dir = ConfigDirLocator.locate("Linux", Map.of("XDG_CONFIG_HOME", "/home/pat/.config", "HOME", "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }

    @Test
    void linuxFallsBackToDotConfigWhenXdgUnset() {
        Path dir = ConfigDirLocator.locate("Linux", Map.of("HOME", "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }

    @Test
    void unknownOsFallsBackToDotConfigLikeLinux() {
        Path dir = ConfigDirLocator.locate("SomeExoticOS", Map.of("HOME", "/home/pat"));
        assertThat(dir).isEqualTo(Path.of("/home/pat/.config/sluice"));
    }
}
