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
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Finding.MissingShard;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ReconcileReport;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.model.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
                .hasMessageContaining("montage-001: no shard")
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings())
                        .containsExactly(new MissingShard("montage-001", "decisions-001.json")));
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
                .hasMessageContaining("file listed both as a decision and as unreviewable: " + photo);
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
                .hasMessageContaining("decisions-002.json: no matching montage")
                .isInstanceOfSatisfying(ApplyException.class, e ->
                        assertThat(e.findings()).containsExactly(new StrayShard("decisions-002.json")));
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
        // photo was already moved to its destination by a prior, crashed run. The crash landed
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
        // entry and skip writing the second. The path has to match too, not just the hash.
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
        // photo was already moved to its destination by a prior, crashed run. The crash landed
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
        // The crash lands on the second decision's move itself, before it touches the filesystem
        // at all. Its source is untouched, so a resumed run needs no special-case recovery for it.
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
        // alreadyMoved is never written to disk under Sorted at all. It stands in for a prior,
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
                .hasMessageContaining(prepDir.resolve("move-records.log").toString())
                .isInstanceOfSatisfying(ApplyException.class, e -> assertThat(e.findings())
                        .containsExactly(new MissingSource(photo, prepDir.resolve("move-records.log"))));
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
    void cancellationMidDecisionsLoopStopsEarlyAndSkipsBothFinalizers(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path first = root.resolve("Sorted/Photos/2019/06/first.jpg");
        Path second = root.resolve("Sorted/Photos/2019/06/second.jpg");
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
        AtomicInteger ticks = new AtomicInteger();
        CancellationSignal cancelAfterFirstTick = () -> ticks.get() == 1;

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
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
    void cancellationBetweenTheDecisionsAndUnreviewableLoopsStopsBeforeTheUnreviewableFileMoves(@TempDir Path root)
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
        // Cancels right after the one decision ticks, so the unreviewable-file loop right after it
        // never even starts.
        AtomicInteger ticks = new AtomicInteger();
        CancellationSignal cancelAfterFirstTick = () -> ticks.get() == 1;

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false),
                (current, _) -> ticks.set(current), cancelAfterFirstTick);

        assertThat(report).isNull();
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
        // The unreviewable file was never reached.
        assertThat(Files.exists(undecodable)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
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

        // One decision plus one unreviewable file, both real moves. The total must cover both
        // loops, not just the decisions loop, or progress would reach 100% before the unreviewable
        // file is actually moved.
        assertThat(ticks).containsExactly("1/2", "2/2");
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/corrupt.heic"))).isTrue();
    }

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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
        // reconcile() must never treat this as proof: a NearDupChosen's source is never removed by a
        // real run, so a missing one can only mean the photo is genuinely gone.
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        writeFile(dupDir.resolve("a.jpg"), "looks-like-a-copy");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        ReconcileReport report = applyEngine(root, libraryRoot).reconcile(prepDir);

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

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).reconcile(prepDir))
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
        applyEngine(root, libraryRoot).reconcile(prepDir);

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        // The reconciled decision is recognized as already done, not reprocessed. Only the pending
        // one counts as this run's own work. Its reasons line is still backfilled, though, since
        // reconcile() never writes one itself.
        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.readAllLines(root.resolve("Review/junk/_reasons.txt")))
                .containsExactlyInAnyOrder("a.jpg - blurry", "b.jpg - also blurry");
    }

    @Test
    void skipMissingSourceRecordsATerminalDispositionSoApplySucceedsWithoutMovingOrWritingAnything(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path gone = root.resolve("Sorted/Photos/2019/06/gone.jpg"); // never written, no move record either
        Path pending = root.resolve("Sorted/Photos/2019/06/pending.jpg");
        writeFile(pending, "y");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(gone), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(gone, "junk", "blurry"),
                classificationJson(pending, "scenery", "weak composition"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.skipMissingSource(prepDir, gone, "confirmed permanently deleted by the user");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("scenery", 1).doesNotContainKey("junk");
        assertThat(Files.exists(root.resolve("Review/junk"))).isFalse();
        assertThat(Files.exists(root.resolve("Review/scenery/pending.jpg"))).isTrue();
    }

    @Test
    void skipMissingSourceResolvesAMissingUnreviewableFileWithoutMovingAnything(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path gone = root.resolve("Sorted/Photos/2019/06/gone.heic"); // never written, no move record either
        writeIndex(prepDir, 0, List.of(gone), List.of());
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.skipMissingSource(prepDir, gone, "confirmed permanently deleted by the user");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Unreviewable"))).isFalse();
    }

    // classify() checks the disposition ledger's Skipped status before its NearDupChosen-shaped copy
    // exception (design doc: apply-engine.md section 3/7) - a skip must win even for a decision type
    // that would otherwise always be Unresolved once its source is missing.
    @Test
    void skipMissingSourceWinsOverANearDupChosenDecisionThatWouldOtherwiseAlwaysBeUnresolved(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - the photo itself is gone
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(reject, "blurry");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.skipMissingSource(prepDir, chosen, "confirmed the chosen photo itself is gone");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.nearDupGroups()).isZero();
        assertThat(Files.exists(reject)).isFalse();
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        assertThat(Files.exists(dupDir.resolve("a.jpg"))).isFalse();
        assertThat(Files.exists(dupDir.resolve("b.jpg"))).isTrue();
    }

    @Test
    void resolveOverlapTrustDecisionAppliesTheDecisionAndDropsTheFileFromUnreviewable(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.resolveOverlap(prepDir, photo, OverlapResolution.TRUST_DECISION, "the decision is correct");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(report.unreviewable()).isZero();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Unreviewable"))).isFalse();
    }

    @Test
    void resolveOverlapTreatAsUnreviewableAppliesTheFileAsUnreviewableAndDropsTheDecision(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of(photo), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.resolveOverlap(prepDir, photo, OverlapResolution.TREAT_AS_UNREVIEWABLE, "the file wasn't actually reviewed");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).doesNotContainKey("junk");
        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Review/junk"))).isFalse();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06/a.jpg"))).isTrue();
    }

    @Test
    void autoRepairStrayShardReturnsEmptyWhenMoreThanOneMontageIsUnclaimed(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path second = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(first, "x");
        writeFile(second, "y");
        // Both montage-001 and montage-002 lack a shard - the stray below cannot be assigned
        // unambiguously to either one.
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(first));
        writeSidecar(prepDir, "montage-002", sidecarEntry(second));
        writeShard(prepDir, "montage-003", classificationJson(first, "junk", "blurry"));

        Optional<String> repaired = applyEngine(root, libraryRoot)
                .autoRepairStrayShard(prepDir, new StrayShard("decisions-003.json"));

        assertThat(repaired).isEmpty();
        assertThat(Files.exists(prepDir.resolve("decisions-003.json"))).isTrue();
    }

    @Test
    void setAsideStrayShardFilesItIntoTheDisasterDrawerWithoutDeletingIt(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        writeIndex(prepDir, 0, List.of());
        writeShard(prepDir, "montage-001", classificationJson(root.resolve("Sorted/Photos/2019/06/a.jpg"), "junk", "blurry"));

        Path filed = applyEngine(root, libraryRoot).setAsideStrayShard(prepDir, new StrayShard("decisions-001.json"));

        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isFalse();
        assertThat(Files.exists(filed)).isTrue();
        assertThat(filed.getFileName().toString()).contains("stray-shard");
    }

    @Test
    void rebuildIndexRebuildsFromContiguousSidecarsAndFilesTheCorruptOriginal(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path second = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeSidecar(prepDir, "montage-001", sidecarEntry(first));
        writeSidecar(prepDir, "montage-002", sidecarEntry(second));
        Files.writeString(prepDir.resolve("index.json"), "not valid json");

        Optional<PrepDir> rebuilt = applyEngine(root, root.resolve("Library")).rebuildIndex(prepDir);

        assertThat(rebuilt).isPresent();
        assertThat(rebuilt.get().entries()).containsExactly("montage-001", "montage-002");
        assertThat(rebuilt.get().photos()).isEqualTo(2);
        assertThat(rebuilt.get().unreviewable()).isEmpty();
        assertThat(rebuilt.get().scope()).isEqualTo("scope1");
        assertThat(rebuilt.get().basePath()).isEqualTo(root.resolve("Sorted/Photos/2019/06"));
        // Persisted, not just returned - a later read sees the rebuilt content.
        assertThat(readIndex(prepDir).entries()).containsExactly("montage-001", "montage-002");
        Path drawer = prepDir.resolve("disasters");
        try (var entries = Files.list(drawer)) {
            assertThat(entries.toList().getFirst().getFileName().toString()).contains("index-json");
        }
    }

    @Test
    void rebuildIndexRefusesWhenTheSidecarSequenceHasAGap(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        writeSidecar(prepDir, "montage-001", sidecarEntry(root.resolve("Sorted/Photos/2019/06/a.jpg")));
        writeSidecar(prepDir, "montage-003", sidecarEntry(root.resolve("Sorted/Photos/2019/06/c.jpg"))); // montage-002 missing

        Optional<PrepDir> rebuilt = applyEngine(root, root.resolve("Library")).rebuildIndex(prepDir);

        assertThat(rebuilt).isEmpty();
    }

    @Test
    void rebuildIndexRefusesWhenAnyContiguousSidecarIsUnparseable(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        writeSidecar(prepDir, "montage-001", sidecarEntry(root.resolve("Sorted/Photos/2019/06/a.jpg")));
        Files.writeString(prepDir.resolve("montage-002.json"), "not valid json");

        Optional<PrepDir> rebuilt = applyEngine(root, root.resolve("Library")).rebuildIndex(prepDir);

        assertThat(rebuilt).isEmpty();
    }

    @Test
    void validateReportsCorruptSidecarForAMontageWithAShardButNoReadableSidecar(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        // No sidecar written for montage-001 at all - stands in for a missing or corrupt one; both
        // fail the same way (readSidecar() throws UncheckedIOException either way).
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        ValidationReport report = applyEngine(root, root.resolve("Library"))
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true));

        assertThat(report.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
        assertThat(report.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(report.decisions()).isEmpty();
    }

    @Test
    void validateSilentlySkipsACorruptSidecarForAMontageWithNoShardYet(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has no sidecar and no shard yet - still being culled, not yet actionable.

        ValidationReport report = applyEngine(root, root.resolve("Library"))
                .validate(prepDir, readIndex(prepDir), new ApplyOptions(true));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void resolveCorruptSidecarSetAsideExcludesTheMontageEntirelyLeavingItsPhotoInSorted(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.SET_ASIDE, "redo this batch later");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).isEmpty();
        assertThat(Files.exists(photo)).isTrue();
    }

    @Test
    void resolveCorruptSidecarApplyAnywayTrustsTheShardsOwnDecisionsWithoutAMembershipCheck(@TempDir Path root)
            throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        // No sidecar ever backs this decision's file - a healthy run would report it FileOutOfScope.
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        ApplyEngine engine = applyEngine(root, libraryRoot);

        engine.resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.APPLY_ANYWAY, "trust the culler's own shard");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
    }

    @Test
    void resolveCorruptSidecarFilesTheSidecarFileIntoTheDisasterDrawerWhenStillPresent(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));
        Files.writeString(prepDir.resolve("montage-001.json"), "not valid json"); // present, but corrupt

        applyEngine(root, root.resolve("Library"))
                .resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.SET_ASIDE, "give up on this batch");

        assertThat(Files.exists(prepDir.resolve("montage-001.json"))).isFalse();
        Path drawer = prepDir.resolve("disasters");
        try (var entries = Files.list(drawer)) {
            List<Path> filed = entries.toList();
            assertThat(filed).hasSize(1);
            assertThat(filed.getFirst().getFileName().toString()).contains("corrupt-sidecar-montage-001");
        }
    }

    @Test
    void discardMovesTextArtifactsToAGlobalGraveyardAndDeletesOnlyMontageImages(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeFile(prepDir.resolve("montage-001.jpg"), "fake contact sheet");
        writeFile(prepDir.resolve("tile-001-01.jpg"), "fake tile");
        Files.createDirectories(prepDir.resolve("disasters"));
        Files.writeString(prepDir.resolve("disasters/2026-01-01_00-00-00-something.txt"), "old drawer entry");

        Path graveyard = applyEngine(root, root.resolve("Library")).discard(prepDir);

        assertThat(graveyard.getParent()).isEqualTo(root.resolve("logs/disasters"));
        assertThat(graveyard.getFileName().toString()).startsWith("scope1-");
        assertThat(Files.exists(graveyard.resolve("index.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("montage-001.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("disasters/2026-01-01_00-00-00-something.txt"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("montage-001.jpg"))).isFalse();
        assertThat(Files.exists(graveyard.resolve("tile-001-01.jpg"))).isFalse();
        assertThat(Files.exists(prepDir)).isFalse();
    }

    private static PrepDir readIndex(Path prepDir) {
        return new JsonCullPrepStore().readIndex(prepDir);
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

    // Simulates a move-record line an earlier, crashed run would have written before its move.
    // Pairs with a hand-placed destination file standing in for that move having actually
    // happened.
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
                new Sha256Hasher(), hashIndex, new DisasterDrawer(mediaStore));
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

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL, null);
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
