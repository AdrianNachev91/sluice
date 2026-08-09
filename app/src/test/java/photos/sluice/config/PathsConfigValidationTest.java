package photos.sluice.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathsConfigValidationTest {

    // The bundled application.yml is loaded because PathsConfig now reads through SettingsHolder,
    // which needs the cull and montage defaults to build a settings value at all.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void blankRepoRootFailsWithActionableMessage(@TempDir final Path libraryRoot, @TempDir final Path inbox) {
        this.runner.withPropertyValues(
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
        this.runner.withPropertyValues(
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
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void inboxNestedUnderRepoRootStartsCleanly(@TempDir final Path repoRoot, @TempDir final Path libraryRoot)
            throws IOException {
        final Path inbox = Files.createDirectory(repoRoot.resolve("Inbox"));
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void libraryNestedUnderInboxFailsWithActionableMessage(@TempDir final Path repoRoot, @TempDir final Path inbox)
            throws IOException {
        final Path libraryRoot = Files.createDirectory(inbox.resolve("library"));
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sluice.paths.library-root")
                    .hasMessageContaining("sluice.paths.inbox")
                    .hasMessageContaining("must not contain each other");
        });
    }

    @Test
    void inboxNestedUnderLibraryFailsWithActionableMessage(
            @TempDir final Path repoRoot, @TempDir final Path libraryRoot) throws IOException {
        final Path inbox = Files.createDirectory(libraryRoot.resolve("inbox"));
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not contain each other");
        });
    }

    @Test
    void libraryAndInboxAsTheSameDirectoryFailsWithActionableMessage(
            @TempDir final Path repoRoot, @TempDir final Path shared) {
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + shared,
                "sluice.paths.inbox=" + shared
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not contain each other");
        });
    }

    @Test
    void inboxAsTheRepoRootFailsWithActionableMessage(@TempDir final Path shared, @TempDir final Path libraryRoot) {
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + shared,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + shared
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sluice.paths.inbox")
                    .hasMessageContaining("sluice.paths.repo-root")
                    .hasMessageContaining("must not be, or contain");
        });
    }

    @Test
    void inboxAsAnAncestorOfTheRepoRootFailsWithActionableMessage(
            @TempDir final Path inbox, @TempDir final Path libraryRoot) throws IOException {
        final Path repoRoot = Files.createDirectory(inbox.resolve("repo"));
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + libraryRoot,
                "sluice.paths.inbox=" + inbox
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not be, or contain");
        });
    }

    @Test
    void sameRealDirectoryUnderTwoNamesFailsEvenWithDistinctConfiguredPaths(
            @TempDir final Path repoRoot, @TempDir final Path realDir) {
        final Path aliasDir = realDir.getParent().resolve("inbox-alias");
        try {
            Files.createSymbolicLink(aliasDir, realDir);
        } catch (final IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not supported in this environment: " + e.getMessage());
        }
        this.runner.withPropertyValues(
                "sluice.paths.repo-root=" + repoRoot,
                "sluice.paths.library-root=" + realDir,
                "sluice.paths.inbox=" + aliasDir
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not contain each other");
        });
    }

    @Configuration
    @EnableConfigurationProperties({PathsProperties.class, CullConfig.class, MontageProperties.class})
    @Import({SettingsHolder.class, PathsConfig.class})
    static class TestConfig {
    }
}
