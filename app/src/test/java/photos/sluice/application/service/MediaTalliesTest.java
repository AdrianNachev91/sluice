package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.config.SettingsFixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MediaTalliesTest {

    @TempDir
    private Path workingRoot;

    @TempDir
    private Path inboxRoot;

    @Test
    void theInboxCountsPhotosAndVideosAndLeavesEverythingElseOut() {
        write(this.inboxRoot.resolve("holiday.jpg"), 10);
        write(this.inboxRoot.resolve("nested/clip.mp4"), 20);
        write(this.inboxRoot.resolve("holiday.jpg.json"), 400);
        write(this.inboxRoot.resolve("notes.txt"), 800);

        final InboxTally waiting = this.tallies().inbox();

        assertThat(waiting.files()).isEqualTo(2);
        assertThat(waiting.bytes()).isEqualTo(30);
    }

    @Test
    void anInboxFolderThatIsNotThereIsEmptyRatherThanAFailure() {
        final InboxTally waiting = this.talliesWithInboxAt(this.inboxRoot.resolve("never-created")).inbox();

        assertThat(waiting.files()).isZero();
        assertThat(waiting.bytes()).isZero();
    }

    @Test
    void aYearCountsPhotosAndVideosSeparatelyFromTheTwoTreesTheySitIn() {
        this.staged("Photos/2019/06/a.jpg", "Photos/2019/06/b.heic", "Videos/2019/06/c.mp4");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.year()).isEqualTo(2019);
        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.videos()).isEqualTo(1);
        assertThat(row.total()).isEqualTo(3);
    }

    @Test
    void aMonthCountsPhotosAndVideosSeparatelyToo() {
        this.staged("Photos/2019/06/a.jpg", "Videos/2019/06/c.mp4", "Videos/2019/08/d.mp4");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.months())
                .extracting(MonthRow::month, MonthRow::photos, MonthRow::videos)
                .containsExactly(tuple(6, 1, 1), tuple(8, 0, 1));
    }

    @Test
    void yearsComeBackNewestFirst() {
        this.staged("Photos/2018/01/old.jpg", "Photos/2021/01/new.jpg", "Photos/2019/01/middle.jpg");

        assertThat(this.tallies().sorted().years().stream().map(YearRow::year))
                .containsExactly(2021, 2019, 2018);
    }

    @Test
    void monthsAreCountedSeparatelySoAScopeNarrowedToSomeOfThemCanBeSized() {
        this.staged("Photos/2019/06/a.jpg", "Photos/2019/06/b.jpg", "Photos/2019/08/c.jpg",
                "Photos/2019/11/d.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.photosIn(List.of(6, 8))).isEqualTo(3);
        assertThat(row.photosIn(List.of(11))).isEqualTo(1);
        assertThat(row.photosIn(List.of(1))).isZero();
    }

    @Test
    void aPhotoSittingStraightInAYearFolderCountsForTheYearAndForNoMonth() {
        this.staged("Photos/2019/loose.jpg", "Photos/2019/06/filed.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.photosIn(List.of(6))).isEqualTo(1);
    }

    @Test
    void aVideoSittingStraightInAYearFolderCountsForTheYearAndForNoMonth() {
        this.staged("Videos/2019/loose.mp4", "Videos/2019/06/filed.mp4");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.videos()).isEqualTo(2);
        assertThat(row.months()).extracting(MonthRow::month, MonthRow::videos).containsExactly(tuple(6, 1));
    }

    @Test
    void aPhotoOutsideAnyYearFolderIsCountedNowhere() {
        this.staged("Photos/loose.jpg", "Photos/not-a-year/also-loose.jpg", "Photos/2019/06/filed.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.year()).isEqualTo(2019);
        assertThat(row.photos()).isEqualTo(1);
    }

    @Test
    void aFileFiledUnderTheWrongTreeForItsKindIsCountedNeitherWay() {
        this.staged("Photos/2019/06/clip.mp4", "Videos/2019/06/still.jpg", "Photos/2019/06/real.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.photos()).isEqualTo(1);
        assertThat(row.videos()).isZero();
    }

    @Test
    void aSortedTreeThatWasNeverWrittenHasNoYears() {
        assertThat(this.tallies().sorted().years()).isEmpty();
    }

    @Test
    void aMonthFolderThatIsNotAMonthLeavesItsPhotoCountedForTheYearOnly() {
        this.staged("Photos/2019/13/a.jpg", "Photos/2019/00/b.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.months()).isEmpty();
    }

    @Test
    void aFolderSpelledDifferentlyFromTheWayAScopeNamesItIsNotCountedUnderIt() {
        this.staged("Photos/2019/6/unpadded-month.jpg", "Photos/02019/06/padded-year.jpg",
                "Photos/2019/06/filed.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.year()).isEqualTo(2019);
        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.photosIn(List.of(6))).isEqualTo(1);
    }

    @Test
    void aYearFolderOfAnyWidthButFourIsNotAYearAScopeCouldName() {
        this.staged("Photos/999/06/short.jpg", "Photos/12019/06/long.jpg", "Photos/-999/06/signed.jpg",
                "Photos/2019/06/filed.jpg");

        final YearRow row = only(this.tallies().sorted());

        assertThat(row.year()).isEqualTo(2019);
        assertThat(row.photos()).isEqualTo(1);
    }

    private static YearRow only(final SortedTally tally) {
        assertThat(tally.years()).hasSize(1);
        return tally.years().getFirst();
    }

    private MediaTallies tallies() {
        return this.talliesWithInboxAt(this.inboxRoot);
    }

    private MediaTallies talliesWithInboxAt(final Path inbox) {
        return new MediaTallies(new NioMediaStore(),
                SettingsFixture.pathsConfig(this.workingRoot, this.workingRoot.resolve("Library"), inbox));
    }

    private void staged(final String... relativePaths) {
        for (final String relative : relativePaths) {
            write(this.workingRoot.resolve("Sorted").resolve(relative), 1);
        }
    }

    private static void write(final Path file, final int bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[bytes]);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the fixture " + file, e);
        }
    }
}
