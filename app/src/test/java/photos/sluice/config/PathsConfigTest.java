package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathsConfigTest {

    @Test
    void relativePathsResolveAgainstWorkingDirectory() {
        final var config = new PathsConfig(new PathsProperties("relative-repo", "relative-lib", "relative-inbox"));

        final Path expectedBase = Path.of("").toAbsolutePath().normalize();
        assertThat(config.repoRoot()).isEqualTo(expectedBase.resolve("relative-repo"));
        assertThat(config.library()).isEqualTo(expectedBase.resolve("relative-lib"));
        assertThat(config.inbox()).isEqualTo(expectedBase.resolve("relative-inbox"));
    }

    @Test
    void absolutePathsResolveUnchanged() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.repoRoot()).isEqualTo(absolute);
    }

    @Test
    void logsIsDerivedFromRepoRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.logs()).isEqualTo(absolute.resolve("logs"));
    }

    @Test
    void sortedIsDerivedFromRepoRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.sorted()).isEqualTo(absolute.resolve("Sorted"));
    }

    @Test
    void reviewIsDerivedFromRepoRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = new PathsConfig(new PathsProperties(absolute.toString(), absolute.toString(), absolute.toString()));

        assertThat(config.review()).isEqualTo(absolute.resolve("Review"));
    }
}
