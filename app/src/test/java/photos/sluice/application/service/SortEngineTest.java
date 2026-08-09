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
import photos.sluice.config.SettingsFixture;
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
        // Simulates a changed Takeout export shape: a JSON sidecar exists (takeoutMode true), but
        // its name doesn't prefix-match the media file's, so nothing pairs. Before this fix, that
        // failure mode was silent - every file just falls through to mtime with no warning at all.
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
        // TakeoutSidecarPairer maps an "-edited" copy to its original's sidecar. Both media files
        // in scope resolve their date via the same JSON path, so it must be consumed once, not
        // once per file.
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

        // Mechanism 1 (inline consumption) never touches it. Its date came from filename, not
        // this sidecar, so sidecarsDeleted stays 0. But the whole-Inbox sweep (mechanism 2) is
        // independent of why the media left. Once the photo is sorted away, nothing in the
        // sidecar's directory owns it anymore, so the sweep deletes it anyway.
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

    // The other half of the incremental promise. The sidecar the first run correctly left behind
    // gets swept by the run that finally takes its media, not consumed inline. The sidecar is
    // invalid JSON, so it cannot win the date race - the filename source wins instead. Only
    // mechanism 2 (the whole-Inbox sweep) can be what deletes it, proving a later run sweeps it
    // rather than letting it pile up.
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
        // Invalid, so mechanism 1 never wins the date-resolution race for it. Any cleanup here can
        // only be mechanism 2 (the sweep) noticing the media is gone, not the inline consumption.
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
                .contains("20190615_tiny.jpg - low-res");
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
                .contains("nodatepattern.jpg - unsorted-implausible-date");
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

    // The property CurateEngine leans on to read "the" year off a summary. Both scopes narrow to
    // one year before anything is routed, so a second year cannot reach Sorted in the same run.
    @Test
    void anOldestYearSortReportsOnlyTheYearItPicked(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20180101_a.jpg"), padded("older"));
        writeFile(inbox.resolve("20190101_b.jpg"), padded("newer"));

        final SortSummary summary = this.sortEngine(root).sort(new SortScope.OldestYear());

        assertThat(summary.yearsSorted()).containsExactly(2018);
    }

    // Mirrors anOldestYearSortReportsOnlyTheYearItPicked for the other scope shape that narrows to
    // one year up front: an explicit Year scope, over the same two-year fixture.
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
        // One file engineered per outcome bucket, run together. This checks the
        // processed-equals-sum-of-buckets invariant against a real mixed batch, not six isolated
        // single-file cases. Isolated cases could each pass independently while a cross-file
        // interaction - e.g. one file's routing changing another's counters - went unnoticed.
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
        // implausible mtime (pre-2000), which DateResolver demotes to UNSORTABLE. Its resolved
        // date is therefore 1990, not 2019 like every other file here. That's why the scope below
        // is OldestN(7) rather than Year(2019, null) or OldestYear(). A year-based scope would
        // select only this one file - the actual oldest year present - and leave the 2019 files
        // out of the batch entirely.
        final Path unsortedFile = inbox.resolve("nodatepattern.jpg");
        writeFile(unsortedFile, padded("mystery"));
        setMtime(unsortedFile, LocalDateTime.of(1990, 1, 1, 0, 0));

        // Buckets 5 and 6: an ordinary photo keeper and an ordinary video keeper.
        writeFile(inbox.resolve("20190104_photo.jpg"), padded("photo"));
        writeFile(inbox.resolve("20190105_video.mp4"), "video");

        // All 7 files fit within OldestN(7), so every one is in scope regardless of the 1990/2019
        // date spread. No truncation, no ordering to reason about.
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
    void progressCallbackTicksOnceForEachSurvivorAgainstTheFinalSortedCount(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        final List<String> ticks = new ArrayList<>();
        this.sortEngine(root).sort(new SortScope.OldestYear(), (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancelMidDatingAbortsCleanlyWithNothingMovedOrDeleted(@TempDir final Path root) throws IOException {
        final Path inbox = inboxOf(root);
        writeFile(inbox.resolve("20210101_a.jpg"), padded("a"));
        writeFile(inbox.resolve("20210102_b.jpg"), padded("b"));

        // Dating is checked before each file, so the first poll (for file 1) still runs and the
        // second (before file 2) cancels - proving the abort happens mid-pass, not merely when
        // already cancelled going in.
        final AtomicInteger polls = new AtomicInteger();
        final CancellationSignal cancelBeforeSecondFile = () -> polls.incrementAndGet() > 1;

        final SortSummary summary =
                this.sortEngine(root).sort(new SortScope.OldestYear(), ProgressCallback.NO_OP, cancelBeforeSecondFile);

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

        // Cancels once the first survivor's move has already ticked, so routing stops before the
        // remaining two are even looked at.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final SortSummary summary =
                this.sortEngine(root).sort(new SortScope.OldestYear(), cancelAfterFirstTick, cancelled::get);

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

        // Cancels once the first survivor routed, whichever of a/b that turns out to be - scan
        // order is filesystem-dependent, not alphabetical. Sidecar consumption must only spend the
        // routed file's sidecar; the other file's media never left the Inbox, so its own sidecar
        // has to survive for a future run.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final SortSummary summary =
                this.sortEngine(root).sort(new SortScope.OldestYear(), cancelAfterFirstTick, cancelled::get);

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
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final SortSummary summary =
                this.sortEngine(root).sort(new SortScope.OldestYear(), cancelAfterFirstTick, cancelled::get);

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
        // app's JSON. Neither ever described a photo, so neither is the sweep's to delete - not
        // even in an album the run emptied of media.
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

    private static Path inboxOf(final Path root) {
        return root.resolve("Inbox");
    }

    private SortEngine sortEngine(final Path root) {
        return this.sortEngine(root, new CsvLibraryHashIndex(SettingsFixture.workingRoot(root)));
    }

    private SortEngine sortEngine(final Path root, final HashIndexPort hashIndex) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root, root.resolve("Inbox"));
        return new SortEngine(pathsConfig, this.inboxScanner, this.dateResolver, this.sha256Port, hashIndex,
                this.imageDimensionsPort, this.mediaStore);
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

    // 60,000 bytes clears LowResGate's 50KB threshold. A fixture built from this never gets
    // classified as low-res, even though its content is garbage, not a real image. Most tests
    // here aren't testing low-res routing. They use this fixture instead of real image bytes to
    // keep other outcomes (dedup, dating, sorting) independent of it. The marker prefix keeps
    // otherwise-identical padding distinguishable by content/hash within one test.
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
