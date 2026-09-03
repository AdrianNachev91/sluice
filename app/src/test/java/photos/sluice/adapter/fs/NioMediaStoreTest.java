package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.job.CancellationSignal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioMediaStoreTest {

    private final NioMediaStore store = new NioMediaStore();

    @Test
    void listFilesReturnsEveryRegularFileRecursivelyButNoDirectories(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("top.jpg"), "top");
        final Path nested = Files.createDirectories(root.resolve("2019").resolve("06"));
        Files.writeString(nested.resolve("nested.jpg"), "nested");

        final List<Path> files = this.store.listFiles(root);

        assertThat(files).containsExactlyInAnyOrder(root.resolve("top.jpg"), nested.resolve("nested.jpg"));
    }

    @Test
    void listFilesOnAnEmptyDirectoryReturnsEmpty(@TempDir final Path root) {
        assertThat(this.store.listFiles(root)).isEmpty();
    }

    @Test
    void listFilesAnswersATransferThatNeverLandedLikeAnyOtherFile(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("IMG_1234.jpg"), "a whole photo");
        final Path part = root.resolve("IMG_5678.jpg.sluice-part");
        Files.writeString(part, "half a photo");

        assertThat(this.store.listFiles(root)).contains(part);
        assertThat(MediaStore.isIncompleteTransfer(part)).isTrue();
        assertThat(MediaStore.isIncompleteTransfer(root.resolve("IMG_1234.jpg"))).isFalse();
    }

    // TRUNCATE_EXISTING is what makes the part file a fresh one. Without it a copy appends to what
    // an earlier abandoned run left under that name. The photo then lands with a tail.
    @Test
    void aCopyOverAPartFileLeftByAnEarlierRunWritesOnlyTheSourceBytes(@TempDir final Path root)
            throws IOException {
        final Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "new", StandardCharsets.UTF_8);
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("IMG_1234.jpg.sluice-part"), "leftover bytes from before",
                StandardCharsets.UTF_8);

        final Path landed = this.store.copy(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(Files.readString(landed, StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(this.store.listFiles(destDir)).containsExactly(landed);
    }

    // The loop reads before its first check, so an empty source never enters it. Real dumps hold
    // zero-byte files.
    @Test
    void aZeroByteFileStillLands(@TempDir final Path root) throws IOException {
        final Path source = Files.createFile(root.resolve("empty.jpg"));
        final Path destDir = Files.createDirectories(root.resolve("dest"));

        final Path landed = this.store.copy(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(landed).exists();
        assertThat(Files.size(landed)).isZero();
    }

    @Test
    void listFilesOnAMissingRootWrapsIoExceptionUnchecked(@TempDir final Path root) {
        final Path missing = root.resolve("does-not-exist");

        assertThatThrownBy(() -> this.store.listFiles(missing)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void moveCreatesDestDirAndPlacesFileUnderOriginalName(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "photo bytes", StandardCharsets.UTF_8);
        final Path destDir = root.resolve("Sorted").resolve("2019").resolve("06");

        final Path dest = this.store.move(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234.jpg"));
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("photo bytes");
    }

    @Test
    void moveResolvesCollisionByAppendingNumberBeforeExtension(@TempDir final Path root) throws IOException {
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("IMG_1234.jpg"), "existing");
        Files.writeString(destDir.resolve("IMG_1234 (2).jpg"), "existing2");
        final Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "incoming");

        final Path dest = this.store.move(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234 (3).jpg"));
        assertThat(Files.readString(dest)).isEqualTo("incoming");
        assertThat(Files.readString(destDir.resolve("IMG_1234.jpg"))).isEqualTo("existing");
        assertThat(Files.readString(destDir.resolve("IMG_1234 (2).jpg"))).isEqualTo("existing2");
    }

    // On a case-insensitive volume, Files.exists folds the two names to one lookup, so the second
    // move lands on the collision path instead of silently overwriting the first. See
    // docs/plans/macos-verification-plan.md for why this only proves anything on such a volume.
    @Test
    void moveDoesNotOverwriteAFileWhoseNameDiffersOnlyInCase(@TempDir final Path root) throws IOException {
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Assumptions.assumeTrue(isCaseInsensitive(destDir),
                "Filesystem is case-sensitive; the collision this test targets cannot occur here.");
        // Two different source directories. The case fold this test targets should happen only
        // once the files land in the shared destination, not a moment earlier between the sources.
        final Path from1 = Files.createDirectories(root.resolve("from1"));
        final Path from2 = Files.createDirectories(root.resolve("from2"));
        final Path first = from1.resolve("photo.jpg");
        Files.writeString(first, "first");
        final Path second = from2.resolve("PHOTO.jpg");
        Files.writeString(second, "second");

        final Path firstDest = this.store.move(first, destDir, CancellationSignal.NEVER, TransferProgress.NONE);
        final Path secondDest = this.store.move(second, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(firstDest).isEqualTo(destDir.resolve("photo.jpg"));
        assertThat(secondDest).isEqualTo(destDir.resolve("PHOTO (2).jpg"));
        assertThat(Files.readString(firstDest)).isEqualTo("first");
        assertThat(Files.readString(secondDest)).isEqualTo("second");
    }

    @Test
    void moveHandlesFilenameWithNoExtension(@TempDir final Path root) throws IOException {
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("README"), "existing");
        final Path source = root.resolve("README");
        Files.writeString(source, "incoming");

        final Path dest = this.store.move(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(dest).isEqualTo(destDir.resolve("README (2)"));
    }

    @Test
    void copyLeavesSourceInPlace(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "photo bytes");
        final Path destDir = root.resolve("dest");

        final Path dest = this.store.copy(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.readString(dest)).isEqualTo("photo bytes");
    }

    @Test
    void copyResolvesCollisionSameAsMove(@TempDir final Path root) throws IOException {
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("IMG_1234.jpg"), "existing");
        final Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "incoming");

        final Path dest = this.store.copy(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234 (2).jpg"));
        assertThat(Files.readString(destDir.resolve("IMG_1234.jpg"))).isEqualTo("existing");
    }

    @Test
    void collisionOnADotfileAppendsSuffixToTheWholeName(@TempDir final Path root) throws IOException {
        final Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve(".gitignore"), "existing");
        final Path source = root.resolve(".gitignore");
        Files.writeString(source, "incoming");

        final Path dest = this.store.move(source, destDir, CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(dest).isEqualTo(destDir.resolve(".gitignore (2)"));
    }

    @Test
    void moveMissingSourceWrapsIoExceptionUnchecked(@TempDir final Path root) {
        final Path missing = root.resolve("does-not-exist.jpg");
        final Path destDir = root.resolve("dest");

        assertThatThrownBy(() -> this.store.move(missing, destDir, CancellationSignal.NEVER, TransferProgress.NONE))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void copyMissingSourceWrapsIoExceptionUnchecked(@TempDir final Path root) {
        final Path missing = root.resolve("does-not-exist.jpg");
        final Path destDir = root.resolve("dest");

        assertThatThrownBy(() -> this.store.copy(missing, destDir, CancellationSignal.NEVER, TransferProgress.NONE))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void deleteRemovesFile(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("junk.jpg");
        Files.writeString(file, "junk");

        this.store.delete(file);

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void deleteMissingFileWrapsIoExceptionUnchecked(@TempDir final Path root) {
        final Path missing = root.resolve("does-not-exist.jpg");

        assertThatThrownBy(() -> this.store.delete(missing)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void ensureDirectoryCreatesNestedPathAndIsIdempotent(@TempDir final Path root) {
        final Path dir = root.resolve("a").resolve("b").resolve("c");

        this.store.ensureDirectory(dir);
        this.store.ensureDirectory(dir);

        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void existsIsTrueForARealFileAndFalseOtherwise(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("present.jpg");
        Files.writeString(file, "bytes");
        final Path missing = root.resolve("absent.jpg");

        assertThat(this.store.exists(file)).isTrue();
        assertThat(this.store.exists(missing)).isFalse();
    }

    @Test
    void directoryIsThereIsTrueForAFolder(@TempDir final Path root) throws IOException {
        assertThat(this.store.directoryIsThere(Files.createDirectory(root.resolve("folder")))).isTrue();
    }

    @Test
    void directoryIsThereIsFalseForAPathNamingNothing(@TempDir final Path root) {
        assertThat(this.store.directoryIsThere(root.resolve("absent"))).isFalse();
    }

    @Test
    void directoryIsThereIsFalseForARegularFile(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("file.jpg");
        Files.writeString(file, "bytes");

        assertThat(this.store.directoryIsThere(file)).isFalse();
    }

    @Test
    void realDirectoryResolvesAFolderThatIsThere(@TempDir final Path root) throws IOException {
        final Path dir = Files.createDirectory(root.resolve("folder"));

        assertThat(this.store.realDirectory(dir)).contains(dir.toRealPath());
    }

    @Test
    void realDirectoryIsEmptyForAFolderThatIsNotThere(@TempDir final Path root) {
        assertThat(this.store.realDirectory(root.resolve("absent"))).isEmpty();
    }

    // A configured root naming a file is a user mistake this has to report as absence, not resolve.
    @Test
    void realDirectoryIsEmptyForARegularFile(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("file.jpg");
        Files.writeString(file, "bytes");

        assertThat(this.store.realDirectory(file)).isEmpty();
    }

    // Resolving through the link is what lets two configured roots naming one folder be compared as
    // plain paths. Windows junctions behave the same way.
    @Test
    void realDirectoryFollowsALinkToItsTarget(@TempDir final Path root) throws IOException {
        final Path target = Files.createDirectory(root.resolve("target"));
        final Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (final IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not supported in this environment: " + e.getMessage());
        }

        assertThat(this.store.realDirectory(link)).contains(target.toRealPath());
    }

    @Test
    void sizeReturnsByteCount(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("file.jpg");
        Files.writeString(file, "12345", StandardCharsets.UTF_8);

        assertThat(this.store.size(file)).isEqualTo(5);
    }

    @Test
    void appendLineCreatesFileOnFirstCallThenAppendsOnSubsequentCalls(@TempDir final Path root) {
        final Path file = root.resolve("_reasons.txt");

        this.store.appendLine(file, "IMG_0001.jpg - low-res");
        this.store.appendLine(file, "IMG_0002.jpg - unsorted-implausible-date");

        assertThat(Files.exists(file)).isTrue();
        final List<String> lines = readLines(file);
        assertThat(lines).containsExactly(
                "IMG_0001.jpg - low-res", "IMG_0002.jpg - unsorted-implausible-date");
    }

    @Test
    void writeCreatesFileOnFirstCallThenReplacesItsWholeContentOnSubsequentCalls(@TempDir final Path root) {
        final Path file = root.resolve("chosen.jpg.txt");

        this.store.write(file, "Chose a.jpg - sharpest. Rejects: b.jpg - blurred");
        this.store.write(file, "Chose a.jpg - sharpest. Rejects: b.jpg - blurred, c.jpg - also blurred");

        assertThat(readLines(file)).containsExactly(
                "Chose a.jpg - sharpest. Rejects: b.jpg - blurred, c.jpg - also blurred");
    }

    @Test
    void readLinesReturnsEveryLineInOrder(@TempDir final Path root) {
        final Path file = root.resolve("applied.log");

        this.store.appendLine(file, "IMG_0001.jpg");
        this.store.appendLine(file, "IMG_0002.jpg");

        assertThat(this.store.readLines(file)).containsExactly("IMG_0001.jpg", "IMG_0002.jpg");
    }

    @Test
    void readLinesOnAMissingFileReturnsEmpty(@TempDir final Path root) {
        final Path missing = root.resolve("applied.log");

        assertThat(this.store.readLines(missing)).isEmpty();
    }

    // A caller that degrades around damaged content keys off this exact cause to tell it apart from
    // a file that is merely locked. So holding it is the adapter's job, not a side effect of how a
    // line happens to get decoded. Substituting replacement characters instead would look like a
    // successful read of a file whose real content is gone.
    @Test
    void readLinesOnBytesThatAreNotUtf8ThrowsWithADecodeFailureCause(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("choices.log");
        // 0xFF and 0xFE are not legal UTF-8 lead bytes.
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});

        assertThatThrownBy(() -> this.store.readLines(file))
                .isInstanceOf(UncheckedIOException.class)
                .cause().isInstanceOf(CharacterCodingException.class);
    }

    @Test
    void removeEmptyDirectoriesCollapsesNestedEmptyChainBottomUp(@TempDir final Path root) throws IOException {
        final Path nested = Files.createDirectories(root.resolve("Takeout").resolve("Google Photos").resolve("2019-06"
        ));

        this.store.removeEmptyDirectories(root);

        assertThat(Files.exists(nested)).isFalse();
        assertThat(Files.exists(root.resolve("Takeout").resolve("Google Photos"))).isFalse();
        assertThat(Files.exists(root.resolve("Takeout"))).isFalse();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void removeEmptyDirectoriesLeavesADirectoryWithAFileInPlace(@TempDir final Path root) throws IOException {
        final Path keptDir = Files.createDirectories(root.resolve("keep"));
        Files.writeString(keptDir.resolve("leftover.txt"), "not media");
        final Path emptyDir = Files.createDirectories(root.resolve("empty"));

        this.store.removeEmptyDirectories(root);

        assertThat(Files.exists(keptDir)).isTrue();
        assertThat(Files.exists(emptyDir)).isFalse();
    }

    @Test
    void removeEmptyDirectoriesLeavesAnAncestorInPlaceWhenADeeperSiblingStillHasAFile(@TempDir final Path root) throws IOException {
        final Path emptyBranch = Files.createDirectories(root.resolve("album").resolve("empty-sub"));
        final Path fileBranch = Files.createDirectories(root.resolve("album").resolve("has-file"));
        Files.writeString(fileBranch.resolve("leftover.txt"), "not media");

        this.store.removeEmptyDirectories(root);

        assertThat(Files.exists(emptyBranch)).isFalse();
        assertThat(Files.exists(fileBranch)).isTrue();
        assertThat(Files.exists(root.resolve("album"))).isTrue();
    }

    @Test
    void removeIfEmptyOfFilesDeletesDirAndNestedEmptySubtreeWhenNoFileRemains(@TempDir final Path root) throws IOException {
        final Path target = Files.createDirectories(root.resolve("2019-06").resolve("empty-sub"));

        this.store.removeIfEmptyOfFiles(root.resolve("2019-06"));

        assertThat(Files.exists(target)).isFalse();
        assertThat(Files.exists(root.resolve("2019-06"))).isFalse();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void removeIfEmptyOfFilesLeavesDirInPlaceWhenAFileRemainsAnywhereBelow(@TempDir final Path root) throws IOException {
        final Path target = Files.createDirectories(root.resolve("2019-06"));
        final Path nested = Files.createDirectories(target.resolve("nested"));
        Files.writeString(nested.resolve("leftover.jpg"), "keeper", StandardCharsets.UTF_8);

        this.store.removeIfEmptyOfFiles(target);

        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.exists(target.resolve("nested").resolve("leftover.jpg"))).isTrue();
    }

    @Test
    void removeIfEmptyOfFilesOnAMissingDirIsANoOp(@TempDir final Path root) {
        final Path missing = root.resolve("does-not-exist");

        this.store.removeIfEmptyOfFiles(missing);

        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void copyToWritesTheFileAtExactlyTheNameItWasGiven(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("holiday.jpg"), "holiday");

        final Path written = this.store.copyTo(root.resolve("holiday.jpg"),
                root.resolve("2019").resolve("holiday.jpg.sluice-part"), CancellationSignal.NEVER,
                TransferProgress.NONE);

        assertThat(written).isEqualTo(root.resolve("2019").resolve("holiday.jpg.sluice-part"));
        assertThat(written).hasContent("holiday");
        assertThat(root.resolve("holiday.jpg")).hasContent("holiday");
    }

    // An import rests the safety of its part files on this refusal.
    @Test
    void copyToRefusesANameAlreadyTakenRatherThanReplacingIt(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("holiday.jpg"), "the one being copied");
        Files.writeString(root.resolve("taken.jpg"), "the one already there");

        assertThatThrownBy(() -> this.store.copyTo(root.resolve("holiday.jpg"), root.resolve("taken.jpg"),
                CancellationSignal.NEVER, TransferProgress.NONE))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(root.resolve("taken.jpg")).hasContent("the one already there");
    }

    @Test
    void aCopyCarriesTheSourcesLastModifiedTimeOverToTheFileItWrites(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("holiday.jpg");
        Files.writeString(source, "holiday");
        final FileTime taken = FileTime.from(Instant.parse("2019-07-04T10:15:30Z"));
        Files.setLastModifiedTime(source, taken);

        final Path written = this.store.copy(source, root.resolve("dest"),
                CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(Files.getLastModifiedTime(written)).isEqualTo(taken);
    }

    @Test
    void aCopySpanningManyBlocksLandsEveryByteOfIt(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("movie.mp4");
        final byte[] bytes = bytesOfLength(3_000_000);
        Files.write(source, bytes);

        final Path written = this.store.copy(source, root.resolve("dest"),
                CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(Files.readAllBytes(written)).isEqualTo(bytes);
    }

    @Test
    void aCopyAbandonedPartWayLeavesTheDestinationDirectoryEmpty(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("movie.mp4");
        Files.write(source, bytesOfLength(3_000_000));
        final Path destDir = root.resolve("dest");

        assertThatThrownBy(() -> this.store.copy(source, destDir, abandonsAfter(1), TransferProgress.NONE))
                .isInstanceOf(TransferAbandonedException.class);

        try (final var landed = Files.list(destDir)) {
            assertThat(landed).isEmpty();
        }
        assertThat(Files.size(source)).isEqualTo(3_000_000);
    }

    @Test
    void aMoveAcrossFileStoresCopiesTheFileOverAndThenRemovesTheOriginal(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("holiday.jpg");
        Files.writeString(source, "holiday");

        final Path landed = acrossFileStores().moveTo(source, root.resolve("2019").resolve("holiday.jpg"),
                CancellationSignal.NEVER, TransferProgress.NONE);

        assertThat(landed).hasContent("holiday");
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    void aMoveAcrossFileStoresAbandonedPartWayLeavesTheOriginalWhereItWas(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("movie.mp4");
        Files.write(source, bytesOfLength(3_000_000));
        final Path destination = root.resolve("2019").resolve("movie.mp4");

        assertThatThrownBy(() -> acrossFileStores()
                .moveTo(source, destination, abandonsAfter(1), TransferProgress.NONE))
                .isInstanceOf(TransferAbandonedException.class);

        assertThat(Files.size(source)).isEqualTo(3_000_000);
        assertThat(Files.exists(destination)).isFalse();
    }

    @Test
    void aMoveWithinOneFileStoreLandsEvenWhileTheSignalIsAskingToAbandon(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("holiday.jpg");
        Files.writeString(source, "holiday");

        final Path landed = this.store.moveTo(source, root.resolve("2019").resolve("holiday.jpg"),
                abandonsAfter(0), TransferProgress.NONE);

        assertThat(landed).hasContent("holiday");
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    void listFilesToleratingAnswersEveryFileAndNoRefusalWhereItReadEverything(@TempDir final Path root)
            throws IOException {
        Files.writeString(root.resolve("top.jpg"), "top");
        Files.createDirectories(root.resolve("2019"));
        Files.writeString(root.resolve("2019").resolve("holiday.jpg"), "holiday");

        final NioMediaStore.Walk walk = this.store.listFilesTolerating(root);

        assertThat(walk.files()).containsExactlyInAnyOrder(
                root.resolve("top.jpg"), root.resolve("2019").resolve("holiday.jpg"));
        assertThat(walk.unreadablePlaces()).isEmpty();
    }

    @Test
    void aCopyReportsHowFarItHasGotAsItGoes(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("movie.mp4");
        Files.write(source, bytesOfLength(3_000_000));
        final var readings = new ArrayList<Long>();

        this.store.copy(source, root.resolve("dest"), CancellationSignal.NEVER,
                (written, size) -> {
                    assertThat(size).isEqualTo(3_000_000);
                    readings.add(written);
                });

        assertThat(readings).isNotEmpty().isSorted().last().isEqualTo(3_000_000L);
        assertThat(readings.getFirst()).isLessThan(3_000_000L);
    }

    @Test
    void aMoveWithinOneFileStoreReportsNothing(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("holiday.jpg");
        Files.writeString(source, "holiday");
        final var readings = new ArrayList<Long>();

        this.store.move(source, root.resolve("2019"), CancellationSignal.NEVER,
                (written, _) -> readings.add(written));

        assertThat(readings).isEmpty();
    }

    @Test
    void aMoveAcrossFileStoresReportsTheCopyUnderneathIt(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("movie.mp4");
        Files.write(source, bytesOfLength(3_000_000));
        final var readings = new ArrayList<Long>();

        acrossFileStores().move(source, root.resolve("2019"), CancellationSignal.NEVER,
                (written, _) -> readings.add(written));

        assertThat(readings).isNotEmpty().last().isEqualTo(3_000_000L);
    }

    @Test
    void aZeroByteFileIsNotReportedAtAll(@TempDir final Path root) throws IOException {
        final Path source = Files.createFile(root.resolve("empty.jpg"));
        final var readings = new ArrayList<Long>();

        this.store.copy(source, root.resolve("dest"), CancellationSignal.NEVER,
                (written, _) -> readings.add(written));

        assertThat(readings).isEmpty();
    }

    // Probes the real filesystem instead of checking the OS name, since a case-sensitive volume can
    // be mounted on any platform.
    private static boolean isCaseInsensitive(final Path dir) throws IOException {
        final Path lower = dir.resolve("case_probe.tmp");
        Files.writeString(lower, "probe");
        try {
            return Files.exists(dir.resolve("CASE_PROBE.TMP"));
        } finally {
            Files.delete(lower);
        }
    }

    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] bytesOfLength(final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    // Answering true on the first ask would only ever prove a copy that wrote nothing at all. The
    // count is what puts real bytes in the part file before the escalation lands on it.
    private static CancellationSignal abandonsAfter(final int asks) {
        final AtomicInteger asked = new AtomicInteger();
        return new CancellationSignal() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public boolean isAbandonRequested() {
                return asked.getAndIncrement() >= asks;
            }
        };
    }

    private static NioMediaStore acrossFileStores() {
        return new NioMediaStore() {
            @Override
            boolean sameFileStore(final Path source, final Path destinationDir) {
                return false;
            }
        };
    }
}
