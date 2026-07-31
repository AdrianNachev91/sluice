package photos.sluice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest
class SmokeTest {

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
}
