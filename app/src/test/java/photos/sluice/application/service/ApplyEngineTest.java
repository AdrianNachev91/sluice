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
import java.time.Instant;
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
        // alreadyMoved is never written to disk at all - standing in for a decision a prior,
        // crashed run already carried out before dying.
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "scenery", "weak composition"));
        Files.writeString(prepDir.resolve("applied.log"), alreadyMoved + System.lineSeparator());

        ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("scenery", 1).doesNotContainKey("junk");
        assertThat(Files.exists(root.resolve("Review/scenery/b.jpg"))).isTrue();
        assertThat(Files.readString(prepDir.resolve("applied.log")))
                .contains(alreadyMoved.toString())
                .contains(pending.toString());
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
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "funny", "old meme"),
                classificationJson(pending, "funny", "new meme"));
        Files.writeString(prepDir.resolve("applied.log"), alreadyMoved + System.lineSeparator());

        applyEngine(root, libraryRoot, hashIndex).apply(prepDir, new ApplyOptions(false));

        Path newDest = libraryRoot.resolve("Funny/new-meme.jpg");
        assertThat(Files.exists(newDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(priorHash, new Sha256Hasher().hash(newDest));
    }

    @Test
    void aCrashBetweenTwoFunnyDecisionsLeavesTheFirstOnesIndexRowAlreadyDurable(@TempDir Path root) throws IOException {
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
        // the first decision is carried out but before the loop reaches the second.
        ApplyEngine engine = applyEngine(root, libraryRoot, hashIndex, new FailingAfterMoves(1));

        assertThatThrownBy(() -> engine.apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        Path firstDest = libraryRoot.resolve("Funny/first-meme.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(new Sha256Hasher().hash(firstDest));
        assertThat(Files.exists(libraryRoot.resolve("Funny/second-meme.jpg"))).isFalse();
        // The crash lands on the SECOND decision's move. By then the first one's move, index write,
        // and applied.log line have all already completed - a cleanly resumable state, with nothing
        // half-done for either decision.
        assertThat(Files.readString(prepDir.resolve("applied.log")))
                .contains(first.toString())
                .doesNotContain(second.toString());
    }

    @Test
    void aNearDupNoteBuiltOnResumeStillListsARejectAlreadyAppliedInAPriorRun(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg"); // pending this run
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg"); // already applied in a prior run
        writeFile(chosen, "sharp");
        // reject is never written to disk - simulates a prior run having already moved it away
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));
        Files.writeString(prepDir.resolve("applied.log"), reject + System.lineSeparator());

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
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
        // reaching applied.log's append for this decision. chosen's source is never removed by a
        // copy, so it's still not in applied.log either. The engine would otherwise treat this
        // decision as untouched and reprocess it.
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        writeFile(dupDir.resolve("a.jpg"), "already-copied");
        Files.writeString(dupDir.resolve("a.jpg.txt"), "Chose a.jpg - sharpest. Rejects: b.jpg - blurred" + System.lineSeparator());

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(Files.exists(dupDir.resolve("a (2).jpg"))).isFalse();
        assertThat(Files.readString(dupDir.resolve("a.jpg"))).isEqualTo("already-copied");
        assertThat(Files.readAllLines(dupDir.resolve("a.jpg.txt"))).containsExactly(
                "Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
        assertThat(Files.readString(prepDir.resolve("applied.log"))).contains(chosen.toString());
    }

    @Test
    void mergedDecisionsSummaryReflectsAllDecisionsIncludingOnesFromAPriorRun(@TempDir Path root) throws IOException, ApplyException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path alreadyMoved = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path pending = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(pending, "y");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(alreadyMoved), sidecarEntry(pending));
        writeShard(prepDir, "montage-001",
                classificationJson(alreadyMoved, "junk", "blurry"),
                classificationJson(pending, "junk", "also blurry"));
        Files.writeString(prepDir.resolve("applied.log"), alreadyMoved + System.lineSeparator());

        applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        String json = Files.readString(prepDir.resolve("decisions.json")).replaceAll("\\s+", "");
        assertThat(json).contains("\"junk\":2");
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
    void aMissingClassificationFileFailsLoudlyAndWarnsToConfirmItsReasonNoteFirst(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found and not already applied")
                .hasMessageContaining("Review reason note")
                .hasMessageContaining(prepDir.resolve("applied.log").toString());
    }

    @Test
    void aMissingFunnyFileFailsLoudlyAndWarnsToConfirmItsHashIndexRowFirst(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "funny", "genuinely funny"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("library hash-index row");
    }

    @Test
    void aMissingNearDupRejectFileFailsLoudlyWithNoExtraCaveatSinceItHasNoSecondWrite(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path prepDir = prepDir(root);
        Path chosen = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path reject = root.resolve("Sorted/Photos/2019/06/b.jpg"); // never written to disk
        writeFile(chosen, "sharp");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(chosen), sidecarEntry(reject));
        writeShard(prepDir, "montage-001",
                nearDupChosenJson(chosen, "lake-jun19", "sharpest"),
                nearDupRejectJson(reject, "lake-jun19", "blurred"));

        assertThatThrownBy(() -> applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false)))
                .isInstanceOf(ApplyException.class)
                .hasMessageContaining("file not found and not already applied")
                .hasMessageNotContaining("Review reason note")
                .hasMessageNotContaining("hash-index row");
    }

    private static Path prepDir(Path root) throws IOException {
        Path dir = root.resolve("logs/cull-prep/scope1");
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeIndex(Path prepDir, int photos, List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", prepDir.resolve("base"), photos, List.of(), entries.size(), prepDir, entries));
    }

    private static void writeSidecar(Path prepDir, String montage, SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"), List.of(photos));
    }

    private static SidecarPhotoEntry sidecarEntry(Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
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

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls. This
    // deterministically simulates a process crash partway through a single apply() invocation - the
    // scenario applied.log's per-decision, crash-safe design exists for. No amount of pre-seeded
    // state can reproduce that: it only proves the engine tolerates ALREADY-crashed state, not that
    // a crash mid-run leaves the right things durable.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private int movesUntilFailure;

        FailingAfterMoves(int movesUntilFailure) {
            this.movesUntilFailure = movesUntilFailure;
        }

        @Override
        public Path move(Path source, Path destDir) {
            if (movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            movesUntilFailure--;
            return delegate.move(source, destDir);
        }

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
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
