package photos.sluice.domain.dating;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.MediaFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RescueDateResolverTest {

    @Test
    void datedLeafWinsEvenWhenExifAndFilenameDisagree() {
        final var resolver = new RescueDateResolver(
                stub(LocalDateTime.of(2099, 1, 1, 0, 0)), stub(LocalDateTime.of(2018, 3, 4, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_20180304.jpg"));

        final Optional<LocalDateTime> result = resolver.resolve(file, "2019-06");

        assertThat(result).contains(LocalDateTime.of(2019, 6, 1, 0, 0));
    }

    @Test
    void undatedFolderFallsThroughToExifThenFilename() {
        final var exifWins = new RescueDateResolver(stub(LocalDateTime.of(2018, 3, 4, 12, 0)), stub(null));
        final var filenameWins = new RescueDateResolver(stub(null), stub(LocalDateTime.of(2017, 5, 6, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(exifWins.resolve(file, "Food")).contains(LocalDateTime.of(2018, 3, 4, 12, 0));
        assertThat(filenameWins.resolve(file, "Food")).contains(LocalDateTime.of(2017, 5, 6, 0, 0));
    }

    @Test
    void noSourceProducingADateResolvesToEmpty() {
        final var resolver = new RescueDateResolver(stub(null), stub(null));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "Food")).isEmpty();
    }

    @Test
    void anImplausibleFilenameDateIsRejectedRatherThanReturned() {
        final var resolver = new RescueDateResolver(stub(null), stub(LocalDateTime.of(1990, 1, 1, 0, 0)));
        final MediaFile file = new MediaFile(Path.of("IMG_1.jpg"));

        assertThat(resolver.resolve(file, "Food")).isEmpty();
    }

    private static DateSource stub(final LocalDateTime when) {
        return (_, _) -> Optional.ofNullable(when);
    }
}
