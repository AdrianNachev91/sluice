package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.MediaFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RescueDateResolverTest {

    @Test
    void aDashedMonthFolderWinsEvenWhenExifAndFilenameDisagree() {
        final var resolver = new RescueDateResolver(
                stub(LocalDateTime.of(2099, 1, 1, 0, 0)), stub(LocalDateTime.of(2018, 3, 4, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_20180304.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "2019-06/IMG_20180304.jpg", null);

        assertThat(result).contains(LocalDateTime.of(2019, 6, 1, 0, 0));
    }

    @Test
    void aYearFolderHoldingMonthFoldersDatesAFileTheSameWay() {
        final var resolver = new RescueDateResolver(
                stub(LocalDateTime.of(2099, 1, 1, 0, 0)), stub(LocalDateTime.of(2018, 3, 4, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_20180304.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "2019/06/IMG_20180304.jpg", null);

        assertThat(result).contains(LocalDateTime.of(2019, 6, 1, 0, 0));
    }

    @Test
    void aMonthFolderSittingAnywhereInThePathStillCounts() {
        final var resolver = new RescueDateResolver(
                stub(LocalDateTime.of(2099, 1, 1, 0, 0)), stub(LocalDateTime.of(2018, 3, 4, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_20180304.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "Unsorted/2019/06/IMG_20180304.jpg", null);

        assertThat(result).contains(LocalDateTime.of(2019, 6, 1, 0, 0));
    }

    @Test
    void aPathSegmentThatIsNotARealMonthFallsThroughRatherThanThrowing() {
        final var resolver = new RescueDateResolver(stub(LocalDateTime.of(2018, 3, 4, 12, 0)), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "2019-13/IMG_1.jpg", null);

        assertThat(result).contains(LocalDateTime.of(2018, 3, 4, 12, 0));
    }

    @Test
    void aResolvedDateBeforeTheYearTwoThousandIsRejectedRatherThanReturned() {
        final var resolver = new RescueDateResolver(stub(null), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "1999-06/IMG_1.jpg", null)).isEmpty();
    }

    @Test
    void aResolvedDateInTheFutureIsRejectedRatherThanReturned() {
        final var resolver = new RescueDateResolver(stub(LocalDateTime.now().plusYears(1)), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "Food/IMG_1.jpg", null)).isEmpty();
    }

    @Test
    void undatedFolderFallsThroughToExifThenFilename() {
        final var exifWins = new RescueDateResolver(stub(LocalDateTime.of(2018, 3, 4, 12, 0)), stub(null));
        final var filenameWins = new RescueDateResolver(stub(null), stub(LocalDateTime.of(2017, 5, 6, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(exifWins.resolve(file, "Food", null)).contains(LocalDateTime.of(2018, 3, 4, 12, 0));
        assertThat(filenameWins.resolve(file, "Food", null)).contains(LocalDateTime.of(2017, 5, 6, 0, 0));
    }

    @Test
    void noSourceProducingADateResolvesToEmpty() {
        final var resolver = new RescueDateResolver(stub(null), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "Food", null)).isEmpty();
    }

    @Test
    void anImplausibleFilenameDateIsRejectedRatherThanReturned() {
        final var resolver = new RescueDateResolver(stub(null), stub(LocalDateTime.of(1990, 1, 1, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "Food", null)).isEmpty();
    }

    @Test
    void aNotedDateBeatsTheFolderTheFileSitsIn() {
        final var resolver = new RescueDateResolver(
                stub(LocalDateTime.of(2018, 3, 4, 0, 0)), stub(LocalDateTime.of(2017, 5, 6, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "2019-06/IMG_1.jpg",
                LocalDateTime.of(2020, 8, 9, 0, 0));

        assertThat(result).contains(LocalDateTime.of(2020, 8, 9, 0, 0));
    }

    @Test
    void anImplausibleNotedDateFallsThroughToTheRestOfTheChain() {
        final var resolver = new RescueDateResolver(stub(LocalDateTime.of(2018, 3, 4, 0, 0)), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "Food/IMG_1.jpg",
                LocalDateTime.of(1990, 1, 1, 0, 0));

        assertThat(result).contains(LocalDateTime.of(2018, 3, 4, 0, 0));
    }

    // Null stands for a source that finds nothing, which is what most of these cases turn on.
    // @Nullable rather than an overload, so a caller reads which of the two it is asking for.
    private static DateSource stub(final @Nullable LocalDateTime when) {
        return (_, _) -> Optional.ofNullable(when);
    }
}
