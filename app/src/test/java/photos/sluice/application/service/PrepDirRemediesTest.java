package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.CullPrepTestSupport.applyEngine;
import static photos.sluice.application.service.CullPrepTestSupport.classificationJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupChosenJson;
import static photos.sluice.application.service.CullPrepTestSupport.nearDupRejectJson;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.prepDirRemedies;
import static photos.sluice.application.service.CullPrepTestSupport.readIndex;
import static photos.sluice.application.service.CullPrepTestSupport.sidecarEntry;
import static photos.sluice.application.service.CullPrepTestSupport.writeFile;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;
import static photos.sluice.application.service.CullPrepTestSupport.writeShard;
import static photos.sluice.application.service.CullPrepTestSupport.writeSidecar;

// The repairs a damaged prep dir can be put through. A remedy records a disposition rather than
// editing a shard. So most of these prove the remedy by running a real apply() afterwards and
// asserting on what it then does.
class PrepDirRemediesTest {

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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.skipMissingSource(prepDir, gone, "confirmed permanently deleted by the user");
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.skipMissingSource(prepDir, gone, "confirmed permanently deleted by the user");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.unreviewable()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Unreviewable"))).isFalse();
    }

    // Classification checks the disposition ledger's Skipped status before its NearDupChosen-shaped
    // copy exception. A skip must win even for a decision type that would otherwise always be
    // Unresolved once its source is missing.
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.skipMissingSource(prepDir, chosen, "confirmed the chosen photo itself is gone");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.nearDupGroups()).isZero();
        assertThat(Files.exists(reject)).isFalse();
        Path dupDir = root.resolve("Duplicates/2019-06_lake-jun19");
        assertThat(Files.exists(dupDir.resolve("a.jpg"))).isFalse();
        assertThat(Files.exists(dupDir.resolve("b.jpg"))).isTrue();
    }

    // classify() checks whether the source is still on disk before it consults the ledger's skips.
    // A skip is the remedy for a file that has gone missing. Once that file is back, there is
    // nothing left for the skip to excuse, so the decision applies normally.
    @Test
    void aSkippedDecisionWhoseFileIsRestoredIsAppliedNormally(@TempDir Path root) throws IOException, ApplyException {
        final Path libraryRoot = root.resolve("Library");
        final Path prepDir = prepDir(root);
        final Path restored = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(restored, "blurry");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(restored));
        writeShard(prepDir, "montage-001", classificationJson(restored, "junk", "blurry"));

        prepDirRemedies(root, libraryRoot).skipMissingSource(prepDir, restored, "thought it was gone, then found it");
        final ApplyReport report = applyEngine(root, libraryRoot).apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(restored)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.resolveOverlap(prepDir, photo, OverlapResolution.TRUST_DECISION, "the decision is correct");
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.resolveOverlap(prepDir, photo, OverlapResolution.TREAT_AS_UNREVIEWABLE, "the file wasn't actually reviewed");
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

        Optional<String> repaired = prepDirRemedies(root, libraryRoot)
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

        Path filed = prepDirRemedies(root, libraryRoot).setAsideStrayShard(prepDir, new StrayShard("decisions-001.json"));

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

        Optional<PrepDir> rebuilt = prepDirRemedies(root, root.resolve("Library")).rebuildIndex(prepDir);

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

        Optional<PrepDir> rebuilt = prepDirRemedies(root, root.resolve("Library")).rebuildIndex(prepDir);

        assertThat(rebuilt).isEmpty();
    }

    @Test
    void rebuildIndexRefusesWhenAnyContiguousSidecarIsUnparseable(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        writeSidecar(prepDir, "montage-001", sidecarEntry(root.resolve("Sorted/Photos/2019/06/a.jpg")));
        Files.writeString(prepDir.resolve("montage-002.json"), "not valid json");

        Optional<PrepDir> rebuilt = prepDirRemedies(root, root.resolve("Library")).rebuildIndex(prepDir);

        assertThat(rebuilt).isEmpty();
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.SET_ASIDE, "redo this batch later");
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
        PrepDirRemedies remedies = prepDirRemedies(root, libraryRoot);
        ApplyEngine engine = applyEngine(root, libraryRoot);

        remedies.resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.APPLY_ANYWAY, "trust the culler's own shard");
        ApplyReport report = engine.apply(prepDir, new ApplyOptions(false));

        assertThat(report.byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(root.resolve("Review/junk/a.jpg"))).isTrue();
    }

    @Test
    void resolveCorruptSidecarFilesTheSidecarFileIntoTheDisasterDrawerWhenStillPresent(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));
        Files.writeString(prepDir.resolve("montage-001.json"), "not valid json"); // present, but corrupt

        prepDirRemedies(root, root.resolve("Library"))
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

        DiscardReport report = prepDirRemedies(root, root.resolve("Library")).discard(prepDir);
        Path graveyard = report.graveyard();

        assertThat(graveyard.getParent()).isEqualTo(root.resolve("logs/disasters"));
        assertThat(graveyard.getFileName().toString()).startsWith("scope1-");
        assertThat(Files.exists(graveyard.resolve("index.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("montage-001.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("disasters/2026-01-01_00-00-00-something.txt"))).isTrue();
        assertThat(Files.exists(graveyard.resolve("montage-001.jpg"))).isFalse();
        assertThat(Files.exists(graveyard.resolve("tile-001-01.jpg"))).isFalse();
        assertThat(Files.exists(prepDir)).isFalse();
        assertThat(report.shardsSetAside()).isEqualTo(1);
    }

}
