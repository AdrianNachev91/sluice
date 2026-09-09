package photos.sluice.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import photos.sluice.adapter.fs.YamlSettingsStore;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.domain.cull.CullScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;

// Every link of the save-to-watcher-retire chain is already proven on its own. What none of those
// can say is whether the beans the context builds join up, so that is the whole subject here.
@SpringBootTest
class FolderRootsSaveCompositionTest {

    @TempDir
    static Path workingRoot;

    @TempDir
    static Path newWorkingRoot;

    @Autowired
    private Pipeline pipeline;

    @Autowired
    private SettingsUseCase settings;

    @Autowired
    private WorkingRootLock workingRootLock;

    @DynamicPropertySource
    static void paths(final DynamicPropertyRegistry registry) {
        registry.add("sluice.paths.working-root", workingRoot::toString);
        registry.add("sluice.paths.library-root", () -> workingRoot.resolve("Library").toString());
        registry.add("sluice.paths.inbox", () -> workingRoot.resolve("Inbox").toString());
    }

    @BeforeAll
    static void folders() throws IOException {
        Files.createDirectories(workingRoot.resolve("Library"));
        Files.createDirectories(workingRoot.resolve("Inbox"));
        Files.createDirectories(newWorkingRoot.resolve("Library"));
        Files.createDirectories(newWorkingRoot.resolve("Inbox"));
    }

    // The save claims the root it moves to, through the real lock. That claim lives in process-wide
    // state every other test shares, and holds an open marker file inside a temporary directory
    // JUnit is about to delete.
    @AfterEach
    void giveTheClaimBack() {
        this.workingRootLock.releaseAll();
    }

    @Test
    void savingTheWorkingRootElsewhereRetiresTheWatchersItStranded() throws IOException {
        writePhoto(sortedPhotosDir(workingRoot, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var waiting = (CullJobOutcome.Waiting) this.pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(this.pipeline.isWatchActive(prepDir)).isTrue();

        this.settings.save(this.movedTo(newWorkingRoot));

        assertThat(this.pipeline.isWatchActive(prepDir)).isFalse();
    }

    // Everything but the working root and the inbox is carried over from the settings in force, so
    // the save under test moves roots and changes nothing else. The library root has its own seam.
    private Settings movedTo(final Path root) {
        final Settings current = this.settings.settings();
        return new Settings(new PathSettings(root.toString(), current.paths().libraryRoot(),
                root.resolve("Inbox").toString()),
                current.provider(), current.providerSettingsById(), current.categories(),
                current.montage(), current.theme());
    }

    // The one bean this test redirects. Production reads the config file out of an OS-native
    // app-data folder, which a test must not write into.
    @TestConfiguration
    static class TempConfigFile {

        @Bean
        @Primary
        SettingsStore temporarySettingsStore() {
            return new YamlSettingsStore(newWorkingRoot.resolve("config.yml"));
        }
    }
}
