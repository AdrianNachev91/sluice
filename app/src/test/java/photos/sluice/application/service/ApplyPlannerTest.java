package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.MalformedPrepJsonException;
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

    // The shard contract is checked once over every montage's shards together, never one montage at
    // a time. A group id reused across two shards is the clearest proof. Each shard alone is
    // perfectly well-formed, with one chosen keeper and one reject. Only a whole-set check can see
    // that the two groups would merge into a single Duplicates folder at apply time.
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

    // The pair below is why a prep dir records its own category set. Both run against the standard
    // fixedSettings() wiring, whose categories are junk/scenery/food/funny. Each index deliberately
    // disagrees with that set, and the index is what decides.
    //
    // Here the shard names a category nobody has configured. It applies anyway, because the run was
    // prepped under a set that had it. Editing a category cannot strand a run that already named it.
    @Test
    void aCategoryOnlyThePrepDirRecordsStillValidates(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "paperwork");
        writeIndex(prepDir, List.of("receipts"), 1, List.of(), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "receipts", "photographed paperwork"));

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.exists(photo)).as("routed out of Sorted").isFalse();
        assertThat(Files.exists(root.resolve("Review/receipts/a.jpg"))).isTrue();
    }

    // The mirror. junk IS configured, and the run was not prepped under it, so it is refused. A
    // planner still consulting config would let this through.
    @Test
    void aConfiguredCategoryThePrepDirNeverRecordedIsRefused(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "blurry");
        writeIndex(prepDir, List.of("receipts"), 1, List.of(), List.of("montage-001"));
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
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk - a copy that never
        // ran
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

    // classify() answers Unresolved for a NearDupChosen decision before it ever consults a move
    // record. A real run copies the chosen file rather than moving it, so it never writes one. A
    // record naming a NearDupChosen source can only have come from somewhere else, and is refused
    // even when it hash-verifies.
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
        // The move record points at a destination that was never actually written - a record alone
        // is never treated as proof; the destination has to hash-verify too.
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
        // A hash that deliberately doesn't match dest's actual content. Stands in for the
        // destination having been altered (or a different file landing there) after the record
        // for this decision was written.
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
        // No sidecar written for montage-001 at all - stands in for a missing or corrupt one; both
        // fail the same way (readSidecar() throws UncheckedIOException either way).
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
        assertThat(report.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(report.decisions()).isEmpty();
    }

    // A montage with an unreadable sidecar and no shard reads like one still being culled, and is
    // not. A culler keys its verdicts against the sidecar, so it can never produce a shard for a
    // montage whose sidecar it cannot read. Left unreported, the run sits WAITING with an empty
    // findings list and only a discard escapes it. Reported, SET_ASIDE becomes reachable.
    @Test
    void validateReportsACorruptSidecarForAMontageWithNoShardYet(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has neither a sidecar nor a shard.

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).containsExactly(new Finding.CorruptSidecar("montage-002"));
    }

    // A montage whose sidecar reads fine and simply lacks a shard is skipped in silence, because it
    // genuinely is still being culled. This is the boundary the corrupt-sidecar finding must not
    // cross. An implementation flagging every uncalled montage would still satisfy every assertion
    // about reporting a corrupt one, while burying the user in noise for runs that are mid-cull.
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

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
    }

    // Both answers are terminal, so neither re-raises the finding the user already settled. SET_ASIDE
    // drops the montage; there is no shard for APPLY_ANYWAY to trust, so it contributes nothing.
    @Test
    void validateStopsReportingACorruptSidecarWithNoShardOnceItIsSetAside(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        prepDirRemedies(root, root.resolve("Library")).resolveCorruptSidecar(prepDir, "montage-002",
                CorruptSidecarResolution.SET_ASIDE, "nothing left to cull it against");

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
    }

    // APPLY_ANYWAY on a shardless montage says to trust a shard that is not there, so the montage
    // contributes nothing. What matters is that the answer still counts as given. Re-raising the
    // finding would ask the user to settle something they already settled.
    @Test
    void validateStopsReportingACorruptSidecarWithNoShardOnceItIsApplyAnyway(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        prepDirRemedies(root, root.resolve("Library")).resolveCorruptSidecar(prepDir, "montage-002",
                CorruptSidecarResolution.APPLY_ANYWAY, "its shard will turn up");

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings()).isEmpty();
        // Weak on its own, since montage-002 has no shard file for any implementation to read. It
        // pins the other half of the sentence above: the answered montage contributes nothing while
        // montage-001 still contributes normally.
        assertThat(report.decisions()).hasSize(1);
    }

    // This gate is the only one an apply-only resume passes through, so an unparseable shard has to
    // come back as a finding here. Left to escape as an exception it would crash the job instead of
    // resolving it to a Blocked run the user can act on.
    @Test
    void validateReportsCorruptShardForAMontageWhoseShardCannotBeParsed(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeFile(prepDir.resolve("decisions-001.json"), "{ not valid json");

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(report.decisions()).isEmpty();
    }

    // A corrupt shard must not take the rest of the batch down with it. The second montage's
    // decisions still have to reach the report, so a troubleshooter sees one problem rather than a
    // whole scope gone dark.
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

        final ValidationReport report = applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir));

        assertThat(report.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(report.decisions()).extracting(Decision::file).containsExactly(second);
    }

    // The other half of the damaged-vs-failed split. A shard whose read merely failed says nothing
    // about the culling agent's work. Blaming it with a CorruptShard finding would be a wrong
    // diagnosis on content that is very likely intact.
    @Test
    void validateLetsAFailedShardReadPropagateInsteadOfBlamingTheCuller(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        // A directory where the shard file belongs: present to hasShard(), unreadable to the codec.
        Files.createDirectory(prepDir.resolve("decisions-001.json"));

        assertThatThrownBy(() -> applyPlanner()
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir)))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    // The sidecar side of the same damaged-vs-failed split. A montage whose sidecar merely failed to
    // read - a lock held for a moment by a backup process - has done nothing wrong. Its culler
    // answers are not suspect. Diagnosing it as CorruptSidecar would cost the user an irreversible
    // CHOICE answer over a file that was never damaged. Injected at the CullPrepPort seam, proving
    // the classification without depending on how a given platform's filesystem treats a directory
    // standing in for a file.
    @Test
    void validateLetsAFailedSidecarReadPropagateInsteadOfDiagnosingCorruption(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));

        assertThatThrownBy(() -> applyPlanner(new FailingSidecarRead())
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true), readLedger(prepDir)))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }
}
