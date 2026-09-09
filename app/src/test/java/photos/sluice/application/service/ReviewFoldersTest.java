package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.ReviewListing.FiledBy;
import photos.sluice.application.port.in.ReviewListing.Folder;
import photos.sluice.application.port.in.ReviewListing.Root;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.SettingsFixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ReviewFoldersTest {

    @TempDir
    private Path workingRoot;

    @Test
    void aReviewFolderIsNamedByItsOwnLeafAndCountsThePhotosAndVideosInIt() {
        this.file("Review/2019-06/a.jpg");
        this.file("Review/2019-06/b.heic");
        this.file("Review/2019-06/c.mp4");

        final Folder folder = only(this.folders().list());

        assertThat(folder.root()).isEqualTo(Root.REVIEW);
        assertThat(folder.name()).isEqualTo("2019-06");
        assertThat(folder.photos()).isEqualTo(2);
        assertThat(folder.videos()).isEqualTo(1);
    }

    @Test
    void theNoteBesideThePhotosIsNotCountedAsOneOfThem() {
        this.file("Review/Food/a.jpg");
        this.file("Review/Food/_reasons.txt");

        assertThat(only(this.folders().list()).photos()).isEqualTo(1);
    }

    @Test
    void aHalfWrittenTransferIsNotCountedAndCannotPutAFolderOnTheList() {
        this.file("Review/Food/a.jpg");
        this.file("Review/2019-06/half.jpg" + MediaStore.INCOMPLETE_TRANSFER_SUFFIX);

        assertThat(this.folders().list().folders()).extracting(Folder::name)
                .containsExactly("Food");
    }

    // Unreviewable keeps the year-and-month shape, unlike the two flat roots beside it. A row per
    // year would name a folder holding no files.
    @Test
    void anUnreviewableFolderIsNamedByBothLevelsBelowItsRoot() {
        this.file("Unreviewable/2019/06/a.jpg");

        assertThat(only(this.folders().list()).name()).isEqualTo("2019/06");
    }

    @Test
    void allThreeRootsAreListedTogether() {
        this.file("Review/Food/a.jpg");
        this.file("Duplicates/2019-06_beach/b.jpg");
        this.file("Unreviewable/2019/06/c.jpg");

        assertThat(this.folders().list().folders())
                .extracting(Folder::root, Folder::name)
                .containsExactlyInAnyOrder(tuple(Root.REVIEW, "Food"),
                        tuple(Root.DUPLICATES, "2019-06_beach"),
                        tuple(Root.UNREVIEWABLE, "2019/06"));
    }

    @Test
    void aDatedFolderAndUnsortedAreTheOnesASortFiled() {
        this.file("Review/2019-06/a.jpg");
        this.file("Review/Unsorted/b.jpg");
        this.file("Review/Food/c.jpg");
        this.file("Review/2019-06_beach/d.jpg");

        assertThat(this.folders().list().folders())
                .extracting(Folder::name, Folder::filedBy)
                .containsExactlyInAnyOrder(tuple("2019-06", FiledBy.A_SORT),
                        tuple("Unsorted", FiledBy.A_SORT),
                        tuple("Food", FiledBy.A_SIFT),
                        tuple("2019-06_beach", FiledBy.A_SIFT));
    }

    @Test
    void aFolderNamedTheWayASortNamesOneIsStillASiftsUnderAnotherRoot() {
        this.file("Duplicates/2019-06/a.jpg");
        this.file("Unreviewable/Unsorted/b.jpg");

        assertThat(this.folders().list().folders()).extracting(Folder::filedBy)
                .containsExactly(FiledBy.A_SIFT, FiledBy.A_SIFT);
    }

    @Test
    void theNewestFolderComesFirst() {
        this.file("Review/older/a.jpg");
        this.file("Review/newer/b.jpg");
        this.touched("Review/older", Instant.parse("2020-01-01T00:00:00Z"));
        this.touched("Review/newer", Instant.parse("2024-01-01T00:00:00Z"));

        assertThat(this.folders().list().folders()).extracting(Folder::name)
                .containsExactly("newer", "older");
    }

    @Test
    void aRootThatWasNeverWrittenIsEmptyRatherThanUnreadable() {
        final ReviewListing listing = this.folders().list();

        assertThat(listing.folders()).isEmpty();
        assertThat(listing.unreadable()).isEmpty();
    }

    // No real filesystem can be made to refuse one directory and not its sibling on every platform
    // this runs on. So the refusal is the one thing standing in for the real reader here.
    @Test
    void aRootThatCannotBeReadIsNamedAndTheOthersStillList() {
        this.file("Review/Food/a.jpg");
        final Path duplicates = this.workingRoot.resolve("Duplicates");
        this.file("Duplicates/2019-06_beach/b.jpg");

        final ReviewListing listing = this.foldersRefusing(duplicates).list();

        assertThat(listing.folders()).extracting(Folder::name).containsExactly("Food");
        assertThat(listing.unreadable()).containsExactly(duplicates);
    }

    @Test
    void aRootTheFilesystemWillNotDescribeIsNamedRatherThanTreatedAsNeverWritten() {
        this.file("Review/Food/a.jpg");
        final Path duplicates = this.workingRoot.resolve("Duplicates");
        this.file("Duplicates/2019-06_beach/b.jpg");

        final ReviewListing listing = this.foldersSilentAbout(duplicates).list();

        assertThat(listing.folders()).extracting(Folder::name).containsExactly("Food");
        assertThat(listing.unreadable()).containsExactly(duplicates);
    }

    @Test
    void aRootThatIsAFileRatherThanAFolderContributesNoRows() {
        this.file("Review/Food/a.jpg");
        this.file("Duplicates");

        final ReviewListing listing = this.folders().list();

        assertThat(listing.folders()).extracting(Folder::name).containsExactly("Food");
        assertThat(listing.unreadable()).isEmpty();
    }

    @Test
    void aFolderWeededDownToItsNoteFileAloneLeavesTheListing() {
        this.file("Review/Food/a.jpg");
        this.file("Review/junk/_reasons.txt");

        assertThat(this.folders().list().folders()).extracting(Folder::name).containsExactly("Food");
    }

    @Test
    void aFolderStillHoldingOneVideoStays() {
        this.file("Review/Food/a.mp4");

        assertThat(this.folders().list().folders()).extracting(Folder::name).containsExactly("Food");
    }

    @Test
    void aFileLyingLooseInARootPutsNoFolderOnTheList() {
        this.file("Review/loose.jpg");

        assertThat(this.folders().list().folders()).isEmpty();
    }

    @Test
    void theNotesInAFolderComeBackLineByLine() {
        this.file("Review/Food/a.jpg");
        this.lines("Review/Food/_reasons.txt", "a.jpg - food", "b.jpg - food");

        assertThat(this.folders().notesIn(this.workingRoot.resolve("Review").resolve("Food")))
                .containsExactly("a.jpg - food", "b.jpg - food");
    }

    @Test
    void everyNoteInAFolderIsRead() {
        this.lines("Duplicates/2019-06_beach/first.jpg.txt", "Chose first.jpg");
        this.lines("Duplicates/2019-06_beach/second.jpg.txt", "Chose second.jpg");

        assertThat(this.folders().notesIn(
                this.workingRoot.resolve("Duplicates").resolve("2019-06_beach")))
                .containsExactly("Chose first.jpg", "Chose second.jpg");
    }

    // Sorting first, README.txt is what the note link would otherwise open.
    @Test
    void aReadersOwnTextFileIsNotReadAsANote() {
        this.lines("Review/Food/README.txt", "my own jottings");
        this.lines("Review/Food/_reasons.txt", "a.jpg - food");

        assertThat(this.folders().noteFilesIn(this.workingRoot.resolve("Review").resolve("Food")))
                .containsExactly(this.workingRoot.resolve("Review").resolve("Food").resolve("_reasons.txt"));
    }

    @Test
    void aFolderWithNoNoteBesideItsPhotosAnswersWithNothing() {
        this.file("Review/Food/a.jpg");

        assertThat(this.folders().notesIn(this.workingRoot.resolve("Review").resolve("Food")))
                .isEmpty();
    }

    @Test
    void aFolderOutsideTheThreeRootsIsRefusedRatherThanRead() {
        assertThatThrownBy(() -> this.folders().notesIn(this.workingRoot.resolve("Sorted")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sorted");
    }

    @Test
    void aPathClimbingOutOfARootIsRefusedHoweverItIsSpelled() {
        assertThatThrownBy(() -> this.folders()
                .notesIn(this.workingRoot.resolve("Review").resolve("..").resolve("Sorted")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFolderThatWentAwayBetweenTheListingAndTheReadHasNoNotes() {
        assertThat(this.folders().notesIn(this.workingRoot.resolve("Review").resolve("gone")))
                .isEmpty();
    }

    private static Folder only(final ReviewListing listing) {
        assertThat(listing.folders()).hasSize(1);
        return listing.folders().getFirst();
    }

    private ReviewFolders folders() {
        return this.over(new NioMediaStore());
    }

    private ReviewFolders foldersRefusing(final Path root) {
        return this.over(new RefusesOneRoot(new NioMediaStore(), root, Refusal.ONE_LEVEL_DOWN));
    }

    private ReviewFolders foldersSilentAbout(final Path root) {
        return this.over(new RefusesOneRoot(new NioMediaStore(), root, Refusal.AT_THE_ROOT));
    }

    private ReviewFolders over(final MediaReader media) {
        return new ReviewFolders(media, SettingsFixture.pathsConfig(this.workingRoot,
                this.workingRoot.resolve("Library"), this.workingRoot.resolve("Inbox")));
    }

    private enum Refusal { ONE_LEVEL_DOWN, AT_THE_ROOT }

    // The real reader everywhere but the one refused root.
    private record RefusesOneRoot(MediaReader real, Path refused, Refusal refusal)
            implements MediaReader {

        @Override
        public List<Path> listFiles(final Path root) {
            if (this.refusal == Refusal.ONE_LEVEL_DOWN && root.equals(this.refused)) {
                throw new UncheckedIOException(new IOException(root + " is not readable"));
            }
            return this.real.listFiles(root);
        }

        @Override
        public Walk listFilesTolerating(final Path root) {
            return this.real.listFilesTolerating(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.real.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.real.lastModifiedTime(path);
        }

        @Override
        public boolean exists(final Path path) {
            return this.real.exists(path);
        }

        @Override
        public boolean directoryIsThere(final Path path) {
            if (this.refusal == Refusal.AT_THE_ROOT && path.equals(this.refused)) {
                throw new UncheckedIOException(new IOException(path + " will not say what is there"));
            }
            return this.real.directoryIsThere(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.real.realDirectory(path);
        }

        @Override
        public Path realFile(final Path path) {
            return this.real.realFile(path);
        }

        @Override
        public long size(final Path path) {
            return this.real.size(path);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.real.readLines(file);
        }
    }

    private void file(final String relative) {
        final Path file = this.workingRoot.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[1]);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the fixture " + file, e);
        }
    }

    private void lines(final String relative, final String... written) {
        final Path file = this.workingRoot.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, List.of(written));
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the fixture " + file, e);
        }
    }

    private void touched(final String relative, final Instant when) {
        try {
            Files.setLastModifiedTime(this.workingRoot.resolve(relative), FileTime.from(when));
        } catch (final IOException e) {
            throw new UncheckedIOException("could not age the fixture " + relative, e);
        }
    }
}
