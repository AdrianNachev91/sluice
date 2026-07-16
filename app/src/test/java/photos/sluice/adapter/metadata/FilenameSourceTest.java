package photos.sluice.adapter.metadata;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.MediaFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameSourceTest {

    private final FilenameSource source = new FilenameSource();

    @Test
    void resolvesRunTogetherDate() {
        var file = new MediaFile(Path.of("IMG_20210315_103000.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDate.of(2021, 3, 15).atStartOfDay());
    }

    @Test
    void resolvesDashSeparatedDate() {
        var file = new MediaFile(Path.of("IMG-2021-03-15-WA0001.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDate.of(2021, 3, 15).atStartOfDay());
    }

    @Test
    void resolvesUnderscoreSeparatedDate() {
        var file = new MediaFile(Path.of("IMG_2021_03_15_WA0001.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDate.of(2021, 3, 15).atStartOfDay());
    }

    @Test
    void resolvesPeriodSeparatedDate() {
        var file = new MediaFile(Path.of("IMG.2021.03.15.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDate.of(2021, 3, 15).atStartOfDay());
    }

    @Test
    void ignoresAnyEmbeddedTimeComponent() {
        // Folder routing only needs year/month/day, so an embedded time component is ignored.
        var file = new MediaFile(Path.of("IMG_20210315_235959.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDate.of(2021, 3, 15).atStartOfDay());
    }

    @Test
    void returnsEmptyWhenNoDateInFilename() {
        var file = new MediaFile(Path.of("vacation-photo.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForImpossibleCalendarDate() {
        // 20210230 has a well-formed year/month/day shape but Feb 30 doesn't exist.
        var file = new MediaFile(Path.of("IMG_20210230_103000.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForOutOfRangeMonth() {
        var file = new MediaFile(Path.of("IMG_20211315_103000.jpg"));

        Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).isEmpty();
    }
}
