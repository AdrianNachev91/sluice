package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathsConfigTest {

    @Test
    void relativePathsResolveAgainstWorkingDirectory() {
        PathsConfig config = new PathsConfig(new PathsProperties("relative-repo", "relative-lib", "relative-inbox"));

        Path expectedBase = Path.of("").toAbsolutePath().normalize();
        assertThat(config.repoRoot()).isEqualTo(expectedBase.resolve("relative-repo"));
        assertThat(config.libraryRoot()).isEqualTo(expectedBase.resolve("relative-lib"));
        assertThat(config.inbox()).isEqualTo(expectedBase.resolve("relative-inbox"));
    }

    @Test
    void absolutePathsResolveUnchanged() {
        Path absolute = Path.of("").toAbsolutePath().normalize();
        PathsConfig config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.repoRoot()).isEqualTo(absolute);
    }

    @Test
    void logsIsDerivedFromRepoRoot() {
        Path absolute = Path.of("").toAbsolutePath().normalize();
        PathsConfig config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.logs()).isEqualTo(absolute.resolve("logs"));
    }
}
