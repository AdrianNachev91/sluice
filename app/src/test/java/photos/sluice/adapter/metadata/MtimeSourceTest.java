package photos.sluice.adapter.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.model.MediaFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MtimeSourceTest {

    private final MtimeSource source = new MtimeSource();

    @Test
    void resolvesLastModifiedTimeInSystemZone(@TempDir final Path dir) throws IOException {
        final Path file = Files.createFile(dir.resolve("IMG_0001.jpg"));
        final LocalDateTime modifiedAt = LocalDateTime.of(2019, 6, 20, 8, 0, 0);
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt.atZone(ZoneId.systemDefault()).toInstant()));

        final Optional<LocalDateTime> result = this.source.resolve(new MediaFile(file), null);

        assertThat(result).contains(modifiedAt);
    }

    @Test
    void returnsEmptyWhenFileDoesNotExist() {
        final var file = new MediaFile(Path.of("does-not-exist.jpg"));

        final Optional<LocalDateTime> result = this.source.resolve(file, null);

        assertThat(result).isEmpty();
    }
}
