package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.ShardTally;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.CullPrepTestSupport.applyPlanner;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
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

    // The tally judges a shard against the prep dir's own recorded categories, the same source
    // ApplyPlanner uses. Worth its own test rather than leaning on the planner's. This is a second,
    // independent call into ShardValidator. The field it has to pass shares its type with entries,
    // so handing over the wrong one would compile.
    //
    // The index records a set the live settings do not have, and omits one they do. A montage whose
    // shard names the configured-but-unrecorded category reads invalid, which is the reverse of what
    // live config would say.
    @Test
    void aMontageIsJudgedAgainstTheCategoriesThePrepDirRecorded(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path recorded = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path configured = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(recorded, "paperwork");
        writeFile(configured, "blurry");
        writeIndex(prepDir, List.of("receipts"), 2, List.of(), List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(recorded));
        writeSidecar(prepDir, "montage-002", sidecarEntry(configured));
        writeShard(prepDir, "montage-001", classificationJson(recorded, "receipts", "photographed paperwork"));
        writeShard(prepDir, "montage-002", classificationJson(configured, "junk", "blurry"));

        final ShardTally tally = shardTallyCalculator().tally(readIndex(prepDir));

        assertThat(tally).isEqualTo(new ShardTally(2, 1, 2));
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

    // A non-I/O RuntimeException from hasShard(), read inside the same guard as the shard read and
    // the validation. tally() degrades that montage to present-but-invalid rather than throwing.
    @Test
    void aNonIoFailureCheckingShardPresenceStillReportsRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        final ShardTallyCalculator calculator = shardTallyCalculator(new ThrowingHasShard());

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 0, 1));
    }

    // The same failure, at isReadyToResume()'s own entry point. It answers not ready rather than
    // propagating, matching the tolerance its own Javadoc already claims for a transiently
    // unreadable index.
    @Test
    void aNonIoFailureCheckingShardPresenceAnswersNotReadyRatherThanThrowing(@TempDir final Path root)
            throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));
        final ShardTallyCalculator calculator = shardTallyCalculator(new ThrowingHasShard());

        assertThat(calculator.isReadyToResume(prepDir)).isFalse();
    }

    // The ledger read tally() itself makes, sitting outside every other guard in this class until a
    // whole-phase review caught it. Degrades to the raw unreviewable list rather than throwing, the
    // same tolerance every other read here already has.
    @Test
    void aFailedLedgerReadStillReportsATallyRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        final var calculator = new ShardTallyCalculator(new JsonCullPrepStore(), applyPlanner(),
                _ -> {
                    throw new IllegalStateException("simulated ledger read failure");
                });

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 1, 1));
    }

    private static ShardTallyCalculator shardTallyCalculator() {
        return shardTallyCalculator(new JsonCullPrepStore());
    }

    private static ShardTallyCalculator shardTallyCalculator(final CullPrepPort cullPrepPort) {
        return new ShardTallyCalculator(cullPrepPort, applyPlanner(new NioMediaStore(), cullPrepPort),
                moveLedger(new NioMediaStore()));
    }

    // Passes every read and write through to a real store. Only hasShard is overridden, the one
    // call this exists to fail: a non-I/O RuntimeException. A port constrains nothing about what an
    // adapter may actually raise.
    private static final class ThrowingHasShard implements CullPrepPort {

        private final CullPrepPort delegate = new JsonCullPrepStore();

        @Override
        public PrepDir readIndex(final Path prepDir) {
            return this.delegate.readIndex(prepDir);
        }

        @Override
        public void writeIndex(final Path prepDir, final PrepDir index) {
            this.delegate.writeIndex(prepDir, index);
        }

        @Override
        public List<SidecarPhotoEntry> readSidecar(final Path prepDir, final String montage) {
            return this.delegate.readSidecar(prepDir, montage);
        }

        @Override
        public boolean hasShard(final Path prepDir, final String montage) {
            throw new IllegalStateException("simulated non-I/O failure");
        }

        @Override
        public DecisionShard readShard(final Path prepDir, final String montage) {
            return this.delegate.readShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShardFile(final Path shardFile) {
            return this.delegate.readShardFile(shardFile);
        }

        @Override
        public void writeMergedDecisions(final Path prepDir, final String scope, final List<Decision> decisions,
                                         final ApplyReport report) {
            this.delegate.writeMergedDecisions(prepDir, scope, decisions, report);
        }
    }
}
