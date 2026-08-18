package photos.sluice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import photos.sluice.adapter.ui.FolderRootsHousekeeping;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.FolderRootsChangeListener;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SmokeTest {

    @Autowired
    private ApplicationContext context;

    @TempDir
    static Path repoRoot;

    @TempDir
    static Path libraryRoot;

    @TempDir
    static Path inbox;

    @DynamicPropertySource
    static void paths(final DynamicPropertyRegistry registry) {
        registry.add("sluice.paths.repo-root", repoRoot::toString);
        registry.add("sluice.paths.library-root", libraryRoot::toString);
        registry.add("sluice.paths.inbox", inbox::toString);
    }

    @Test
    void contextLoads() {
    }

    // The service's own tests hand it listeners directly, so a listener nobody registered would
    // pass every one of them.
    @Test
    void theDesktopRegistersItsFolderRootsHousekeeping() {
        assertThat(this.context.getBeanNamesForType(FolderRootsChangeListener.class)).hasSize(1);
        assertThat(this.context.getBeansOfType(FolderRootsChangeListener.class).values())
                .hasOnlyElementsOfType(FolderRootsHousekeeping.class);
    }

    // The catalog's own tests hand it cullers directly, so wiring that collected nothing would pass
    // all of them. An empty catalog draws an empty dropdown rather than failing.
    @Test
    void everyRegisteredCullerReachesTheProviderCatalog() {
        final List<String> cullerIds = this.context.getBeansOfType(VisionCuller.class).values().stream()
                .map(culler -> culler.describe().id())
                .toList();
        // Both sides being empty would satisfy the comparison below without proving anything.
        assertThat(cullerIds).isNotEmpty();

        assertThat(this.context.getBean(VisionProviderCatalog.class).providers())
                .extracting(VisionProviderDescriptor::id)
                .containsExactlyInAnyOrderElementsOf(cullerIds);
    }
}
