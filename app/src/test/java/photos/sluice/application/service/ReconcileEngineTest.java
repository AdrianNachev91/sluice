package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.ReconcileReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.CullPrepTestSupport.applyEngine;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupChosenJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupRejectJson;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.prepDirRemedies;
import static photos.sluice.application.service.CullPrepTestSupport.reconcileEngine;
import static photos.sluice.application.service.CullPrepTestSupport.sidecarEntry;
import static photos.sluice.application.service.CullPrepTestSupport.writeFile;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;
import static photos.sluice.application.service.CullPrepTestSupport.writeMoveRecord;
import static photos.sluice.application.service.CullPrepTestSupport.writeShard;
import static photos.sluice.application.service.CullPrepTestSupport.writeSidecar;

// Rebuilding a prep dir's move ledger from disk state alone. The recurring fixture shape is a
// source file deliberately never written. It stands in for one an earlier run already moved away
// before that run's ledger was lost.
class ReconcileEngineTest {

    @Test
    void reconcileRebuildsAReconstructedRecordForAFileFoundAtItsExpectedDestination(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - stands in for an already-moved file
        Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "already-moved-content");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.reconstructed()).isEqualTo(1);
        assertThat(report.stillPending()).isZero();
        assertThat(report.missingSource()).isEmpty();
        List<String> lines = Files.readAllLines(prepDir.resolve("move-records.log"));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains(dest.toString()).contains("RECONSTRUCTED");
    }

    @Test
    void reconcileCountsAFileStillAtItsOriginalLocationAsStillPendingWithNoLogEntry(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.stillPending()).isEqualTo(1);
        assertThat(report.reconstructed()).isZero();
        // Nothing to reconstruct means no log line to append - the file is never even recreated.
        assertThat(Files.exists(prepDir.resolve("move-records.log"))).isFalse();
    }

    @Test
    void reconcileReportsMissingSourceWhenNoDestinationCandidateExists(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/gone.jpg"); // never written, no destination either
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.missingSource()).containsExactly(new MissingSource(photo, prepDir.resolve("move-records.log")));
        assertThat(report.reconstructed()).isZero();
    }

    // Even when a copy already sits at the near-dup group's Duplicates/ destination, a NearDupChosen
    // decision's missing source is never reconstructed from it - see the rationale below.
    @Test
    void reconcileNeverReconstructsANearDupChosenDecision(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - the photo itself is gone
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(reject, "blurry");
        // Looks exactly like a successful near-dup-chosen copy, sitting right where one would land.
        // reconcile() must never treat this as proof. A NearDupChosen's source is never removed by
        // a real run, so a missing one can only mean the photo is genuinely gone.
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        writeFile(dupDir.resolve("a.jpg"), "looks-like-a-copy");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.missingSource()).extracting(MissingSource::file).containsExactly(chosen);
        assertThat(report.stillPending()).isEqualTo(1);
        assertThat(report.reconstructed()).isZero();
    }

    @Test
    void reconcileFilesAnExistingMoveRecordsLogIntoTheDisasterDrawerBeforeRebuilding(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeMoveRecord(prepDir, root.resolve("Sorted/Photos/2019/06/stale.jpg"),
                root.resolve("Review/junk/stale.jpg"), "stale-hash");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        reconcileEngine(root, libraryRoot).reconcile(prepDir);

        Path drawer = prepDir.resolve("disasters");
        assertThat(Files.exists(drawer)).isTrue();
        try (var entries = Files.list(drawer)) {
            List<Path> filed = entries.toList();
            assertThat(filed).hasSize(1);
            assertThat(filed.getFirst().getFileName().toString()).contains("move-records-log");
        }
        // photo is still pending (source untouched), so the sweep has nothing to append - the filed-
        // away log is not replaced by an empty one.
        assertThat(Files.exists(prepDir.resolve("move-records.log"))).isFalse();
    }

    @Test
    void reconcileAssignsCollisionCandidatesInDecisionOrderWhenTwoDecisionsShareAnOriginalFileName(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // Two different source photos, from different month folders, happen to share a leaf name -
        // both never re-written to disk. They stand in for an earlier, log-lost run that moved both
        // into Review/junk/, landing the second at its " (2)" collision suffix. Two claimants, two
        // contiguous candidates - the counts-match case reconcile() reconstructs.
        Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path second = root.resolve("Sorted/Photos/2019/07/a.jpg");
        Path firstDest = root.resolve("Review/junk/a.jpg");
        Path secondDest = root.resolve("Review/junk/a (2).jpg");
        writeFile(firstDest, "first-content");
        writeFile(secondDest, "second-content");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "junk", "blurry"),
                classificationJson(second, "junk", "also blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.reconstructed()).isEqualTo(2);
        List<String> lines = Files.readAllLines(prepDir.resolve("move-records.log"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains(first.toString()).contains(firstDest.toString());
        assertThat(lines.get(1)).contains(second.toString()).contains(secondDest.toString());
    }

    @Test
    void reconcileRefusesToReconstructWhenAnUnrelatedFileClaimsTheFirstCollisionSlot(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // Only one decision ever moved here, but the plain-named slot is held by some stranger's
        // file (e.g. a recycled camera filename from an unrelated run). Our own file landed on
        // " (2)" instead. Two candidates exist for one claimant - a surplus. reconcile() cannot tell
        // which candidate is genuinely ours, so it refuses both rather than hashing the wrong one
        // into a trusted RECONSTRUCTED record.
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written
        writeFile(root.resolve("Review/junk/a.jpg"), "unrelated-stranger-content");
        writeFile(root.resolve("Review/junk/a (2).jpg"), "actually-ours");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.missingSource()).containsExactly(new MissingSource(photo, prepDir.resolve("move-records.log")));
        assertThat(report.reconstructed()).isZero();
        assertThat(Files.exists(prepDir.resolve("move-records.log"))).isFalse();
    }

    @Test
    void reconcileRefusesToReconstructWhenTwoClaimantsShareAnOriginalNameButOnlyOneCandidateExists(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // Two decisions started with the same leaf name, but only the plain-named slot exists on
        // disk - a deficit. reconcile() cannot tell which claimant it belongs to, so both are
        // reported missing rather than guessed at.
        Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path second = root.resolve("Sorted/Photos/2019/07/a.jpg");
        writeFile(root.resolve("Review/junk/a.jpg"), "only-one-candidate");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "junk", "blurry"),
                classificationJson(second, "junk", "also blurry"));

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.missingSource()).containsExactlyInAnyOrder(
                new MissingSource(first, prepDir.resolve("move-records.log")),
                new MissingSource(second, prepDir.resolve("move-records.log")));
        assertThat(report.reconstructed()).isZero();
        assertThat(Files.exists(prepDir.resolve("move-records.log"))).isFalse();
    }

    @Test
    void reconcileHandlesAnUnreviewableFileTheSameWayAsADecision(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic"); // never written
        Path dest = root.resolve("Unreviewable/2019/06/corrupt.heic");
        writeFile(dest, "already-moved");
        writeIndex(prepDir, 0, List.of(undecodable), List.of());

        ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        assertThat(report.reconstructed()).isEqualTo(1);
        assertThat(Files.readAllLines(prepDir.resolve("move-records.log")).getFirst()).contains(dest.toString());
    }

    @Test
    void reconcileThrowsWhenTheShardContractItselfDoesNotValidate(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "meme", "not a configured category"));

        assertThatThrownBy(() -> reconcileEngine(root, libraryRoot).reconcile(prepDir))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("invalid action 'meme'");
    }

    @Test
    void aReconciledRecordIsTrustedByALaterApplyRunAsIfItHadBeenWitnessed(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - a lost log after a real move
        Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        writeFile(root.resolve("Review/junk/a.jpg"), "already-moved");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "junk", "also blurry"));
        reconcileEngine(root, libraryRoot).reconcile(prepDir);

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        // The reconciled decision is recognized as already done, not reprocessed. Only the pending
        // one counts as this run's own work. Its reasons line is still backfilled, though, since
        // reconcile() never writes one itself.
        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt")))
                .containsExactlyInAnyOrder("a.jpg - blurry", "b.jpg - also blurry");
    }

    // reconcile() resolves the unreviewable list against the ledger BEFORE filing the old log into
    // the disaster drawer. Filing it away leaves nothing to resolve against, so a TRUST_DECISION
    // overlap would revert to unresolved. The file would then be swept a second time as an
    // unreviewable file, and reported missing at a destination it was never headed for.
    @Test
    void reconcileResolvesTheUnreviewableListBeforeFilingTheLedgerAway(@TempDir Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - moved by a run whose log was lost
        writeFile(root.resolve("Review/junk/a.jpg"), "already-moved");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        prepDirRemedies(root, libraryRoot).resolveOverlap(prepDir, photo, OverlapResolution.TRUST_DECISION,
                "the decision is correct");

        final ReconcileReport report = reconcileEngine(root, libraryRoot).reconcile(prepDir);

        // Swept once, as the decision the user chose to trust.
        assertThat(report.reconstructed()).isEqualTo(1);
        assertThat(report.missingSource()).isEmpty();
    }

}
