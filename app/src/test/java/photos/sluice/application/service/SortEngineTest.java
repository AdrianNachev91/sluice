package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.InboxScanner;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.ImageDimensionsReader;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SortEngineTest {

    private final NioMediaStore mediaStore = new NioMediaStore();
    private final InboxScanner inboxScanner = new InboxScanner();
    private final Sha256Hasher sha256Port = new Sha256Hasher();
    private final ImageDimensionsReader imageDimensionsPort = new ImageDimensionsReader();
    private final DateResolver dateResolver =
            new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());

    @Test
    void plainFilenameDatedPhotoSortsIntoSortedPhotosYearMonth(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210315_photo.jpg"))).isFalse();
    }

    @Test
    void videoSortsIntoSortedVideos(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_clip.mp4"), "video bytes");

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.videosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Videos/2019/06/20190615_clip.mp4"))).isTrue();
    }

    @Test
    void takeoutSidecarDatedPhotoSortsAndConsumesSidecar(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        Path photo = inbox.resolve("photo1.jpg");
        writeFile(photo, padded("keeper"));
        Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1.jpg"))).isTrue();
    }

    @Test
    void sharedSidecarBetweenOriginalAndEditedCopyIsDeletedExactlyOnce(@TempDir Path root) throws IOException {
        // TakeoutSidecarPairer maps an "-edited" copy to its original's sidecar. Both media files
        // in scope resolve their date via the same JSON path, so it must be consumed once, not
        // once per file.
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("photo1.jpg"), padded("original"));
        writeFile(inbox.resolve("photo1-edited.jpg"), padded("edited"));
        Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1-edited.jpg"))).isTrue();
    }

    @Test
    void invalidSidecarIsNotConsumedInlineButIsSweptOnceItsMediaLeavesTheDirectory(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));
        Path sidecar = inbox.resolve("20210315_photo.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        // Mechanism 1 (inline consumption) never touches it. Its date came from filename, not
        // this sidecar, so sidecarsDeleted stays 0. But the whole-Inbox sweep (mechanism 2) is
        // independent of why the media left. Once the photo is sorted away, nothing in the
        // sidecar's directory owns it anymore, so the sweep deletes it anyway.
        assertThat(summary.sidecarsDeleted()).isEqualTo(0);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
    }

    @Test
    void orphanedSidecarInANestedAlbumDirIsSweptAndTheNowEmptyDirIsRemoved(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20210315_photo.jpg"), padded("keeper"));
        Path sidecar = albumDir.resolve("20210315_photo.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(albumDir)).isFalse();
        assertThat(Files.exists(inbox.resolve("Takeout"))).isFalse();
    }

    @Test
    void sidecarSurvivesTheSweepWhileItsMediaIsStillPresentAwaitingAFutureRun(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20190101_a.jpg"), padded("in-scope"));
        writeFile(albumDir.resolve("20250101_future.jpg"), padded("future"));
        Path futureSidecar = albumDir.resolve("20250101_future.jpg.supplemental-metadata.json");
        writeSidecar(futureSidecar, LocalDateTime.of(2025, 1, 1, 0, 0, 0));

        SortSummary summary = sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_a.jpg"))).isTrue();
        assertThat(Files.exists(albumDir.resolve("20250101_future.jpg"))).isTrue();
        assertThat(Files.exists(futureSidecar)).isTrue();
        assertThat(Files.exists(albumDir)).isTrue();
    }

    @Test
    void sidecarOfAReimportDeletedFileIsSweptTooNotJustSidecarsOfMovedFiles(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        String content = padded("reimport-with-sidecar");
        Path inboxFile = inbox.resolve("20190101_dup.jpg");
        writeFile(inboxFile, content);
        String hash = sha256Port.hash(inboxFile);
        Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, content);
        HashIndexPort hashIndex = seededIndex(root, hash, libraryFile);
        // Invalid, so mechanism 1 never wins the date-resolution race for it. Any cleanup here can
        // only be mechanism 2 (the sweep) noticing the media is gone, not the inline consumption.
        Path sidecar = inbox.resolve("20190101_dup.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        SortSummary summary = sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(1);
        assertThat(summary.sidecarsDeleted()).isEqualTo(0);
        assertThat(Files.exists(sidecar)).isFalse();
    }

    @Test
    void reimportOfFileStillInLibraryIsDeletedNotSorted(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        String content = padded("reimport");
        Path inboxFile = inbox.resolve("20190101_dup.jpg");
        writeFile(inboxFile, content);
        String hash = sha256Port.hash(inboxFile);
        Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, content);
        HashIndexPort hashIndex = seededIndex(root, hash, libraryFile);

        SortSummary summary = sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(inboxFile)).isFalse();
    }

    @Test
    void staleLibraryHashWithMissingPathIsNotTreatedAsReimport(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        String content = padded("stale");
        Path inboxFile = inbox.resolve("20190101_keeper.jpg");
        writeFile(inboxFile, content);
        String hash = sha256Port.hash(inboxFile);
        Path goneLibraryFile = root.resolve("LibraryFixture").resolve("gone.jpg"); // never created
        HashIndexPort hashIndex = seededIndex(root, hash, goneLibraryFile);

        SortSummary summary = sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_keeper.jpg"))).isTrue();
    }

    @Test
    void withinBatchDuplicateKeepsExactlyOneDeletesTheRest(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        String content = padded("batchdup");
        writeFile(inbox.resolve("20190102_a.jpg"), content);
        writeFile(inbox.resolve("20190102_b.jpg"), content);

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.byteDupsDeleted()).isEqualTo(1);
        Path destDir = root.resolve("Sorted/Photos/2019/01");
        try (var files = Files.list(destDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void lowResPhotoRoutesToReviewWithReasonNote(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_tiny.jpg"), "tiny");

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(root.resolve("Review/2019-06/20190615_tiny.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/2019-06/_reasons.txt")))
                .contains("20190615_tiny.jpg - low-res");
    }

    @Test
    void svgIsExemptFromLowResEvenWhenTiny(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_drawing.svg"), "<svg/>");

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/20190615_drawing.svg"))).isTrue();
    }

    @Test
    void videoIsExemptFromLowResEvenWhenTiny(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_clip.mp4"), "x");

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(0);
        assertThat(summary.videosSorted()).isEqualTo(1);
    }

    @Test
    void implausibleDateFileRoutesToUnsortedWithReasonNote(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        Path file = inbox.resolve("nodatepattern.jpg");
        writeFile(file, padded("mystery"));
        setMtime(file, LocalDateTime.of(1990, 1, 1, 0, 0));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.unsorted()).isEqualTo(1);
        assertThat(summary.unsortedFiles()).containsExactly("nodatepattern.jpg");
        assertThat(Files.exists(root.resolve("Review/Unsorted/nodatepattern.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/Unsorted/_reasons.txt")))
                .contains("nodatepattern.jpg - unsorted-implausible-date");
    }

    @Test
    void lowConfidenceMtimeDatedFileSortsNormallyButIsFlagged(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        Path file = inbox.resolve("nodatepattern2.jpg");
        writeFile(file, padded("mystery2"));
        setMtime(file, LocalDateTime.of(2022, 6, 1, 9, 0, 0));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.unsorted()).isEqualTo(0);
        assertThat(summary.lowConfidenceFiles()).containsExactly("nodatepattern2.jpg (mtime 2022-06-01)");
        assertThat(Files.exists(root.resolve("Sorted/Photos/2022/06/nodatepattern2.jpg"))).isTrue();
    }

    @Test
    void yearScopeOnlyProcessesFilesInThatYear(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_in.jpg"), padded("in"));
        writeFile(inbox.resolve("20200101_out.jpg"), padded("out"));

        SortSummary summary = sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_in.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20200101_out.jpg"))).isTrue();
    }

    @Test
    void oldestYearScopeProcessesOnlyTheMinYearAcrossTheWholeInbox(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_oldest.jpg"), padded("oldest"));
        writeFile(inbox.resolve("20200101_mid.jpg"), padded("mid"));
        writeFile(inbox.resolve("20210101_newest.jpg"), padded("newest"));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_oldest.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20200101_mid.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210101_newest.jpg"))).isTrue();
    }

    @Test
    void oldestNScopeProcessesOnlyTheNEarliestAcrossTheWholeInbox(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_first.jpg"), padded("first"));
        writeFile(inbox.resolve("20200101_second.jpg"), padded("second"));
        writeFile(inbox.resolve("20210101_third.jpg"), padded("third"));

        SortSummary summary = sortEngine(root).sort(new SortScope.OldestN(2));

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_first.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/01/20200101_second.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210101_third.jpg"))).isTrue();
    }

    @Test
    void mixedBatchCountsSatisfyTheProcessedInvariant(@TempDir Path root) throws IOException {
        // One file engineered per outcome bucket, run together. This checks the
        // processed-equals-sum-of-buckets invariant against a real mixed batch, not six isolated
        // single-file cases. Isolated cases could each pass independently while a cross-file
        // interaction - e.g. one file's routing changing another's counters - went unnoticed.
        Path inbox = inboxOf(root);

        // Bucket 1: reimport. Its bytes are seeded into a "library" hash index entry that still
        // exists on disk, so this file is redundant and gets deleted rather than sorted.
        String reimportContent = padded("reimport");
        Path reimportFile = inbox.resolve("20190101_reimport.jpg");
        writeFile(reimportFile, reimportContent);
        String reimportHash = sha256Port.hash(reimportFile);
        Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, reimportContent);
        HashIndexPort hashIndex = seededIndex(root, reimportHash, libraryFile);

        // Bucket 2: within-batch duplicate - two files, identical bytes, neither in the library.
        // One becomes a normal photo keeper (its own bucket below); the other is the duplicate.
        String dupContent = padded("dup");
        writeFile(inbox.resolve("20190102_dup_a.jpg"), dupContent);
        writeFile(inbox.resolve("20190102_dup_b.jpg"), dupContent);

        // Bucket 3: low-res - under the file-size threshold regardless of (unreadable) content.
        writeFile(inbox.resolve("20190103_lowres.jpg"), "tiny");

        // Bucket 4: unsorted. No filename date pattern and no EXIF, so it falls through to an
        // implausible mtime (pre-2000), which DateResolver demotes to UNSORTABLE. Its resolved
        // date is therefore 1990, not 2019 like every other file here. That's why the scope below
        // is OldestN(7) rather than Year(2019, null) or OldestYear(). A year-based scope would
        // select only this one file - the actual oldest year present - and leave the 2019 files
        // out of the batch entirely.
        Path unsortedFile = inbox.resolve("nodatepattern.jpg");
        writeFile(unsortedFile, padded("mystery"));
        setMtime(unsortedFile, LocalDateTime.of(1990, 1, 1, 0, 0));

        // Buckets 5 and 6: an ordinary photo keeper and an ordinary video keeper.
        writeFile(inbox.resolve("20190104_photo.jpg"), padded("photo"));
        writeFile(inbox.resolve("20190105_video.mp4"), "video");

        // All 7 files fit within OldestN(7), so every one is in scope regardless of the 1990/2019
        // date spread. No truncation, no ordering to reason about.
        SortSummary summary = sortEngine(root, hashIndex).sort(new SortScope.OldestN(7));

        assertThat(summary.processed()).isEqualTo(7);
        assertThat(summary.reimportsDeleted()).isEqualTo(1);
        assertThat(summary.byteDupsDeleted()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(2);
        assertThat(summary.videosSorted()).isEqualTo(1);
        assertThat(summary.lowRes()).isEqualTo(1);
        assertThat(summary.unsorted()).isEqualTo(1);
        assertThat(summary.reimportsDeleted() + summary.byteDupsDeleted() + summary.photosSorted()
                + summary.videosSorted() + summary.lowRes() + summary.unsorted())
                .isEqualTo(summary.processed());
    }

    @Test
    void progressCallbackTicksOnceForEachSurvivorAgainstTheFinalSortedCount(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        List<String> ticks = new ArrayList<>();
        sortEngine(root).sort(new SortScope.OldestYear(), (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancelMidDatingAbortsCleanlyWithNothingMovedOrDeleted(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        // Dating is checked before each file, so the first poll (for file 1) still runs and the
        // second (before file 2) cancels - proving the abort happens mid-pass, not merely when
        // already cancelled going in.
        AtomicInteger polls = new AtomicInteger();
        CancellationSignal cancelBeforeSecondFile = () -> polls.incrementAndGet() > 1;

        SortSummary summary =
                sortEngine(root).sort(new SortScope.OldestYear(), ProgressCallback.NO_OP, cancelBeforeSecondFile);

        assertThat(summary.processed()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(inbox.resolve("20210101_a.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210102_b.jpg"))).isTrue();
    }

    @Test
    void cancelMidRoutingStopsEarlyLeavingAlreadyMovedFilesMovedAndSummaryPartial(@TempDir Path root)
            throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));
        writeFile(inbox.resolve("20210103_c.jpg"), padded("c"));

        // Cancels once the first survivor's move has already ticked, so routing stops before the
        // remaining two are even looked at.
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        SortSummary summary =
                sortEngine(root).sort(new SortScope.OldestYear(), cancelAfterFirstTick, cancelled::get);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        Path destDir = root.resolve("Sorted/Photos/2021/01");
        try (var sorted = Files.list(destDir)) {
            assertThat(sorted.count()).isEqualTo(1);
        }
        try (var remaining = Files.list(inbox)) {
            assertThat(remaining.count()).isEqualTo(2);
        }
    }

    @Test
    void cancelMidRoutingLeavesAnUnroutedFilesSidecarIntact(@TempDir Path root) throws IOException {
        Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        Path sidecarA = inbox.resolve("20210101_a.jpg.supplemental-metadata.json");
        writeSidecar(sidecarA, LocalDateTime.of(2021, 1, 1, 12, 0, 0));

        Path photoB = inbox.resolve("20210102_b.jpg");
        writeFile(photoB, padded("b"));
        Path sidecarB = inbox.resolve("20210102_b.jpg.supplemental-metadata.json");
        writeSidecar(sidecarB, LocalDateTime.of(2021, 1, 2, 12, 0, 0));

        // Cancels once the first survivor (a, scan-order first) has routed, so b is never reached.
        // Sidecar consumption must only spend a's sidecar - b's media never left the Inbox, so its
        // sidecar has to survive for a future run.
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        SortSummary summary =
                sortEngine(root).sort(new SortScope.OldestYear(), cancelAfterFirstTick, cancelled::get);

        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecarA)).isFalse();
        assertThat(Files.exists(photoB)).isTrue();
        assertThat(Files.exists(sidecarB)).isTrue();
    }

    private static Path inboxOf(Path root) {
        return root.resolve("Inbox");
    }

    private SortEngine sortEngine(Path root) {
        return sortEngine(root, new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv")));
    }

    private SortEngine sortEngine(Path root, HashIndexPort hashIndex) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), root.toString(), root.resolve("Inbox").toString()));
        return new SortEngine(pathsConfig, inboxScanner, dateResolver, sha256Port, hashIndex,
                imageDimensionsPort, mediaStore);
    }

    private static HashIndexPort seededIndex(Path root, String hash, Path libraryPath) {
        var index = new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv"));
        index.append(List.of(new IndexEntry(hash, libraryPath)));
        return index;
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // 60,000 bytes clears LowResGate's 50KB threshold. A fixture built from this never gets
    // classified as low-res, even though its content is garbage, not a real image. Most tests
    // here aren't testing low-res routing. They use this fixture instead of real image bytes to
    // keep other outcomes (dedup, dating, sorting) independent of it. The marker prefix keeps
    // otherwise-identical padding distinguishable by content/hash within one test.
    private static String padded(String marker) {
        return marker + "x".repeat(60_000);
    }

    private static void setMtime(Path file, LocalDateTime when) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(when.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private static void writeSidecar(Path sidecar, LocalDateTime photoTakenAt) throws IOException {
        long epochSeconds = photoTakenAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        Files.writeString(sidecar, """
                {
                  "photoTakenTime": {
                    "timestamp": "%d"
                  }
                }""".formatted(epochSeconds));
    }
}
