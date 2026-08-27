package photos.sluice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import photos.sluice.adapter.cli.ConsoleProgressPort;
import photos.sluice.adapter.cli.SluiceCli;
import photos.sluice.adapter.ui.StartupSequence;
import photos.sluice.adapter.ui.FolderRootsHousekeeping;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.FolderRootsChangeListener;
import photos.sluice.application.service.Pipeline;
import picocli.CommandLine.IFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// The context a command line builds. Every other context test runs the desktop's profile. The
// gating rule is that adapter/ui's beans are absent here. The trap is a bean elsewhere that one of
// them was the only supplier of.
@SpringBootTest
@ActiveProfiles("cli")
class CliProfileStartupTest {

    @TempDir
    static Path repoRoot;

    @TempDir
    static Path libraryRoot;

    @TempDir
    static Path inbox;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void paths(final DynamicPropertyRegistry registry) {
        registry.add("sluice.paths.repo-root", repoRoot::toString);
        registry.add("sluice.paths.library-root", libraryRoot::toString);
        registry.add("sluice.paths.inbox", inbox::toString);
    }

    @Test
    void theFacadeAndItsProgressReporterAreBothBuilt() {
        assertThat(this.context.getBeanNamesForType(Pipeline.class)).hasSize(1);
        assertThat(this.context.getBeanNamesForType(ProgressPort.class)).hasSize(1);
    }

    // Which one, not only how many. Swapping the console writer back out for the log-line one
    // leaves the count at one, and sends every command's progress somewhere nobody is watching.
    @Test
    void progressIsReportedByTheCommandLinesOwnWriter() {
        assertThat(this.context.getBean(ProgressPort.class)).isInstanceOf(ConsoleProgressPort.class);
    }

    // The parser builds every verb through Spring while it assembles its command tree. So a verb
    // whose own dependencies cannot be satisfied stops the whole surface, not just itself.
    @Test
    void everyVerbTheSurfaceCarriesCanBeBuilt() {
        assertThat(SluiceCli.parser(this.context.getBean(SluiceCli.class), this.context.getBean(IFactory.class))
                .getSubcommands()).containsOnlyKeys("app", "runs");
    }

    @Test
    void theDesktopsOwnBeansAreLeftUnbuilt() {
        assertThat(this.context.getBeanNamesForType(StartupSequence.class)).isEmpty();
        assertThat(this.context.getBeanNamesForType(FolderRootsHousekeeping.class)).isEmpty();
    }

    @Test
    void nothingListensForAWorkingRootThatMoves() {
        assertThat(this.context.getBeanNamesForType(FolderRootsChangeListener.class)).isEmpty();
    }
}
