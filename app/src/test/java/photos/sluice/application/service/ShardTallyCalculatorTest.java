package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.job.ShardTally;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.CullPrepTestSupport.applyPlanner;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.fixedSettings;
import static photos.sluice.application.service.CullPrepTestSupport.moveLedger;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.prepDirRemedies;
import static photos.sluice.application.service.CullPrepTestSupport.readIndex;
import static photos.sluice.application.service.CullPrepTestSupport.sidecarEntry;
import static photos.sluice.application.service.CullPrepTestSupport.writeFile;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;
import static photos.sluice.application.service.CullPrepTestSupport.writeShard;
import static photos.sluice.application.service.CullPrepTestSupport.writeSidecar;

class ShardTallyCalculatorTest {

    // The tally is a display number. This is the one behaviour of it worth pinning: an answer the
    // user has already given stops the montage reporting that problem back at them. A file listed
    // as unreviewable AND named by a decision is the overlap TRUST_DECISION resolves. Resolving it
    // means the decision wins, so the file stops counting as unreviewable at all.
    //
    // The fixture puts the photo in the montage's own sidecar too. Without that it would be out of
    // scope on top of overlapping. That second finding would hold the montage invalid whatever the
    // user answered, making the assertion below pass for the wrong reason.
    @Test
    void aTrustDecisionAnswerStopsTheOverlappingMontageCountingAsInvalid(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        final ShardTallyCalculator calculator = shardTallyCalculator();
        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 0, 1));

        prepDirRemedies(root, root.resolve("Library"))
                .resolveOverlap(prepDir, photo, OverlapResolution.TRUST_DECISION, "the decision is right");

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 1, 1));
    }

    // Readiness is what a watcher polls, so the same shard file appears in both halves and only its
    // content differs. That is what makes parseability the thing under test rather than presence:
    // a check that asked only whether the file exists would answer true to both.
    //
    // The truncated content is what a poll landing mid-write sees, since the file exists from the
    // moment the agent opens it. Proving it here rather than through a running watcher keeps the
    // claim off the clock. No poll interval, no window, nothing to starve on a loaded machine.
    @Test
    void aShardStillBeingWrittenIsNotReadyToResumeButTheFinishedOneIs(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        final ShardTallyCalculator calculator = shardTallyCalculator();

        writeFile(prepDir.resolve("decisions-001.json"), "{ \"montage\": \"montage-001\", \"decis");
        assertThat(calculator.isReadyToResume(prepDir)).isFalse();

        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThat(calculator.isReadyToResume(prepDir)).isTrue();
    }

    // The shard is absent entirely rather than unreadable. That is the ordinary "agent has not got
    // to this montage yet" state a watch spends most of its life in.
    @Test
    void aMontageWithNoShardAtAllIsNotReadyToResume(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));

        assertThat(shardTallyCalculator().isReadyToResume(prepDir)).isFalse();
    }

    private static ShardTallyCalculator shardTallyCalculator() {
        return new ShardTallyCalculator(new JsonCullPrepStore(), fixedSettings(), applyPlanner(),
                moveLedger(new NioMediaStore()));
    }
}
