package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.ValidationReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.CullPrepTestSupport.FailingSidecarRead;
import static photos.sluice.application.service.CullPrepTestSupport.applyEngine;
import static photos.sluice.application.service.CullPrepTestSupport.applyPlanner;
import static photos.sluice.application.service.CullPrepTestSupport.cards;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupChosenJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupRejectJson;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.prepDirRemedies;
import static photos.sluice.application.service.CullPrepTestSupport.readIndex;
import static photos.sluice.application.service.CullPrepTestSupport.readLedger;
import static photos.sluice.application.service.CullPrepTestSupport.sidecarEntry;
import static photos.sluice.application.service.CullPrepTestSupport.writeFile;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;
import static photos.sluice.application.service.CullPrepTestSupport.writeMoveRecord;
import static photos.sluice.application.service.CullPrepTestSupport.writeShard;
import static photos.sluice.application.service.CullPrepTestSupport.writeSidecar;

// The read-only half of applying: whether a batch of shards is valid at all, and where each
// decision already stands. Most of these drive a real apply() and assert on what it refused to do.
// A planner verdict is only observable through the run it permits or blocks.
class ApplyPlannerTest {

    // Each shard alone is well-formed, with one chosen keeper and one reject. Only a whole-set
    // check can see that the two groups would merge into a single Duplicates folder at apply time.
    @Test
    void aNearDupGroupIdReusedAcrossTwoMontagesFailsValidation(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path juneChosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path juneReject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        final Path julyChosen = root.resolve("Sorted/Photos/2019/07/c.jpg");
        final Path julyReject = root.resolve("Sorted/Photos/2019/07/d.jpg");
        writeFile(juneChosen, "sharp");
        writeFile(juneReject, "blurry");
        writeFile(julyChosen, "sharp too");
        writeFile(julyReject, "blurry too");
        writeIndex(prepDir, 4, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(juneChosen), sidecarEntry(juneReject));
        writeSidecar(prepDir, "montage-002", sidecarEntry(julyChosen), sidecarEntry(julyReject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(juneChosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(juneReject, "lake-jun19", "blurred"));
        writeShard(prepDir, "montage-002",
                nearDupChosenJson(julyChosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(julyReject, "lake-jun19", "blurred"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings()).containsExactly(
                        new Finding.GroupSpansMultipleMontages("lake-jun19", List.of("montage-001", "montage-002"))));
        assertThat(Files.exists(juneChosen)).isTrue();
        assertThat(Files.exists(julyReject)).isTrue();
    }

    // This pair and the next both run against the standard fixedSettings() wiring, which preps
    // under scenery, food, funny and junk. Each index deliberately disagrees with that set.
    //
    // Here the shard names a category nobody has configured, and it applies anyway because the run
    // was prepped under a set that had it.
    @Test
    void aCategoryOnlyThePrepDirRecordsStillValidates(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "paperwork");
        writeIndex(prepDir, cards("receipts"), 1, List.of(), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "receipts", "photographed paperwork"));

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.exists(photo)).as("routed out of Sorted").isFalse();
        assertThat(Files.exists(root.resolve("Review/receipts/a.jpg"))).isTrue();
    }

    // The mirror. junk is one every run gets, and this one was not prepped under it, so a planner
    // still consulting config would let it through.
    @Test
    void aConfiguredCategoryThePrepDirNeverRecordedIsRefused(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "blurry");
        writeIndex(prepDir, cards("receipts"), 1, List.of(), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings()).containsExactly(
                        new Finding.InvalidCategory("montage-001", 1, "junk", "allowed: receipts")));
        assertThat(Files.exists(photo)).as("left where it was").isTrue();
    }

    @Test
    void aMissingFileWithNoMoveRecordFailsLoudlyAndRefusesToGuess(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk, no move record either
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified")
                .hasMessageContaining(prepDir.resolve("move-records.log").toString())
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings())
                        .containsExactly(new MissingSource(photo, prepDir.resolve("move-records.log"))));
    }

    @Test
    void aMissingNearDupChosenFileFailsLoudlyEvenThoughItsNeverAMoveBasedDecision(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // Never written to disk, standing in for a copy that never ran.
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(reject, "blurry");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified");
        assertThat(Files.exists(reject)).isTrue();
    }

    // A real run copies the chosen file rather than moving it, so it never writes a move record. A
    // record naming one can only have come from somewhere else.
    @Test
    void aNearDupChosenDecisionIsNeverResolvedByAMoveRecordEvenOneThatHashVerifies(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - the photo itself is gone
        final Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(reject, "blurry");
        final Path chosenDest = root.resolve("Duplicates/2019-06_lake-jun19/a.jpg");
        writeFile(chosenDest, "looks-like-a-copy");
        writeMoveRecord(prepDir, chosen, chosenDest, new Sha256Hasher().hash(chosenDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified");
        assertThat(Files.exists(reject)).isTrue();
    }

    @Test
    void aMoveRecordWhoseDestinationIsMissingStillFailsLoudlyRatherThanTrustingTheRecordAlone(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
        // The move record points at a destination that was never written.
        writeMoveRecord(prepDir, photo, root.resolve("Review/junk/a.jpg"), "not-a-real-hash-value");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified");
    }

    @Test
    void aMoveRecordWhoseDestinationContentNoLongerMatchesStillFailsLoudly(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
        final Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "content changed after the record was written");
        // A hash that does not match dest's content, standing in for the destination having been
        // altered, or a different file landing there, after the record was written.
        writeMoveRecord(prepDir, photo, dest, "not-a-real-hash-value");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified");
    }

    @Test
    void validateReportsCorruptSidecarForAMontageWithAShardButNoReadableSidecar(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        // No sidecar for montage-001 at all. Missing and corrupt fail the same way, the read
        // throwing either way.
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
        assertThat(report.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(report.decisions()).isEmpty();
    }

    // A montage with an unreadable sidecar and no shard reads like one still being culled, and is
    // not. A culler keys its verdicts against the sidecar, so it can never produce a shard here.
    // Unreported, the run sits WAITING with an empty findings list and only a discard escapes it.
    @Test
    void validateReportsACorruptSidecarForAMontageWithNoShardYet(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has neither a sidecar nor a shard.

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).containsExactly(new Finding.CorruptSidecar("montage-002"));
    }

    // The boundary the corrupt-sidecar finding must not cross. An implementation flagging every
    // uncalled montage would satisfy every assertion about reporting a corrupt one, while burying
    // the user in noise for runs that are mid-sift.
    @Test
    void validateSaysNothingAboutAMontageWithAReadableSidecarAndNoShardYet(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path uncalled = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(culled, "x");
        writeFile(uncalled, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeSidecar(prepDir, "montage-002", sidecarEntry(uncalled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void validateStopsReportingACorruptSidecarWithNoShardOnceItIsSetAside(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        prepDirRemedies(root, root.resolve("Library")).resolveCorruptSidecar(prepDir, "montage-002",
                CorruptSidecarResolution.SET_ASIDE, AnswerSource.DESKTOP);

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
    }

    // APPLY_ANYWAY on a shardless montage says to trust a shard that is not there, so the montage
    // contributes nothing. What matters is that the answer still counts as given.
    @Test
    void validateStopsReportingACorruptSidecarWithNoShardOnceItIsApplyAnyway(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        prepDirRemedies(root, root.resolve("Library")).resolveCorruptSidecar(prepDir, "montage-002",
                CorruptSidecarResolution.APPLY_ANYWAY, AnswerSource.DESKTOP);

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
        // Weak on its own, montage-002 having no shard file for any implementation to read. It
        // pins the other half: the answered montage contributes nothing while montage-001 still
        // contributes normally.
        assertThat(report.decisions()).hasSize(1);
    }

    // This gate is the only one an apply-only resume passes through. Escaping as an exception, an
    // unparseable shard would crash the job rather than resolving to a Blocked run.
    @Test
    void validateReportsCorruptShardForAMontageWhoseShardCannotBeParsed(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeFile(prepDir.resolve("decisions-001.json"), "{ not valid json");

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(report.decisions()).isEmpty();
    }

    // The second montage's decisions still have to reach the report, so a troubleshooter sees one
    // problem rather than a whole scope gone dark.
    @Test
    void validateStillCollectsEveryOtherMontagesDecisionsAlongsideACorruptShard(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path second = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(first, "x");
        writeFile(second, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first));
        writeSidecar(prepDir, "montage-002", sidecarEntry(second));
        writeFile(prepDir.resolve("decisions-001.json"), "{ not valid json");
        writeShard(prepDir, "montage-002", classificationJson(second, "junk", "blurry"));

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(report.decisions()).extracting(Decision::file).containsExactly(second);
    }

    // A shard whose read merely failed says nothing about the culling agent's work. A CorruptShard
    // finding would be a wrong diagnosis on content that is likely intact.
    @Test
    void validateLetsAFailedShardReadPropagateInsteadOfBlamingTheCuller(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        // A directory where the shard file belongs: present to hasShard(), unreadable to the codec.
        Files.createDirectory(prepDir.resolve("decisions-001.json"));

        assertThatThrownBy(() -> applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir)))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    // A sidecar whose read merely failed, over a lock a backup process held for a moment, has done
    // nothing wrong. Diagnosing it as CorruptSidecar would cost the user an irreversible CHOICE
    // answer over a file that was never damaged. Injected at the CullPrepPort seam, so the
    // classification does not depend on how a platform treats a directory standing in for a file.
    @Test
    void validateLetsAFailedSidecarReadPropagateInsteadOfDiagnosingCorruption(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));

        assertThatThrownBy(() -> applyPlanner(root, new FailingSidecarRead())
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir)))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    @Test
    void validateRefusesAnUnreviewableEntryOutsideTheSortedRoot(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path inside = root.resolve("Sorted/Photos/2019/06/undecodable.jpg");
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(inside, "x");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 0, List.of(inside, outside), List.of());

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    @Test
    void validateRefusesAnUnreviewableEntryClimbingOutOfTheSortedRoot(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path escaping = root.resolve("Sorted/Photos/../../Documents/taxes.pdf");
        writeFile(root.resolve("Documents/taxes.pdf"), "not media at all");
        writeIndex(prepDir, 0, List.of(escaping), List.of());

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.SourceOutsideSorted(escaping, root.resolve("Sorted")));
    }

    @Test
    void validateRefusesASidecarSourceOutsideTheSortedRoot(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(outside));
        writeShard(prepDir, "montage-001", classificationJson(outside, "junk", "blurry"));

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    // A rule reading only the sidecars passes this fixture, which has none.
    @Test
    void validateRefusesAnApplyAnywayShardsFileOutsideTheSortedRoot(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeShard(prepDir, "montage-001", classificationJson(outside, "junk", "blurry"));
        prepDirRemedies(root, root.resolve("Library")).resolveCorruptSidecar(prepDir, "montage-001",
                CorruptSidecarResolution.APPLY_ANYWAY, AnswerSource.DESKTOP);

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    @Test
    void validateRefusesAFileNamedByBothSourceListsOnlyOnce(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 1, List.of(outside), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(outside));

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    @Test
    void validateRefusesTheSortedRootItselfAsAnUnreviewableEntry(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path sorted = root.resolve("Sorted");
        Files.createDirectories(sorted);
        writeIndex(prepDir, 0, List.of(sorted), List.of());

        final ValidationReport report = applyPlanner(root)
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).containsExactly(new Finding.SourceOutsideSorted(sorted, sorted));
    }
}
