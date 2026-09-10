package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.AnswerSource;
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
import static photos.sluice.application.service.CullPrepTestSupport.cards;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.keepJson;
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
        final ShardTallyCalculator calculator = shardTallyCalculator(root);
        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 0, 1));

        prepDirRemedies(root, root.resolve("Library"))
                .resolveOverlap(prepDir, photo, OverlapResolution.TRUST_DECISION, AnswerSource.DESKTOP);

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 1, 1));
    }

    // The field the recorded categories are passed in shares its type with entries, so handing over
    // the wrong one would compile.
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
        writeIndex(prepDir, cards("receipts"), 2, List.of(), List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(recorded));
        writeSidecar(prepDir, "montage-002", sidecarEntry(configured));
        writeShard(prepDir, "montage-001", classificationJson(recorded, "receipts", "photographed paperwork"));
        writeShard(prepDir, "montage-002", classificationJson(configured, "junk", "blurry"));

        final ShardTally tally = shardTallyCalculator(root).tally(readIndex(prepDir));

        assertThat(tally).isEqualTo(new ShardTally(2, 1, 2));
    }

    @Test
    void aShardJudgingOnlySomeOfItsOwnSheetCountsAsInvalid(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path judged = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path unjudged = root.resolve("Sorted/Photos/2019/06/b.jpg");
        final Path elsewhere = root.resolve("Sorted/Photos/2019/06/c.jpg");
        writeFile(judged, "paperwork");
        writeFile(unjudged, "a keeper");
        writeFile(elsewhere, "another keeper");
        writeIndex(prepDir, 3, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(judged), sidecarEntry(unjudged));
        writeSidecar(prepDir, "montage-002", sidecarEntry(elsewhere));
        writeShard(prepDir, "montage-001", classificationJson(judged, "junk", "photographed paperwork"));
        writeShard(prepDir, "montage-002", keepJson(elsewhere));

        final ShardTally tally = shardTallyCalculator(root).tally(readIndex(prepDir));

        assertThat(tally).isEqualTo(new ShardTally(2, 1, 2));
    }

    // The same shard file appears in both halves, and only its content differs. A check asking
    // only whether the file exists would answer true to both.
    //
    // Proved against the calculator rather than a running watcher, which keeps the claim off the
    // clock. No poll interval, no window, nothing to starve on a loaded machine.
    @Test
    void aShardStillBeingWrittenIsNotReadyToResumeButTheFinishedOneIs(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        final ShardTallyCalculator calculator = shardTallyCalculator(root);

        writeFile(prepDir.resolve("decisions-001.json"), "{ \"montage\": \"montage-001\", \"decis");
        assertThat(calculator.poll(prepDir).readyToResume()).isFalse();

        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThat(calculator.poll(prepDir).readyToResume()).isTrue();
    }

    @Test
    void aMontageWithNoShardAtAllIsNotReadyToResume(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));

        assertThat(shardTallyCalculator(root).poll(prepDir).readyToResume()).isFalse();
    }

    @Test
    void aNonIoFailureCheckingShardPresenceStillReportsRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        final ShardTallyCalculator calculator = shardTallyCalculator(root, new ThrowingHasShard());

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 0, 1));
    }

    @Test
    void aNonIoFailureCheckingShardPresenceAnswersNotReadyRatherThanThrowing(@TempDir final Path root)
            throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));
        final ShardTallyCalculator calculator = shardTallyCalculator(root, new ThrowingHasShard());

        assertThat(calculator.poll(prepDir).readyToResume()).isFalse();
    }

    // The shard is well-formed and rules on the photo the sidecar would have listed. So the only
    // thing between this and a valid montage is the sidecar nobody can parse.
    @Test
    void aSidecarNobodyCanParseStillReportsATallyRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeFile(prepDir.resolve("montage-001.json"), "{ \"photos\": [ { \"src\": ");

        final ShardTally tally = shardTallyCalculator(root).tally(readIndex(prepDir));

        // Present, because the shard is there and parses. Not valid, because the montage's own
        // sheet came back empty, so the file the shard rules on is not one this sheet covers.
        assertThat(tally).isEqualTo(new ShardTally(1, 0, 1));
    }

    @Test
    void aFailedLedgerReadStillReportsATallyRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        final var calculator = new ShardTallyCalculator(new JsonCullPrepStore(), applyPlanner(root),
                _ -> {
                    throw new IllegalStateException("simulated ledger read failure");
                });

        assertThat(calculator.tally(readIndex(prepDir))).isEqualTo(new ShardTally(1, 1, 1));
    }

    @Test
    void onePollAnswersBothReadinessAndTheTally(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeSidecar(prepDir, "montage-002", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final ShardTallyCalculator.Reading reading = shardTallyCalculator(root).poll(prepDir);

        assertThat(reading.readyToResume()).isFalse();
        assertThat(reading.tally()).isEqualTo(new ShardTally(1, 1, 2));
    }

    @Test
    void aPrepDirThatCouldNotBeReadHasNoTallyAtAll(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        writeFile(prepDir.resolve("index.json"), "{ \"scope\": ");

        final ShardTallyCalculator.Reading reading = shardTallyCalculator(root).poll(prepDir);

        assertThat(reading.readyToResume()).isFalse();
        assertThat(reading.tally()).isNull();
    }

    private static ShardTallyCalculator shardTallyCalculator(final Path root) {
        return shardTallyCalculator(root, new JsonCullPrepStore());
    }

    private static ShardTallyCalculator shardTallyCalculator(final Path root, final CullPrepPort cullPrepPort) {
        return new ShardTallyCalculator(cullPrepPort, applyPlanner(root, cullPrepPort),
                moveLedger(new NioMediaStore()));
    }

    // Only hasShard is overridden, the one call this exists to fail with a non-I/O
    // RuntimeException. A port constrains nothing about what an adapter may actually raise.
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
