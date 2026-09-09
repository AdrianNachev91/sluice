package photos.sluice.config;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.PathSettings;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathsConfigTest {

    @Test
    void relativePathsResolveAgainstWorkingDirectory() {
        final var config = SettingsFixture.pathsConfig("relative-repo", "relative-lib", "relative-inbox");

        final Path expectedBase = Path.of("").toAbsolutePath().normalize();
        assertThat(config.workingRoot()).isEqualTo(expectedBase.resolve("relative-repo"));
        assertThat(config.library()).isEqualTo(expectedBase.resolve("relative-lib"));
        assertThat(config.inbox()).isEqualTo(expectedBase.resolve("relative-inbox"));
    }

    @Test
    void absolutePathsResolveUnchanged() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = SettingsFixture.pathsConfig(absolute, absolute, absolute);

        assertThat(config.workingRoot()).isEqualTo(absolute);
    }

    @Test
    void logsIsDerivedFromWorkingRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = SettingsFixture.pathsConfig(absolute, absolute, absolute);

        assertThat(config.logs()).isEqualTo(absolute.resolve("logs"));
    }

    @Test
    void sortedIsDerivedFromWorkingRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = SettingsFixture.pathsConfig(absolute, absolute, absolute);

        assertThat(config.sorted()).isEqualTo(absolute.resolve("Sorted"));
    }

    @Test
    void reviewIsDerivedFromWorkingRoot() {
        final Path absolute = Path.of("").toAbsolutePath().normalize();
        final var config = SettingsFixture.pathsConfig(absolute, absolute, absolute);

        assertThat(config.review()).isEqualTo(absolute.resolve("Review"));
    }

    @Test
    void everyPathFollowsASavedWorkingRootWithNothingRestarted() {
        final Path before = Path.of("before").toAbsolutePath().normalize();
        final Path after = Path.of("after").toAbsolutePath().normalize();
        final var holder = new SettingsHolder(SettingsFixture.settings(
                new PathSettings(before.toString(), before.toString(), before.resolve("Inbox").toString())));
        final var config = new PathsConfig(holder);

        holder.apply(SettingsFixture.settings(
                new PathSettings(after.toString(), after.toString(), after.resolve("Inbox").toString())));

        assertThat(config.workingRoot()).isEqualTo(after);
        assertThat(config.logs()).isEqualTo(after.resolve("logs"));
        assertThat(config.sorted()).isEqualTo(after.resolve("Sorted"));
        assertThat(config.inbox()).isEqualTo(after.resolve("Inbox"));
    }
}
