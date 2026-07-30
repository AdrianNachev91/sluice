package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathsConfigValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void blankRepoRootFailsWithActionableMessage(@TempDir final Path libraryRoot, @TempDir final Path inbox) {
        runner.withPropertyValues(
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sluice.paths.repo-root")
                    .hasMessageContaining("not configured");
        });
    }

    @Test
    void nonExistentDirectoryFailsWithDistinctMessage(
            @TempDir final Path repoParent, @TempDir final Path libraryRoot, @TempDir final Path inbox) {
        final Path missingRepoRoot = repoParent.resolve("does-not-exist");
        runner.withPropertyValues(
                "sluice.paths.repo-root=" + missingRepoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sluice.paths.repo-root")
                    .hasMessageContaining("does not exist");
        });
    }

    @Test
    void allExistingDirectoriesStartCleanly(
            @TempDir final Path repoRoot, @TempDir final Path libraryRoot, @TempDir final Path inbox) {
        runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties(PathsProperties.class)
    @Import(PathsConfig.class)
    static class TestConfig {
    }
}
