package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.listed;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;

// That every entry point runs the check is an ArchitectureTest rule, since it is a property of the
// whole surface rather than of any one method. What is left for a test is what the check does when
// it fails.
class PipelinePathGuardTest {

    private final RecordingProgressPort progress = new RecordingProgressPort();

    // Started work would report a failed run for what is really a settings problem, and would hold
    // the job slot while it said so.
    @Test
    void aJobIsRefusedBeforeItStartsRatherThanFailingInside(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        Files.delete(root.resolve("Library"));

        assertThatThrownBy(() -> pipeline.sort(new SortScope.OldestYear()))
                .isInstanceOf(PathsMisconfiguredException.class);
    }

    // As a value, so a screen can mark the field rather than parse a sentence.
    @Test
    void theRefusalNamesTheRootThatIsGone(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        final Path library = root.resolve("Library");
        Files.delete(library);

        assertThatThrownBy(pipeline::siftRuns)
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e ->
                        assertThat(e.violations())
                                .containsExactly(new NotADirectory(PathRole.LIBRARY_ROOT, library)));
    }

    // The refusal asserted first is the control: a fixture whose roots were fine would prove
    // nothing. Nothing is running here, so the answer below says only that the call was let through.
    @Test
    void theExitPathIsNotRefusedOnceAFolderRootHasGone(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        Files.delete(root.resolve("Library"));
        assertThatThrownBy(pipeline::siftRuns).isInstanceOf(PathsMisconfiguredException.class);

        assertThat(pipeline.stopAcceptingJobs(Duration.ofSeconds(5))).isTrue();
    }

    // Reading the roots per call is what lets the app boot with nothing configured.
    @Test
    void restoringTheFolderIsEnoughToWorkAgain(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        final Path library = root.resolve("Library");
        Files.delete(library);
        assertThatThrownBy(pipeline::siftRuns).isInstanceOf(PathsMisconfiguredException.class);

        Files.createDirectory(library);

        assertThat(listed(pipeline.siftRuns())).isEmpty();
    }
}
