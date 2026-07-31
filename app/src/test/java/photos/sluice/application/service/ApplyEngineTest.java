package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Finding.MissingShard;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.model.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.CullPrepTestSupport.applyEngine;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.hashIndex;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupChosenJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupRejectJson;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.sidecarEntry;
import static photos.sluice.application.service.CullPrepTestSupport.writeFile;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;
import static photos.sluice.application.service.CullPrepTestSupport.writeMoveRecord;
import static photos.sluice.application.service.CullPrepTestSupport.writeShard;
import static photos.sluice.application.service.CullPrepTestSupport.writeSidecar;
import static photos.sluice.application.service.CullPrepTestSupport.writeUndecodable;

// Carrying decisions out against a real filesystem. It covers the moves, copies and secondary
// writes each decision type produces, resuming a crashed run, cancellation, and the finalizers that
// close a completed run. Deciding WHAT to carry out belongs to the planner, in ApplyPlannerTest.
class ApplyEngineTest {

    @Test
    void aClassificationDecisionMovesTheFileToReviewCategoryAndWritesAReasonsNote(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/IMG_1.jpg");
        writeFile(photo, "junk");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "phone photo of a monitor"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/junk/_reasons.txt")))
                .contains("IMG_1.jpg - phone photo of a monitor");
    }

    // The safety property behind the ledger's tolerance of a damaged file. Reading a ruined
    // move-record log as no records must cost proof, never permission. The decision it could have
    // proven becomes unresolvable, so the run refuses rather than moving anything.
    @Test
    void anUndecodableMoveRecordLogBlocksTheNextApplyInsteadOfLettingItActAgain(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path first = root.resolve("Sorted/Photos/2019/06/IMG_1.jpg");
        final Path second = root.resolve("Sorted/Photos/2019/06/IMG_2.jpg");
        // Culled in the same batch, then put back in Sorted afterwards. It is the one source still
        // on disk for the second run, so it is the only thing a wrongly-permissive run could move.
        final Path still = root.resolve("Sorted/Photos/2019/06/IMG_3.jpg");
        writeFile(first, "junk");
        writeFile(second, "also junk");
        writeFile(still, "blurry too");
        writeIndex(prepDir, 3, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second), sidecarEntry(still));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "junk", "blurry"),
                classificationJson(second, "junk", "also blurry"),
                classificationJson(still, "junk", "blurry too"));
        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));
        writeFile(still, "blurry too"); // put back in Sorted after that run carried it out
        writeUndecodable(prepDir.resolve("move-records.log"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("IMG_1.jpg");

        // still is what makes "nothing applied" falsifiable. A run that shrugged off the
        // unresolvable pair and carried on would have moved it out of Sorted.
        assertThat(Files.exists(still)).isTrue();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_3.jpg"))).isFalse();
    }

    @Test
    void aFunnyClassificationMovesToLibraryFunnyAndAppendsAnIndexRowWithNoReasonsNote(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/meme.jpg");
        writeFile(photo, "haha");
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        final ApplyReport report = applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("funny", 1);
        final Path dest = libraryRoot.resolve("Funny/meme.jpg");
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Funny/_reasons.txt"))).isFalse();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(dest));
    }

    @Test
    void nearDupChosenIsCopiedRejectIsMovedAndAChosenNoteListsAllRejects(@TempDir final Path root) throws IOException
            , ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(chosen, "sharp");
        writeFile(reject, "blurry");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.nearDupGroups()).isEqualTo(1);
        assertThat(report.nearDupRejects()).isEqualTo(1);
        assertThat(Files.exists(chosen)).isTrue();
        assertThat(Files.exists(reject)).isFalse();
        final Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        assertThat(Files.exists(dupDir.resolve("a.jpg"))).isTrue();
        assertThat(Files.exists(dupDir.resolve("b.jpg"))).isTrue();
        assertThat(Files.readString(dupDir.resolve("a.jpg.txt")))
                .contains("Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
    }

    @Test
    void aMissingShardWithoutAllowPartialFailsLoudlyAndMovesNothing(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("montage-001: no shard")
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings())
                        .containsExactly(new MissingShard("montage-001", "decisions-001.json")));
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void aMissingShardWithAllowPartialLeavesThatMontagesPhotosInPlace(@TempDir final Path root) throws IOException,
            ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path untouched = root.resolve("Sorted/Photos/2019/06/untouched.jpg");
        final Path junk = root.resolve("Sorted/Photos/2019/06/junk.jpg");
        writeFile(untouched, "x");
        writeFile(junk, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(untouched));
        writeSidecar(prepDir, "montage-002", sidecarEntry(junk));
        writeShard(prepDir, "montage-002", classificationJson(junk, "junk", "blurry"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(true));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(untouched)).isTrue();
        assertThat(Files.exists(root.resolve("Review/junk/junk.jpg"))).isTrue();
    }

    @Test
    void anOffContractDecisionFailsLoudlyAndMovesNothing(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "meme", "not a configured category"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("invalid action 'meme'");
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void aFileListedBothAsADecisionAndAsUnreviewableFailsValidationAndMovesNothing(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file listed both as a decision and as unreviewable: " + photo);
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void aDecisionsFileWithNoMatchingMontageFailsLoudly(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("decisions-002.json: no matching montage")
                .isInstanceOfSatisfying(ApplyException.class, e ->
                        assertThat(e.findings()).containsExactly(new StrayShard("decisions-002.json")));
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void autoHealsARetypedPathViaUniqueSidecarBasenameAndSurfacesItAsAHeal(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path actual = root.resolve("Sorted/Photos/2019/08/a.jpg");
        final Path retyped = root.resolve("Sorted/Photos/2019/09/a.jpg"); // culler wrote the wrong month segment
        writeFile(actual, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(actual));
        writeShard(prepDir, "montage-001", classificationJson(retyped, "junk", "blurry"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.heals()).hasSize(1);
        assertThat(Files.exists(actual)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
    }

    @Test
    void resumingAfterASimulatedCrashSkipsAlreadyAppliedFilesAndAppliesTheRest(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // alreadyMoved is never written to disk under Sorted at all - standing in for a decision a
        // prior, crashed run already carried out before dying. Its destination, reasons line, and
        // move record are all pre-placed exactly as a completed run would have left them.
        final Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        final Path alreadyMovedDest = root.resolve("Review/junk/a.jpg");
        writeFile(alreadyMovedDest, "already-moved");
        Files.writeString(root.resolve("Review/junk/_reasons.txt"), "a.jpg - blurry" + System.lineSeparator());
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "scenery", "weak composition"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("scenery", 1).doesNotContainKey("junk");
        assertThat(Files.exists(root.resolve("Review/scenery/b.jpg"))).isTrue();
        // The already-done decision was recognized, not reprocessed - its reasons line still
        // appears exactly once.
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt")))
                .containsExactly("a.jpg - blurry");
    }

    @Test
    void resumingPreservesAnAlreadyAppliedFunnyDecisionsIndexEntryAndIndexesThePendingOne(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // alreadyMoved's file was already relocated (and, per this fix, already indexed) by a prior
        // run before it crashed - simulated directly, rather than by actually crashing mid-run.
        final Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/old-meme.jpg");
        final Path pending = root.resolve("Sorted/Photos/2019/06/new-meme.jpg");
        writeFile(pending, "haha");
        final Path priorDest = libraryRoot.resolve("Funny/old-meme.jpg");
        writeFile(priorDest, "already-there");
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        final String priorHash = new Sha256Hasher().hash(priorDest);
        hashIndex.append(List.of(new IndexEntry(priorHash, priorDest)));
        writeMoveRecord(prepDir, alreadyMoved, priorDest, priorHash);
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "funny", "old meme"),
                classificationJson(pending, "funny", "new meme"));

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        final Path newDest = libraryRoot.resolve("Funny/new-meme.jpg");
        assertThat(Files.exists(newDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(priorHash, new Sha256Hasher().hash(newDest));
    }

    @Test
    void aCrashBetweenAFunnyMoveAndItsIndexRowIsReconciledOnResumeWithoutRecountingIt(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // photo was already moved to its destination by a prior, crashed run. The crash landed
        // between the move and the index row that would normally follow it.
        final Path photo = root.resolve("Sorted/Photos/2019/06/meme.jpg");
        final Path dest = libraryRoot.resolve("Funny/meme.jpg");
        writeFile(dest, "haha");
        writeMoveRecord(prepDir, photo, dest, new Sha256Hasher().hash(dest));
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        final ApplyReport report = applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        // The move was already done, so this run only backfills the missing row - it isn't counted
        // as this run's own work.
        assertThat(report.byCategory()).isEmpty();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(dest));
    }

    @Test
    void aCrashBetweenAFunnyMoveAndItsIndexRowIsReconciledEvenWhenAnotherFileSharesItsHash(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // Two byte-identical funny photos share one hash but live at different paths. The first is
        // already fully indexed; the second's move already happened too, but a crash left its own
        // index row missing. Checking "is this hash indexed at all" would wrongly see the first
        // entry and skip writing the second. The path has to match too, not just the hash.
        final Path photo = root.resolve("Sorted/Photos/2019/06/meme2.jpg");
        final Path existingDest = libraryRoot.resolve("Funny/meme1.jpg");
        final Path dest = libraryRoot.resolve("Funny/meme2.jpg");
        writeFile(existingDest, "identical bytes");
        writeFile(dest, "identical bytes");
        final String hash = new Sha256Hasher().hash(dest);
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        hashIndex.append(List.of(new IndexEntry(hash, existingDest)));
        writeMoveRecord(prepDir, photo, dest, hash);
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        assertThat(hashIndex.load().get(hash)).containsExactlyInAnyOrder(existingDest, dest);
    }

    @Test
    void aCrashBetweenAReviewMoveAndItsReasonsLineIsReconciledOnResumeWithoutRecountingIt(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // photo was already moved to its destination by a prior, crashed run. The crash landed
        // between the move and the _reasons.txt line that would normally follow it.
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "x");
        writeMoveRecord(prepDir, photo, dest, new Sha256Hasher().hash(dest));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt"))).containsExactly("a.jpg - blurry");
    }

    @Test
    void aCrashBetweenTwoFunnyDecisionsLeavesTheFirstOnesIndexRowDurableAndResumeFinishesTheSecond(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path first = root.resolve("Sorted/Photos/2019/06/first-meme.jpg");
        final Path second = root.resolve("Sorted/Photos/2019/06/second-meme.jpg");
        writeFile(first, "haha1");
        writeFile(second, "haha2");
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "funny", "first meme"),
                classificationJson(second, "funny", "second meme"));
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first decision's move but before the loop reaches the second.
        final ApplyEngine crashingEngine = applyEngine(root, libraryRoot, hashIndex, new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        final Path firstDest = libraryRoot.resolve("Funny/first-meme.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(firstDest));
        // The crash lands on the second decision's move itself, before it touches the filesystem
        // at all. Its source is untouched, so a resumed run needs no special-case recovery for it.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Funny/second-meme.jpg"))).isFalse();

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        final Path secondDest = libraryRoot.resolve("Funny/second-meme.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(
                new Sha256Hasher().hash(firstDest), new Sha256Hasher().hash(secondDest));
    }

    // The move record is written BEFORE the move it describes, never after. This is the one crash
    // timing that tells the two orderings apart. A crash landing between a completed move and the
    // next statement leaves the source gone either way. Only a record already on disk can prove
    // afterwards that the move genuinely happened. Recording after the move would leave that file
    // permanently unresolvable instead.
    @Test
    void aCrashLandingImmediatelyAfterAMoveIsStillProvableAsDoneOnResume(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "blurry");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        // Carries the move out for real, then throws before apply() can run the reasons-note write
        // that normally follows it.
        final ApplyEngine crashingEngine = applyEngine(root, libraryRoot, hashIndex(root),
                new FailingAfterMoves(0, CrashPoint.AFTER_THE_MOVE));

        assertThatThrownBy(() -> crashingEngine.apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        final Path dest = root.resolve("Review/junk/a.jpg");
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(dest)).isTrue();

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        // The resumed run proves the move from the record alone, since the source is long gone. It
        // backfills only the reasons line the crash cost, and counts no work of its own.
        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt"))).containsExactly("a.jpg - blurry");
    }

    @Test
    void aNearDupNoteBuiltOnResumeStillListsARejectAlreadyAppliedInAPriorRun(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // pending this run
        final Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg"); // already applied in a prior run
        writeFile(chosen, "sharp");
        // reject is never written to disk - simulates a prior run having already moved it away
        final Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        final Path rejectDest = dupDir.resolve("b.jpg");
        writeFile(rejectDest, "blurry");
        writeMoveRecord(prepDir, reject, rejectDest, new Sha256Hasher().hash(rejectDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.readString(dupDir.resolve("a.jpg.txt")))
                .contains("Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
    }

    @Test
    void resumingANearDupChosenDecisionAlreadyCopiedDoesNotDuplicateTheFileOrTheNote(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(chosen, "sharp");
        writeFile(reject, "blurry");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));
        // Simulates a prior run that copied the chosen file and wrote its note, then crashed before
        // this decision's next step. chosen's source is never removed by a copy, so the engine
        // always reprocesses it - it must converge on the same end state rather than compounding.
        final Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        writeFile(dupDir.resolve("a.jpg"), "already-copied");
        Files.writeString(dupDir.resolve("a.jpg.txt"),
                "Chose a.jpg - sharpest. Rejects: b.jpg - blurred" + System.lineSeparator());

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.exists(dupDir.resolve("a (2).jpg"))).isFalse();
        assertThat(Files.readString(dupDir.resolve("a.jpg"))).isEqualTo("already-copied");
        assertThat(Files.readAllLines(dupDir.resolve("a.jpg.txt"))).containsExactly(
                "Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
    }

    @Test
    void mergedDecisionsSummaryReflectsAllDecisionsIncludingOnesFromAPriorRun(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        final Path alreadyMovedDest = root.resolve("Review/junk/a.jpg");
        writeFile(alreadyMovedDest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "junk", "also blurry"));

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        final String json = Files.readString(prepDir.resolve("decisions.json")).replaceAll("\\s+", "");
        assertThat(json).contains("\"junk\":2");
    }

    @Test
    void anUnreviewableFileIsMovedToUnreviewableYearMonth(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        writeFile(undecodable, "not a real image");
        writeIndex(prepDir, 0, List.of(undecodable), List.of());

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.exists(undecodable)).isFalse();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/corrupt.heic"))).isTrue();
    }

    @Test
    void resumingRecognizesAnAlreadyMovedUnreviewableFileWithoutReprocessingIt(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        // alreadyMoved is never written to disk under Sorted at all. It stands in for a prior,
        // crashed run that already moved it before this run reads the prep dir.
        final Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        final Path dest = root.resolve("Unreviewable/2019/06/corrupt.heic");
        writeFile(dest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, dest, new Sha256Hasher().hash(dest));
        writeIndex(prepDir, 0, List.of(alreadyMoved), List.of());

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.readString(dest)).isEqualTo("already-moved");
    }

    @Test
    void aRunWithBothAPendingAndAnAlreadyDoneUnreviewableFileHandlesEachCorrectly(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path pending = root.resolve("Sorted/Photos/2019/06/pending.heic");
        final Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/already-moved.heic");
        writeFile(pending, "not a real image");
        final Path alreadyMovedDest = root.resolve("Unreviewable/2019/06/already-moved.heic");
        writeFile(alreadyMovedDest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 0, List.of(pending, alreadyMoved), List.of());

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(2);
        assertThat(Files.exists(pending)).isFalse();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/pending.heic"))).isTrue();
        assertThat(Files.readString(alreadyMovedDest)).isEqualTo("already-moved");
    }

    @Test
    void aMissingUnreviewableFileWithNoMoveRecordAbortsTheWholeRunEvenWhenOtherDecisionsArePending(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path missingUnreviewable = root.resolve("Sorted/Photos/2019/06/gone.heic"); // never written, no move
        // record
        final Path pending = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(pending, "x");
        writeIndex(prepDir, 1, List.of(missingUnreviewable), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(pending));
        writeShard(prepDir, "montage-001", classificationJson(pending, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified")
                .hasMessageContaining(missingUnreviewable.toString());
        // The whole run aborted before anything moved - the unrelated pending decision is untouched too.
        assertThat(Files.exists(pending)).isTrue();
    }

    @Test
    void writesMergedDecisionsAndCleansUpIntermediatesEvenWithZeroDecisions(@TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path keeper = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(keeper, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(keeper));
        writeShard(prepDir, "montage-001"); // an all-keeps montage still answers with an empty shard
        Files.writeString(prepDir.resolve("montage-001.jpg"), "fake-image");
        Files.writeString(prepDir.resolve("tile-001-01.jpg"), "fake-tile");

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.exists(keeper)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("index.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("montage-001.jpg"))).isFalse();
        assertThat(Files.exists(prepDir.resolve("tile-001-01.jpg"))).isFalse();
        // The sidecar shares montage-001's own filename prefix with its contact-sheet image. It is
        // JSON ground truth, not a deletable intermediate, so it survives cleanup for the prep dir's
        // whole life. The idempotency test below shows why that matters.
        assertThat(Files.exists(prepDir.resolve("montage-001.json"))).isTrue();
    }

    // Proves apply() is idempotent by actually calling it twice on the same prep dir. Every other
    // "resuming after a crash" test in this file simulates that state by hand-writing a move record
    // instead. A real second call needs montage-001's own sidecar to still be readable, which is
    // exactly what cleanupIntermediates() preserves. Idempotency itself needs no special-case code
    // here. It falls out of the same classification machinery those hand-simulated tests exercise.
    @Test
    void aSecondApplyOnAnAlreadyCompleteRunIsANoOpThatMovesNothingAndDoesNotDuplicateSecondaryWrites(
            @TempDir final Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path junk = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path meme = root.resolve("Sorted/Photos/2019/06/meme.jpg");
        writeFile(junk, "blurry");
        writeFile(meme, "haha");
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(junk), sidecarEntry(meme));
        writeShard(prepDir, "montage-001",
                classificationJson(junk, "junk", "blurry"),
                classificationJson(meme, "funny", "genuinely funny"));
        final ApplyEngine engine = applyEngine(root, libraryRoot, hashIndex);

        final ApplyReport first = engine.apply(prepDir, new ApplyOptions(false));
        final ApplyReport second = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(first.byCategory()).containsEntry("junk", 1).containsEntry("funny", 1);
        assertThat(second.byCategory()).isEmpty();
        final Path funnyDest = libraryRoot.resolve("Funny/meme.jpg");
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
        assertThat(Files.exists(funnyDest)).isTrue();
        assertThat(Files.readString(root.resolve("Review/junk/_reasons.txt")).lines().toList())
                .containsExactly("a.jpg - blurry");
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(funnyDest));
    }

    @Test
    void cancellationMidDecisionsLoopStopsEarlyAndSkipsBothFinalizers(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path first = root.resolve("Sorted/Photos/2019/06/first.jpg");
        final Path second = root.resolve("Sorted/Photos/2019/06/second.jpg");
        writeFile(first, "blurry1");
        writeFile(second, "blurry2");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "junk", "blurry"),
                classificationJson(second, "junk", "also blurry"));
        // Present so cleanupIntermediates() has something to (not) delete - proves it really never
        // runs on a cancelled pass, not just that decisions.json happens to be absent.
        Files.writeString(prepDir.resolve("montage-001.jpg"), "fake-image");
        Files.writeString(prepDir.resolve("tile-001-01.jpg"), "fake-tile");
        // Cancels once the first decision has ticked, so the loop stops before the second one is
        // even looked at.
        final AtomicInteger ticks = new AtomicInteger();
        final CancellationSignal cancelAfterFirstTick = () -> ticks.get() == 1;

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
                (current, _) -> ticks.set(current), cancelAfterFirstTick);

        assertThat(report).isNull();
        assertThat(Files.exists(first)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/first.jpg"))).isTrue();
        // The second decision was never reached.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(root.resolve("Review/junk/second.jpg"))).isFalse();
        // Both finalizers skipped: no merged decisions.json, and the intermediates survive.
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        assertThat(Files.exists(prepDir.resolve("montage-001.jpg"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("tile-001-01.jpg"))).isTrue();
    }

    @Test
    void cancellationBetweenTheDecisionsAndUnreviewableLoopsStopsBeforeTheUnreviewableFileMoves(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        writeFile(photo, "junk1");
        writeFile(undecodable, "not a real image");
        writeIndex(prepDir, 1, List.of(undecodable), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        // Cancels right after the one decision ticks, so the unreviewable-file loop right after it
        // never even starts.
        final AtomicInteger ticks = new AtomicInteger();
        final CancellationSignal cancelAfterFirstTick = () -> ticks.get() == 1;

        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
                (current, _) -> ticks.set(current), cancelAfterFirstTick);

        assertThat(report).isNull();
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
        // The unreviewable file was never reached.
        assertThat(Files.exists(undecodable)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
    }

    @Test
    void progressCallbackTicksOnceForEachDecisionAndOnceForEachUnreviewableFile(@TempDir final Path root)
            throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        writeFile(photo, "junk1");
        writeFile(undecodable, "not a real image");
        writeIndex(prepDir, 1, List.of(undecodable), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final List<String> ticks = new ArrayList<>();
        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
                (current, total) -> ticks.add(current + "/" + total));

        // One decision plus one unreviewable file, both real moves. The total must cover both
        // loops, not just the decisions loop, or progress would reach 100% before the unreviewable
        // file is actually moved.
        assertThat(ticks).containsExactly("1/2", "2/2");
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/corrupt.heic"))).isTrue();
    }

    // Where the simulated crash lands relative to the move that triggers it. BEFORE_THE_MOVE leaves
    // the source untouched, the timing a resumed run needs no recovery for at all. AFTER_THE_MOVE
    // carries the move out first, which is the timing only a move record written beforehand can
    // explain afterwards.
    private enum CrashPoint {
        BEFORE_THE_MOVE, AFTER_THE_MOVE
    }

    // Wraps the real NioMediaStore but throws around a chosen moveTo() call - recordThenMove()'s own
    // move step. That's where a real process crash would land partway through a single apply()
    // invocation. Deterministically simulates that crash timing. No amount of pre-seeded state can
    // reproduce it: pre-seeding only proves the engine tolerates ALREADY-crashed state, not that a
    // crash mid-run leaves the right things durable.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CrashPoint crashPoint;
        private int movesUntilFailure;

        FailingAfterMoves(final int movesUntilFailure) {
            this(movesUntilFailure, CrashPoint.BEFORE_THE_MOVE);
        }

        FailingAfterMoves(final int movesUntilFailure, final CrashPoint crashPoint) {
            this.movesUntilFailure = movesUntilFailure;
            this.crashPoint = crashPoint;
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            return this.delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            if (this.movesUntilFailure <= 0) {
                if (this.crashPoint == CrashPoint.AFTER_THE_MOVE) {
                    this.delegate.moveTo(source, destination);
                }
                throw new RuntimeException("simulated crash");
            }
            this.movesUntilFailure--;
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }
}
