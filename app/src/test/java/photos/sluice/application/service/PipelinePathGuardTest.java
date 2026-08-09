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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;

// That every entry point runs the check is an ArchitectureTest rule, since it is a property of the
// whole surface rather than of any one method. What is left for a test is what the check does when
// it fails.
class PipelinePathGuardTest {

    private final RecordingProgressPort progress = new RecordingProgressPort();

    // The refusal has to reach the caller instead of the job. Started work would report a failed run
    // for what is really a settings problem, and would already hold the job slot to say so.
    @Test
    void aJobIsRefusedBeforeItStartsRatherThanFailingInside(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        Files.delete(root.resolve("Library"));

        assertThatThrownBy(() -> pipeline.sort(new SortScope.OldestYear()))
                .isInstanceOf(PathsMisconfiguredException.class);
    }

    // The refusal carries the violation as a value, so a screen can mark the field that caused it
    // rather than reading a sentence.
    @Test
    void theRefusalNamesTheRootThatIsGone(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        final Path library = root.resolve("Library");
        Files.delete(library);

        assertThatThrownBy(pipeline::cullRuns)
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e ->
                        assertThat(e.violations())
                                .containsExactly(new NotADirectory(PathRole.LIBRARY_ROOT, library)));
    }

    // The check reads the roots on every call rather than at construction. That is what lets the app
    // boot with nothing configured, and start working the moment a first run configures it.
    @Test
    void restoringTheFolderIsEnoughToWorkAgain(@TempDir final Path root) throws IOException {
        final Pipeline pipeline = pipeline(root, this.progress);
        final Path library = root.resolve("Library");
        Files.delete(library);
        assertThatThrownBy(pipeline::cullRuns).isInstanceOf(PathsMisconfiguredException.class);

        Files.createDirectory(library);

        assertThat(pipeline.cullRuns()).isEmpty();
    }
}
