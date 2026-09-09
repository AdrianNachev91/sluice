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
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.job.CancellationSignal;
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
    void plainFilenameDatedPhotoSortsIntoSortedPhotosYearMonth(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210315_photo.jpg"))).isFalse();
        // No Takeout JSON anywhere in this fixture - the pairing canary has nothing to warn about.
        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    void healthyPairingRateProducesNoPairingWarning(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path photo = inbox.resolve("photo1.jpg");
        writeFile(photo, padded("keeper"));
        writeSidecar(inbox.resolve("photo1.jpg.supplemental-metadata.json"), LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    void nearZeroPairingRateWithSidecarsPresentTripsThePairingCanary(@TempDir final Path root) throws IOException {
        // A changed Takeout export shape. A JSON sidecar exists, so takeout mode is on. Its name
        // does not prefix-match the media file's, so nothing pairs and every file falls back to
        // mtime.
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));
        Files.writeString(inbox.resolve("print-subscriptions.json"), "{}");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.sidecarsDeleted()).isEqualTo(0);
        assertThat(summary.warnings()).hasSize(1);
        assertThat(summary.warnings().getFirst()).contains("Takeout sidecars are present");
    }

    @Test
    void videoSortsIntoSortedVideos(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_clip.mp4"), "video bytes");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.videosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Videos/2019/06/20190615_clip.mp4"))).isTrue();
    }

    @Test
    void takeoutSidecarDatedPhotoSortsAndConsumesSidecar(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path photo = inbox.resolve("photo1.jpg");
        writeFile(photo, padded("keeper"));
        final Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1.jpg"))).isTrue();
    }

    @Test
    void sharedSidecarBetweenOriginalAndEditedCopyIsDeletedExactlyOnce(@TempDir final Path root) throws IOException {
        // An "-edited" copy pairs to its original's sidecar, so both media files in scope resolve
        // their date through the same JSON path.
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("photo1.jpg"), padded("original"));
        writeFile(inbox.resolve("photo1-edited.jpg"), padded("edited"));
        final Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2015/05/photo1-edited.jpg"))).isTrue();
    }

    @Test
    void invalidSidecarIsNotConsumedInlineButIsSweptOnceItsMediaLeavesTheDirectory(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));
        final Path sidecar = inbox.resolve("20210315_photo.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        // Its date came from the filename rather than this sidecar, so the inline consumption
        // never touched it and the count stays 0. The sweep still takes it once the photo is gone.
        assertThat(summary.sidecarsDeleted()).isEqualTo(0);
        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
    }

    @Test
    void orphanedSidecarInANestedAlbumDirIsSweptAndTheNowEmptyDirIsRemoved(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20210315_photo.jpg"), padded("keeper"));
        final Path sidecar = albumDir.resolve("20210315_photo.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(Files.exists(albumDir)).isFalse();
        assertThat(Files.exists(inbox.resolve("Takeout"))).isFalse();
    }

    @Test
    void sidecarSurvivesTheSweepWhileItsMediaIsStillPresentAwaitingAFutureRun(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20190101_a.jpg"), padded("in-scope"));
        writeFile(albumDir.resolve("20250101_future.jpg"), padded("future"));
        final Path futureSidecar = albumDir.resolve("20250101_future.jpg.supplemental-metadata.json");
        writeSidecar(futureSidecar, LocalDateTime.of(2025, 1, 1, 0, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_a.jpg"))).isTrue();
        assertThat(Files.exists(albumDir.resolve("20250101_future.jpg"))).isTrue();
        assertThat(Files.exists(futureSidecar)).isTrue();
        assertThat(Files.exists(albumDir)).isTrue();
    }

    // The sidecar is invalid JSON, so it cannot win the date race and the filename source wins
    // instead. The sweep is therefore the only thing that could have deleted it.
    @Test
    void aLaterYearsSortSweepsTheSidecarTheEarlierRunLeftBehind(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20190101_a.jpg"), padded("in-scope"));
        writeFile(albumDir.resolve("20250101_future.jpg"), padded("future"));
        final Path futureSidecar = albumDir.resolve("20250101_future.jpg.supplemental-metadata.json");
        Files.writeString(futureSidecar, "{not valid json");

        this.sortEngine(root).sort(new SortScope.Year(2019, null));
        assertThat(Files.exists(futureSidecar)).isTrue();

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.Year(2025, null));

        assertThat(summary.sidecarsDeleted()).isZero();
        assertThat(Files.exists(futureSidecar)).isFalse();
        assertThat(Files.exists(albumDir)).isFalse();
    }

    @Test
    void sidecarOfAReimportDeletedFileIsSweptTooNotJustSidecarsOfMovedFiles(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final String content = padded("reimport-with-sidecar");
        final Path inboxFile = inbox.resolve("20190101_dup.jpg");
        writeFile(inboxFile, content);
        final String hash = this.sha256Port.hash(inboxFile);
        final Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, content);
        final HashIndexPort hashIndex = seededIndex(root, hash, libraryFile);
        // Invalid, so it never wins the date-resolution race and inline consumption cannot be what
        // removes it. Only the sweep noticing the media is gone can.
        final Path sidecar = inbox.resolve("20190101_dup.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, "{not valid json");

        final SortSummary summary = this.sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(1);
        assertThat(summary.sidecarsDeleted()).isEqualTo(0);
        assertThat(Files.exists(sidecar)).isFalse();
    }

    @Test
    void reimportOfFileStillInLibraryIsDeletedNotSorted(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final String content = padded("reimport");
        final Path inboxFile = inbox.resolve("20190101_dup.jpg");
        writeFile(inboxFile, content);
        final String hash = this.sha256Port.hash(inboxFile);
        final Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, content);
        final HashIndexPort hashIndex = seededIndex(root, hash, libraryFile);

        final SortSummary summary = this.sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(inboxFile)).isFalse();
    }

    @Test
    void staleLibraryHashWithMissingPathIsNotTreatedAsReimport(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final String content = padded("stale");
        final Path inboxFile = inbox.resolve("20190101_keeper.jpg");
        writeFile(inboxFile, content);
        final String hash = this.sha256Port.hash(inboxFile);
        final Path goneLibraryFile = root.resolve("LibraryFixture").resolve("gone.jpg"); // never created
        final HashIndexPort hashIndex = seededIndex(root, hash, goneLibraryFile);

        final SortSummary summary = this.sortEngine(root, hashIndex).sort(new SortScope.OldestYear());

        assertThat(summary.reimportsDeleted()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_keeper.jpg"))).isTrue();
    }

    @Test
    void withinBatchDuplicateKeepsExactlyOneDeletesTheRest(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final String content = padded("batchdup");
        writeFile(inbox.resolve("20190102_a.jpg"), content);
        writeFile(inbox.resolve("20190102_b.jpg"), content);

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.byteDupsDeleted()).isEqualTo(1);
        final Path destDir = root.resolve("Sorted/Photos/2019/01");
        try (final var files = Files.list(destDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void lowResPhotoRoutesToReviewWithReasonNote(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_tiny.jpg"), "tiny");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(root.resolve("Review/2019-06/20190615_tiny.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/2019-06/_reasons.txt")))
                .contains("20190615_tiny.jpg (2019-06-15) - too small to sift");
    }

    @Test
    void aLowResPhotoDatedOffItsTimestampSaysSoInTheNote(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path file = inbox.resolve("tiny.jpg");
        writeFile(file, "tiny");
        setMtime(file, LocalDateTime.of(2022, 6, 1, 9, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(1);
        assertThat(summary.lowConfidenceFiles()).containsExactly("tiny.jpg (mtime 2022-06-01)");
        assertThat(Files.readString(root.resolve("Review/2022-06/_reasons.txt")))
                .contains("tiny.jpg (2022-06-01, low confidence date) - too small to sift");
    }

    @Test
    void aNameAlreadyTakenInTheReviewFolderIsNotedUnderTheNameTheFileLandedAs(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Review/2019-06/20190615_tiny.jpg"), "already there");
        writeFile(inboxOf(root).resolve("20190615_tiny.jpg"), "tiny");

        this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(Files.readString(root.resolve("Review/2019-06/_reasons.txt")))
                .contains("20190615_tiny (2).jpg (2019-06-15) - too small to sift");
    }

    @Test
    void svgIsExemptFromLowResEvenWhenTiny(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_drawing.svg"), "<svg/>");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/20190615_drawing.svg"))).isTrue();
    }

    @Test
    void videoIsExemptFromLowResEvenWhenTiny(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190615_clip.mp4"), "x");

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.lowRes()).isEqualTo(0);
        assertThat(summary.videosSorted()).isEqualTo(1);
    }

    @Test
    void implausibleDateFileRoutesToUnsortedWithReasonNote(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path file = inbox.resolve("nodatepattern.jpg");
        writeFile(file, padded("mystery"));
        setMtime(file, LocalDateTime.of(1990, 1, 1, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.unsorted()).isEqualTo(1);
        assertThat(summary.unsortedFiles()).containsExactly("nodatepattern.jpg");
        assertThat(Files.exists(root.resolve("Review/Unsorted/nodatepattern.jpg"))).isTrue();
        assertThat(Files.readString(root.resolve("Review/Unsorted/_reasons.txt")))
                .contains("nodatepattern.jpg - no date could be read");
    }

    @Test
    void lowConfidenceMtimeDatedFileSortsNormallyButIsFlagged(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path file = inbox.resolve("nodatepattern2.jpg");
        writeFile(file, padded("mystery2"));
        setMtime(file, LocalDateTime.of(2022, 6, 1, 9, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.unsorted()).isEqualTo(0);
        assertThat(summary.lowConfidenceFiles()).containsExactly("nodatepattern2.jpg (mtime 2022-06-01)");
        assertThat(Files.exists(root.resolve("Sorted/Photos/2022/06/nodatepattern2.jpg"))).isTrue();
    }

    @Test
    void yearScopeOnlyProcessesFilesInThatYear(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_in.jpg"), padded("in"));
        writeFile(inbox.resolve("20200101_out.jpg"), padded("out"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_in.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20200101_out.jpg"))).isTrue();
    }

    @Test
    void oldestYearScopeProcessesOnlyTheMinYearAcrossTheWholeInbox(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_oldest.jpg"), padded("oldest"));
        writeFile(inbox.resolve("20200101_mid.jpg"), padded("mid"));
        writeFile(inbox.resolve("20210101_newest.jpg"), padded("newest"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_oldest.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20200101_mid.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210101_newest.jpg"))).isTrue();
    }

    // Reading "the" year off a summary rests on this: a second year cannot reach Sorted in the
    // same run.
    @Test
    void anOldestYearSortReportsOnlyTheYearItPicked(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20180101_a.jpg"), padded("older"));
        writeFile(inbox.resolve("20190101_b.jpg"), padded("newer"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.yearsSorted()).containsExactly(2018);
    }

    @Test
    void anExplicitYearSortReportsOnlyThatYear(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20180101_a.jpg"), padded("older"));
        writeFile(inbox.resolve("20190101_b.jpg"), padded("newer"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.yearsSorted()).containsExactly(2019);
    }

    @Test
    void oldestNScopeProcessesOnlyTheNEarliestAcrossTheWholeInbox(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_first.jpg"), padded("first"));
        writeFile(inbox.resolve("20200101_second.jpg"), padded("second"));
        writeFile(inbox.resolve("20210101_third.jpg"), padded("third"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestN(2));

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_first.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/01/20200101_second.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210101_third.jpg"))).isTrue();
    }

    @Test
    void mixedBatchCountsSatisfyTheProcessedInvariant(@TempDir final Path root) throws IOException {
        // One file per outcome bucket, run together as one batch. Six isolated single-file cases
        // could each pass while a cross-file interaction, one file's routing changing another's
        // counters, went unnoticed.
        final Path inbox = inboxOf(root);

        // Bucket 1: reimport. Its bytes are seeded into a "library" hash index entry that still
        // exists on disk, so this file is redundant and gets deleted rather than sorted.
        final String reimportContent = padded("reimport");
        final Path reimportFile = inbox.resolve("20190101_reimport.jpg");
        writeFile(reimportFile, reimportContent);
        final String reimportHash = this.sha256Port.hash(reimportFile);
        final Path libraryFile = root.resolve("LibraryFixture").resolve("existing.jpg");
        writeFile(libraryFile, reimportContent);
        final HashIndexPort hashIndex = seededIndex(root, reimportHash, libraryFile);

        // Bucket 2: within-batch duplicate - two files, identical bytes, neither in the library.
        // One becomes a normal photo keeper (its own bucket below); the other is the duplicate.
        final String dupContent = padded("dup");
        writeFile(inbox.resolve("20190102_dup_a.jpg"), dupContent);
        writeFile(inbox.resolve("20190102_dup_b.jpg"), dupContent);

        // Bucket 3: low-res - under the file-size threshold regardless of (unreadable) content.
        writeFile(inbox.resolve("20190103_lowres.jpg"), "tiny");

        // Bucket 4: unsorted. No filename date pattern and no EXIF, so it falls through to an
        // implausible pre-2000 mtime and is demoted to UNSORTABLE. Its resolved date is 1990,
        // which is why the scope below is OldestN(7). A year-based scope would select this file
        // alone, as the oldest year present, and leave the 2019 files out of the batch.
        final Path unsortedFile = inbox.resolve("nodatepattern.jpg");
        writeFile(unsortedFile, padded("mystery"));
        setMtime(unsortedFile, LocalDateTime.of(1990, 1, 1, 0, 0));

        // Buckets 5 and 6: an ordinary photo keeper and an ordinary video keeper.
        writeFile(inbox.resolve("20190104_photo.jpg"), padded("photo"));
        writeFile(inbox.resolve("20190105_video.mp4"), "video");

        // All 7 fit within OldestN(7), so every one is in scope whatever the date spread. No
        // truncation, and no ordering to reason about.
        final SortSummary summary = this.sortEngine(root, hashIndex).sort(new SortScope.OldestN(7));

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
    void everyStageOfASortIsReportedAndCounted(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        final var reported = new RecordingPhases();
        this.sortEngine(root, reported).sort(new SortScope.OldestYear());

        assertThat(reported.events).containsExactly(
                "started:Finding dates...", "tick:Finding dates...:1/2", "tick:Finding dates...:2/2",
                "finished:Finding dates...",
                "started:Checking for duplicates...", "tick:Checking for duplicates...:1/2",
                "tick:Checking for duplicates...:2/2", "finished:Checking for duplicates...",
                "started:Sorting...", "tick:Sorting...:1/2", "tick:Sorting...:2/2", "finished:Sorting...");
    }

    @Test
    void aStoppedSortSaysTheStageGaveUpBeforeItSaysTheStageEnded(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        final AtomicInteger polls = new AtomicInteger();
        final var reported = new RecordingPhases();
        this.sortEngine(root, reported)
                .sort(new SortScope.OldestYear(), () -> polls.incrementAndGet() > 1);

        assertThat(reported.events).containsExactly(
                "started:Finding dates...", "tick:Finding dates...:1/2",
                "cut-short:Finding dates...", "finished:Finding dates...");
    }

    private static final class RecordingPhases implements ProgressPort {

        private final List<String> events = new ArrayList<>();

        @Override
        public void phaseStarted(final String phase) {
            this.events.add("started:" + phase);
        }

        @Override
        public void tick(final String phase, final int current, final int total) {
            this.events.add("tick:" + phase + ":" + current + "/" + total);
        }

        @Override
        public void phaseCutShort(final String phase) {
            this.events.add("cut-short:" + phase);
        }

        @Override
        public void phaseFinished(final String phase) {
            this.events.add("finished:" + phase);
        }
    }

    @Test
    void cancelMidDatingAbortsCleanlyWithNothingMovedOrDeleted(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        // Dating is checked before each file. The first poll runs and the second cancels, so the
        // abort happens mid-pass rather than on a run already cancelled going in.
        final AtomicInteger polls = new AtomicInteger();
        final CancellationSignal cancelBeforeSecondFile = () -> polls.incrementAndGet() > 1;

        final SortSummary summary =
                this.sortEngine(root).sort(new SortScope.OldestYear(),cancelBeforeSecondFile);

        assertThat(summary.processed()).isEqualTo(0);
        assertThat(summary.photosSorted()).isEqualTo(0);
        assertThat(Files.exists(inbox.resolve("20210101_a.jpg"))).isTrue();
        assertThat(Files.exists(inbox.resolve("20210102_b.jpg"))).isTrue();
    }

    @Test
    void cancelMidRoutingStopsEarlyLeavingAlreadyMovedFilesMovedAndSummaryPartial(@TempDir final Path root)
            throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));
        writeFile(inbox.resolve("20210103_c.jpg"), padded("c"));

        // Cancels once the first survivor's move has ticked, so routing stops before the remaining
        // two are looked at.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, cancelOnFirstFileRouted(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        final Path destDir = root.resolve("Sorted/Photos/2021/01");
        try (final var sorted = Files.list(destDir)) {
            assertThat(sorted.count()).isEqualTo(1);
        }
        try (final var remaining = Files.list(inbox)) {
            assertThat(remaining.count()).isEqualTo(2);
        }
    }

    @Test
    void cancelMidRoutingLeavesAnUnroutedFilesSidecarIntact(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path photoA = inbox.resolve("20210101_a.jpg");
        writeFile(photoA, padded("a"));
        final Path sidecarA = inbox.resolve("20210101_a.jpg.supplemental-metadata.json");
        writeSidecar(sidecarA, LocalDateTime.of(2021, 1, 1, 12, 0, 0));

        final Path photoB = inbox.resolve("20210102_b.jpg");
        writeFile(photoB, padded("b"));
        final Path sidecarB = inbox.resolve("20210102_b.jpg.supplemental-metadata.json");
        writeSidecar(sidecarB, LocalDateTime.of(2021, 1, 2, 12, 0, 0));

        // Cancels once the first survivor routed, whichever of a and b that turns out to be. Scan
        // order is filesystem-dependent rather than alphabetical. The other file's media never
        // left the Inbox, so its own sidecar has to survive for a future run.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, cancelOnFirstFileRouted(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        final boolean aRouted = !Files.exists(photoA);
        assertThat(summary.sidecarsDeleted()).isEqualTo(1);
        assertThat(Files.exists(sidecarA)).isEqualTo(!aRouted);
        assertThat(Files.exists(photoB)).isEqualTo(aRouted);
        assertThat(Files.exists(sidecarB)).isEqualTo(aRouted);
    }

    @Test
    void sharedSidecarSurvivesWhileOneOfItsTwoOwnersIsStillInTheInbox(@TempDir final Path root) throws IOException {
        // An "-edited" copy pairs to its original's sidecar, so both read the same date and the
        // scope cut can fall between them. Whichever one leaves, the JSON belongs to the one still
        // waiting and has to survive with it. Which of the two the cut takes is filesystem-
        // dependent, so the assertion below holds for either outcome rather than naming one.
        final Path inbox = inboxOf(root);
        final Path original = inbox.resolve("photo1.jpg");
        writeFile(original, padded("original"));
        final Path editedCopy = inbox.resolve("photo1-edited.jpg");
        writeFile(editedCopy, padded("edited"));
        final Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestN(1));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(original) ^ Files.exists(editedCopy)).isTrue();
        assertThat(summary.sidecarsDeleted()).isZero();
        assertThat(Files.exists(sidecar)).isTrue();
    }

    @Test
    void cancelMidRoutingLeavesASharedSidecarIntactForTheCoOwnerItDidNotReach(@TempDir final Path root)
            throws IOException {
        // The other way a co-owning pair gets split. Routing stops after the first survivor, so one
        // of the two copies never leaves the Inbox and the sidecar they share is not spent.
        final Path inbox = inboxOf(root);
        final Path original = inbox.resolve("photo1.jpg");
        writeFile(original, padded("original"));
        final Path editedCopy = inbox.resolve("photo1-edited.jpg");
        writeFile(editedCopy, padded("edited"));
        final Path sidecar = inbox.resolve("photo1.jpg.supplemental-metadata.json");
        writeSidecar(sidecar, LocalDateTime.of(2015, 5, 5, 12, 0, 0));

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, cancelOnFirstFileRouted(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(original) ^ Files.exists(editedCopy)).isTrue();
        assertThat(summary.sidecarsDeleted()).isZero();
        assertThat(Files.exists(sidecar)).isTrue();
    }

    @Test
    void prefixMatchedSidecarSurvivesWhileItsOutOfScopeMediaWaitsInTheInbox(@TempDir final Path root)
            throws IOException {
        // This sidecar pairs only through the prefix fallback: its base name starts with the media
        // filename, rather than deriving an owner key equal to it. Nothing but the pairing knows
        // they belong together.
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20190101_a.jpg"), padded("in-scope"));
        final Path futurePhoto = inbox.resolve("20250101_future.jpg");
        writeFile(futurePhoto, padded("future"));
        final Path futureSidecar = inbox.resolve("20250101_future.jpg.someextra.json");
        writeSidecar(futureSidecar, LocalDateTime.of(2025, 1, 1, 0, 0, 0));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.Year(2019, null));

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(Files.exists(futurePhoto)).isTrue();
        assertThat(Files.exists(futureSidecar)).isTrue();
    }

    @Test
    void aJsonThatWasNeverAPerPhotoSidecarSurvivesTheSweepAndKeepsItsDirectoryAlive(@TempDir final Path root)
            throws IOException {
        // Real Takeout exports ship a per-album metadata.json, and any dump can carry an unrelated
        // app's JSON. This album is emptied of media, so both sit where the sweep looks.
        final Path inbox = inboxOf(root);
        final Path albumDir = inbox.resolve("Takeout").resolve("Album");
        writeFile(albumDir.resolve("20190101_a.jpg"), padded("in-scope"));
        final Path albumDescriptor = albumDir.resolve("metadata.json");
        Files.writeString(albumDescriptor, "{\"title\": \"Album\"}");
        final Path unrelatedJson = albumDir.resolve("notes.json");
        Files.writeString(unrelatedJson, "{\"note\": \"mine\"}");

        this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/01/20190101_a.jpg"))).isTrue();
        assertThat(Files.exists(albumDescriptor)).isTrue();
        assertThat(Files.exists(unrelatedJson)).isTrue();
        assertThat(Files.exists(albumDir)).isTrue();
    }

    @Test
    void cancelWhileHashingDeletesNothingAndMovesNothing(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        final Path alreadyInTheLibrary = inbox.resolve("20210101_held.jpg");
        writeFile(alreadyInTheLibrary, padded("held"));
        writeFile(inbox.resolve("20210102_new.jpg"), padded("new"));
        final HashIndexPort index = seededIndex(root, this.sha256Port.hash(alreadyInTheLibrary),
                root.resolve("Library/Photos/2021/01/20210101_held.jpg"));

        final var cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, index, cancelOnFirstFileHashed(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(summary.processed()).isZero();
        assertThat(summary.reimportsDeleted()).isZero();
        // These counts are what an untouched Inbox produces too, so only the flag separates them.
        assertThat(summary.cancelled()).isTrue();
        try (final var remaining = Files.list(inbox)) {
            assertThat(remaining.count()).isEqualTo(2);
        }
        assertThat(Files.exists(root.resolve("Sorted"))).isFalse();
    }

    @Test
    void aStoppedRunLeavesAnAlreadyOrphanedSidecarForTheNextRunToTake(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));
        final Path orphan = inbox.resolve("20200101_gone.jpg.supplemental-metadata.json");
        writeSidecar(orphan, LocalDateTime.of(2020, 1, 1, 12, 0, 0));

        final var cancelled = new AtomicBoolean(false);
        this.sortEngine(root, cancelOnFirstFileRouted(cancelled)).sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(Files.exists(orphan)).isTrue();
    }

    @Test
    void aRunStoppedWithFilesStillToRouteSaysHowManyItLeftInTheInbox(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        final var cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, cancelOnFirstFileRouted(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(summary.cancelled()).isTrue();
        assertThat(summary.leftBehind()).isEqualTo(1);
    }

    // Reading the signal rather than the routed count would call this run stopped.
    @Test
    void aRunWhoseStopArrivedAfterTheLastFileRoutedDidNotStopShort(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));

        final var cancelled = new AtomicBoolean(false);
        final SortSummary summary = this.sortEngine(root, cancelOnFirstFileRouted(cancelled))
                .sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(cancelled).isTrue();
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
    }

    // The flag above decides the sweep too, so the run that did not stop short does its
    // housekeeping. Asserting the flag alone would leave that half unpinned.
    @Test
    void aRunWhoseStopArrivedAfterTheLastFileRoutedStillSweepsAnOrphanedSidecar(@TempDir final Path root)
            throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        final Path orphan = inbox.resolve("20200101_gone.jpg.supplemental-metadata.json");
        writeSidecar(orphan, LocalDateTime.of(2020, 1, 1, 12, 0, 0));

        final var cancelled = new AtomicBoolean(false);
        this.sortEngine(root, cancelOnFirstFileRouted(cancelled)).sort(new SortScope.OldestYear(), cancelled::get);

        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    void aTransferGivenUpOnPartWayReportsTheFilesThatDidLand(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));
        writeFile(inbox.resolve("20210103_c.jpg"), padded("c"));

        final SortSummary summary = this.sortEngineOver(root, abandoningTheSecondMove())
                .sort(new SortScope.OldestYear(), CancellationSignal.NEVER);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(summary.cancelled()).isTrue();
        try (final var remaining = Files.list(inbox)) {
            assertThat(remaining.count()).isEqualTo(2);
        }
    }

    private static Path inboxOf(final Path root) {
        return root.resolve("Inbox");
    }

    private static ProgressPort cancelOnFirstFileHashed(final AtomicBoolean cancelled) {
        return new ProgressPort() {
            @Override
            public void phaseStarted(final String phase) {
            }

            @Override
            public void tick(final String phase, final int current, final int total) {
                cancelled.set("Checking for duplicates...".equals(phase) && current == 1);
            }

            @Override
            public void phaseFinished(final String phase) {
            }
        };
    }

    private static MediaStore abandoningTheSecondMove() {
        return new NioMediaStore() {
            private int moves;

            @Override
            public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                    final TransferProgress watching) {
                this.moves++;
                if (this.moves == 2) {
                    throw new TransferAbandonedException(source);
                }
                return super.move(source, destDir, stop, watching);
            }
        };
    }

    private SortEngine sortEngineOver(final Path root, final MediaStore store) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root, root.resolve("Inbox"));
        return new SortEngine(pathsConfig, this.inboxScanner, this.dateResolver, this.sha256Port,
                new CsvLibraryHashIndex(SettingsFixture.workingRoot(root)), this.imageDimensionsPort, store,
                ProgressPort.NO_OP);
    }

    // Cancellation is hung off the routing stage's own tick, so the run stops with exactly one
    // file moved. Dating and the duplicate check tick too, and cancelling on either would stop
    // the run before anything had moved at all.
    private static ProgressPort cancelOnFirstFileRouted(final AtomicBoolean cancelled) {
        return new ProgressPort() {
            @Override
            public void phaseStarted(final String phase) {
            }

            @Override
            public void tick(final String phase, final int current, final int total) {
                cancelled.set("Sorting...".equals(phase) && current == 1);
            }

            @Override
            public void phaseFinished(final String phase) {
            }
        };
    }

    private SortEngine sortEngine(final Path root) {
        return this.sortEngine(root, new CsvLibraryHashIndex(SettingsFixture.workingRoot(root)));
    }

    private SortEngine sortEngine(final Path root, final ProgressPort progressPort) {
        return this.sortEngine(root, new CsvLibraryHashIndex(SettingsFixture.workingRoot(root)), progressPort);
    }

    private SortEngine sortEngine(final Path root, final HashIndexPort hashIndex) {
        return this.sortEngine(root, hashIndex, ProgressPort.NO_OP);
    }

    private SortEngine sortEngine(final Path root, final HashIndexPort hashIndex,
                                  final ProgressPort progressPort) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root, root.resolve("Inbox"));
        return new SortEngine(pathsConfig, this.inboxScanner, this.dateResolver, this.sha256Port, hashIndex,
                this.imageDimensionsPort, this.mediaStore, progressPort);
    }

    private static HashIndexPort seededIndex(final Path root, final String hash, final Path libraryPath) {
        final var index = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));
        index.append(List.of(new IndexEntry(hash, libraryPath)));
        return index;
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Enough bytes to clear LowResGate's size threshold. A fixture built from this is never
    // classified as low-res, though its content is garbage rather than a real image. That keeps
    // dedup, dating and sorting outcomes independent of low-res routing. The marker prefix
    // keeps otherwise-identical padding distinguishable by hash within one test.
    private static String padded(final String marker) {
        return marker + "x".repeat(60_000);
    }

    private static void setMtime(final Path file, final LocalDateTime when) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(when.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private static void writeSidecar(final Path sidecar, final LocalDateTime photoTakenAt) throws IOException {
        final long epochSeconds = photoTakenAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        Files.writeString(sidecar, """
                {
                  "photoTakenTime": {
                    "timestamp": "%d"
                  }
                }""".formatted(epochSeconds));
    }
}
