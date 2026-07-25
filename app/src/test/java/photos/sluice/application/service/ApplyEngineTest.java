package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.model.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyEngineTest {

    @Test
    void aClassificationDecisionMovesTheFileToReviewCategoryAndWritesAReasonsNote(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/IMG_1.jpg");
        writeFile(photo, "junk");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "phone photo of a monitor"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/junk/_reasons.txt")))
                .contains("IMG_1.jpg - phone photo of a monitor");
    }

    @Test
    void aFunnyClassificationMovesToLibraryFunnyAndAppendsAnIndexRowWithNoReasonsNote(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/meme.jpg");
        writeFile(photo, "haha");
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        ApplyReport report = applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("funny", 1);
        Path dest = libraryRoot.resolve("Funny/meme.jpg");
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Funny/_reasons.txt"))).isFalse();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(dest));
    }

    @Test
    void nearDupChosenIsCopiedRejectIsMovedAndAChosenNoteListsAllRejects(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(chosen, "sharp");
        writeFile(reject, "blurry");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.nearDupGroups()).isEqualTo(1);
        assertThat(report.nearDupRejects()).isEqualTo(1);
        assertThat(Files.exists(chosen)).isTrue();
        assertThat(Files.exists(reject)).isFalse();
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        assertThat(Files.exists(dupDir.resolve("a.jpg"))).isTrue();
        assertThat(Files.exists(dupDir.resolve("b.jpg"))).isTrue();
        assertThat(Files.readString(dupDir.resolve("a.jpg.txt")))
                .contains("Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
    }

    @Test
    void aMissingShardWithoutAllowPartialFailsLoudlyAndMovesNothing(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("montage-001: no shard");
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void aMissingShardWithAllowPartialLeavesThatMontagesPhotosInPlace(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path untouched = root.resolve("Sorted/Photos/2019/06/untouched.jpg");
        Path junk = root.resolve("Sorted/Photos/2019/06/junk.jpg");
        writeFile(untouched, "x");
        writeFile(junk, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(untouched));
        writeSidecar(prepDir, "montage-002", sidecarEntry(junk));
        writeShard(prepDir, "montage-002", classificationJson(junk, "junk", "blurry"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(true));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(untouched)).isTrue();
        assertThat(Files.exists(root.resolve("Review/junk/junk.jpg"))).isTrue();
    }

    @Test
    void anOffContractDecisionFailsLoudlyAndMovesNothing(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
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
    void aFileListedBothAsADecisionAndAsUnreviewableFailsValidationAndMovesNothing(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file listed 2 times across shards/unreviewable: " + photo);
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void aDecisionsFileWithNoMatchingMontageFailsLoudly(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("decisions-002.json: no matching montage");
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void autoHealsARetypedPathViaUniqueSidecarBasenameAndSurfacesItAsAHeal(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path actual = root.resolve("Sorted/Photos/2019/08/a.jpg");
        Path retyped = root.resolve("Sorted/Photos/2019/09/a.jpg"); // culler wrote the wrong month segment
        writeFile(actual, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(actual));
        writeShard(prepDir, "montage-001", classificationJson(retyped, "junk", "blurry"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.heals()).hasSize(1);
        assertThat(Files.exists(actual)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
    }

    @Test
    void resumingAfterASimulatedCrashSkipsAlreadyAppliedFilesAndAppliesTheRest(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // alreadyMoved is never written to disk under Sorted at all - standing in for a decision a
        // prior, crashed run already carried out before dying. Its destination, reasons line, and
        // move record are all pre-placed exactly as a completed run would have left them.
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        Path alreadyMovedDest = root.resolve("Review/junk/a.jpg");
        writeFile(alreadyMovedDest, "already-moved");
        Files.writeString(root.resolve("Review/junk/_reasons.txt"), "a.jpg - blurry" + System.lineSeparator());
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "scenery", "weak composition"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("scenery", 1).doesNotContainKey("junk");
        assertThat(Files.exists(root.resolve("Review/scenery/b.jpg"))).isTrue();
        // The already-done decision was recognized, not reprocessed - its reasons line still
        // appears exactly once.
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt")))
                .containsExactly("a.jpg - blurry");
    }

    @Test
    void resumingPreservesAnAlreadyAppliedFunnyDecisionsIndexEntryAndIndexesThePendingOne(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // alreadyMoved's file was already relocated (and, per this fix, already indexed) by a prior
        // run before it crashed - simulated directly, rather than by actually crashing mid-run.
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/old-meme.jpg");
        Path pending = root.resolve("Sorted/Photos/2019/06/new-meme.jpg");
        writeFile(pending, "haha");
        Path priorDest = libraryRoot.resolve("Funny/old-meme.jpg");
        writeFile(priorDest, "already-there");
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        String priorHash = new Sha256Hasher().hash(priorDest);
        hashIndex.append(List.of(new IndexEntry(priorHash, priorDest)));
        writeMoveRecord(prepDir, alreadyMoved, priorDest, priorHash);
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "funny", "old meme"),
                classificationJson(pending, "funny", "new meme"));

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        Path newDest = libraryRoot.resolve("Funny/new-meme.jpg");
        assertThat(Files.exists(newDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(priorHash, new Sha256Hasher().hash(newDest));
    }

    @Test
    void aCrashBetweenAFunnyMoveAndItsIndexRowIsReconciledOnResumeWithoutRecountingIt(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // photo was already moved to its destination by a prior, crashed run - but the crash landed
        // between the move and the index row that would normally follow it.
        Path photo = root.resolve("Sorted/Photos/2019/06/meme.jpg");
        Path dest = libraryRoot.resolve("Funny/meme.jpg");
        writeFile(dest, "haha");
        writeMoveRecord(prepDir, photo, dest, new Sha256Hasher().hash(dest));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        ApplyReport report = applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        // The move was already done, so this run only backfills the missing row - it isn't counted
        // as this run's own work.
        assertThat(report.byCategory()).isEmpty();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(dest));
    }

    @Test
    void aCrashBetweenAFunnyMoveAndItsIndexRowIsReconciledEvenWhenAnotherFileSharesItsHash(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // Two byte-identical funny photos share one hash but live at different paths. The first is
        // already fully indexed; the second's move already happened too, but a crash left its own
        // index row missing. Checking "is this hash indexed at all" would wrongly see the first
        // entry and skip writing the second - the path has to match too, not just the hash.
        Path photo = root.resolve("Sorted/Photos/2019/06/meme2.jpg");
        Path existingDest = libraryRoot.resolve("Funny/meme1.jpg");
        Path dest = libraryRoot.resolve("Funny/meme2.jpg");
        writeFile(existingDest, "identical bytes");
        writeFile(dest, "identical bytes");
        String hash = new Sha256Hasher().hash(dest);
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        hashIndex.append(List.of(new IndexEntry(hash, existingDest)));
        writeMoveRecord(prepDir, photo, dest, hash);
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        assertThat(hashIndex.load().get(hash)).containsExactlyInAnyOrder(existingDest, dest);
    }

    @Test
    void aCrashBetweenAReviewMoveAndItsReasonsLineIsReconciledOnResumeWithoutRecountingIt(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // photo was already moved to its destination by a prior, crashed run - but the crash landed
        // between the move and the _reasons.txt line that would normally follow it.
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "x");
        writeMoveRecord(prepDir, photo, dest, new Sha256Hasher().hash(dest));
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt"))).containsExactly("a.jpg - blurry");
    }

    @Test
    void aCrashBetweenTwoFunnyDecisionsLeavesTheFirstOnesIndexRowDurableAndResumeFinishesTheSecond(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path first = root.resolve("Sorted/Photos/2019/06/first-meme.jpg");
        Path second = root.resolve("Sorted/Photos/2019/06/second-meme.jpg");
        writeFile(first, "haha1");
        writeFile(second, "haha2");
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first), sidecarEntry(second));
        writeShard(prepDir, "montage-001",
                classificationJson(first, "funny", "first meme"),
                classificationJson(second, "funny", "second meme"));
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first decision's move but before the loop reaches the second.
        ApplyEngine crashingEngine = applyEngine(root, libraryRoot, hashIndex, new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        Path firstDest = libraryRoot.resolve("Funny/first-meme.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(firstDest));
        // The crash lands on the second decision's move itself, before it touches the filesystem at
        // all - its source is untouched, so a resumed run needs no special-case recovery for it.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Funny/second-meme.jpg"))).isFalse();

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        Path secondDest = libraryRoot.resolve("Funny/second-meme.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(
                new Sha256Hasher().hash(firstDest), new Sha256Hasher().hash(secondDest));
    }

    @Test
    void aNearDupNoteBuiltOnResumeStillListsARejectAlreadyAppliedInAPriorRun(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // pending this run
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg"); // already applied in a prior run
        writeFile(chosen, "sharp");
        // reject is never written to disk - simulates a prior run having already moved it away
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        Path rejectDest = dupDir.resolve("b.jpg");
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
    void resumingANearDupChosenDecisionAlreadyCopiedDoesNotDuplicateTheFileOrTheNote(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
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
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        writeFile(dupDir.resolve("a.jpg"), "already-copied");
        Files.writeString(dupDir.resolve("a.jpg.txt"), "Chose a.jpg - sharpest. Rejects: b.jpg - blurred" + System.lineSeparator());

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.exists(dupDir.resolve("a (2).jpg"))).isFalse();
        assertThat(Files.readString(dupDir.resolve("a.jpg"))).isEqualTo("already-copied");
        assertThat(Files.readAllLines(dupDir.resolve("a.jpg.txt"))).containsExactly(
                "Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
    }

    @Test
    void mergedDecisionsSummaryReflectsAllDecisionsIncludingOnesFromAPriorRun(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        Path alreadyMovedDest = root.resolve("Review/junk/a.jpg");
        writeFile(alreadyMovedDest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "junk", "also blurry"));

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        String json = Files.readString(prepDir.resolve("decisions.json")).replaceAll("\\s+", "");
        assertThat(json).contains("\"junk\":2");
    }

    @Test
    void anUnreviewableFileIsMovedToUnreviewableYearMonth(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        writeFile(undecodable, "not a real image");
        writeIndex(prepDir, 0, List.of(undecodable), List.of());

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.exists(undecodable)).isFalse();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/corrupt.heic"))).isTrue();
    }

    @Test
    void resumingRecognizesAnAlreadyMovedUnreviewableFileWithoutReprocessingIt(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        // alreadyMoved is never written to disk under Sorted at all - standing in for a prior,
        // crashed run that already moved it before this run reads the prep dir.
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        Path dest = root.resolve("Unreviewable/2019/06/corrupt.heic");
        writeFile(dest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, dest, new Sha256Hasher().hash(dest));
        writeIndex(prepDir, 0, List.of(alreadyMoved), List.of());

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.readString(dest)).isEqualTo("already-moved");
    }

    @Test
    void aRunWithBothAPendingAndAnAlreadyDoneUnreviewableFileHandlesEachCorrectly(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path pending = root.resolve("Sorted/Photos/2019/06/pending.heic");
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/already-moved.heic");
        writeFile(pending, "not a real image");
        Path alreadyMovedDest = root.resolve("Unreviewable/2019/06/already-moved.heic");
        writeFile(alreadyMovedDest, "already-moved");
        writeMoveRecord(prepDir, alreadyMoved, alreadyMovedDest, new Sha256Hasher().hash(alreadyMovedDest));
        writeIndex(prepDir, 0, List.of(pending, alreadyMoved), List.of());

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(2);
        assertThat(Files.exists(pending)).isFalse();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/pending.heic"))).isTrue();
        assertThat(Files.readString(alreadyMovedDest)).isEqualTo("already-moved");
    }

    @Test
    void aMissingUnreviewableFileWithNoMoveRecordAbortsTheWholeRunEvenWhenOtherDecisionsArePending(@TempDir Path root)
            throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path missingUnreviewable = root.resolve("Sorted/Photos/2019/06/gone.heic"); // never written, no move record
        Path pending = root.resolve("Sorted/Photos/2019/06/a.jpg");
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
    void writesMergedDecisionsAndCleansUpIntermediatesEvenWithZeroDecisions(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path keeper = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(keeper, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(keeper));
        writeShard(prepDir, "montage-001"); // an all-keeps montage still answers with an empty shard
        Files.writeString(prepDir.resolve("montage-001.jpg"), "fake-image");
        Files.writeString(prepDir.resolve("tile-001-01.jpg"), "fake-tile");

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.exists(keeper)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("index.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("montage-001.jpg"))).isFalse();
        assertThat(Files.exists(prepDir.resolve("montage-001.json"))).isFalse();
        assertThat(Files.exists(prepDir.resolve("tile-001-01.jpg"))).isFalse();
    }

    @Test
    void aMissingFileWithNoMoveRecordFailsLoudlyAndRefusesToGuess(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk, no move record either
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified")
                .hasMessageContaining(prepDir.resolve("move-records.log").toString());
    }

    @Test
    void aMissingNearDupChosenFileFailsLoudlyEvenThoughItsNeverAMoveBasedDecision(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk - a copy that never ran
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
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

    @Test
    void aMoveRecordWhoseDestinationIsMissingStillFailsLoudlyRatherThanTrustingTheRecordAlone(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
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
    void aMoveRecordWhoseDestinationContentNoLongerMatchesStillFailsLoudly(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
        Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "content changed after the record was written");
        // A hash that deliberately doesn't match dest's actual content, standing in for the
        // destination having been altered (or a different file landing there) after the record for
        // this decision was written.
        writeMoveRecord(prepDir, photo, dest, "not-a-real-hash-value");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found, and its move could not be verified");
    }

    @Test
    void progressCallbackTicksOnceForEachDecisionAndOnceForEachUnreviewableFile(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path undecodable = root.resolve("Sorted/Photos/2019/06/corrupt.heic");
        writeFile(photo, "junk1");
        writeFile(undecodable, "not a real image");
        writeIndex(prepDir, 1, List.of(undecodable), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        List<String> ticks = new ArrayList<>();
        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
                (current, total) -> ticks.add(current + "/" + total));

        // One decision plus one unreviewable file, both real moves - the total must cover both
        // loops, not just the decisions loop, or progress would reach 100% before the unreviewable
        // file is actually moved.
        assertThat(ticks).containsExactly("1/2", "2/2");
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/corrupt.heic"))).isTrue();
    }

    private static Path prepDir(Path root) throws IOException {
        Path dir = root.resolve("logs/cull-prep/scope1");
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeIndex(Path prepDir, int photos, List<String> entries) {
        writeIndex(prepDir, photos, List.of(), entries);
    }

    private static void writeIndex(Path prepDir, int photos, List<Path> unreviewable, List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", prepDir.resolve("base"), photos, unreviewable, entries.size(), prepDir, entries));
    }

    private static void writeSidecar(Path prepDir, String montage, SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"), List.of(photos));
    }

    private static SidecarPhotoEntry sidecarEntry(Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
    }

    // Matches ApplyEngine's own RECORD_DELIMITER exactly - a control character absent from any real
    // path, so it can be split back apart with no escaping.
    private static final String RECORD_DELIMITER = "\u001F";

    // Simulates a move-record line an earlier, crashed run would have written before its move -
    // pairs with a hand-placed destination file standing in for that move having actually happened.
    private static void writeMoveRecord(Path prepDir, Path source, Path dest, String hash) throws IOException {
        Files.writeString(prepDir.resolve("move-records.log"),
                source + RECORD_DELIMITER + dest + RECORD_DELIMITER + hash + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void writeShard(Path prepDir, String montage, String... decisionsJson) throws IOException {
        String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    private static String classificationJson(Path file, String category, String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }".formatted(jsonEscaped(file), category, reason);
    }

    private static String nearDupChosenJson(Path file, String group, String chosenReason) {
        return "{ \"file\": \"%s\", \"action\": \"near-dup-chosen\", \"group\": \"%s\", \"chosen_reason\": \"%s\" }"
                .formatted(jsonEscaped(file), group, chosenReason);
    }

    private static String nearDupRejectJson(Path file, String group, String reason) {
        return "{ \"file\": \"%s\", \"action\": \"near-dup-reject\", \"group\": \"%s\", \"reason\": \"%s\" }"
                .formatted(jsonEscaped(file), group, reason);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ApplyEngine applyEngine(Path repoRoot, Path libraryRoot) {
        return applyEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv")));
    }

    private static ApplyEngine applyEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex) {
        return applyEngine(repoRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    private static ApplyEngine applyEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex, MediaStore mediaStore) {
        return new ApplyEngine(pathsConfig(repoRoot, libraryRoot), mediaStore, new JsonCullPrepStore(), fixedSettings(),
                new Sha256Hasher(), hashIndex);
    }

    private static PathsConfig pathsConfig(Path repoRoot, Path libraryRoot) {
        return new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
    }

    private static CullSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                new CullCategory("junk", "junk description"),
                new CullCategory("scenery", "scenery description"),
                new CullCategory("food", "food description"),
                new CullCategory("funny", "funny description")));
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful moveTo() calls -
    // recordThenMove()'s own move step. That's where a real process crash would land partway through
    // a single apply() invocation. Deterministically simulates that crash timing. No amount of
    // pre-seeded state can reproduce it: pre-seeding only proves the engine tolerates ALREADY-crashed
    // state, not that a crash mid-run leaves the right things durable.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private int movesUntilFailure;

        FailingAfterMoves(int movesUntilFailure) {
            this.movesUntilFailure = movesUntilFailure;
        }

        @Override
        public Path move(Path source, Path destDir) {
            return delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            if (movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            movesUntilFailure--;
            return delegate.moveTo(source, destination);
        }

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
        }
    }
}
