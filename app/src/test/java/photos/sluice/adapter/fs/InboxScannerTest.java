package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.model.ScanResult;
import photos.sluice.domain.model.TakeoutSidecar;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxScannerTest {

    private final InboxScanner scanner = new InboxScanner();

    @Test
    void nestedTakeoutTreePairsEndToEnd(@TempDir final Path inbox) throws IOException {
        final Path album = Files.createDirectories(inbox.resolve("Takeout").resolve("Google Photos").resolve("2019-06"
        ));
        final Path photo = album.resolve("IMG_1234.jpg");
        Files.writeString(photo, "photo bytes");
        Files.writeString(album.resolve("IMG_1234.jpg.json"), "{}");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.takeoutMode()).isTrue();
        assertThat(result.media()).containsExactly(new MediaFile(photo));
        assertThat(result.sidecars()).containsEntry(new MediaFile(photo), new TakeoutSidecar(album.resolve("IMG_1234" +
                ".jpg.json")));
    }

    @Test
    void plainDumpIsNonTakeoutMode(@TempDir final Path inbox) throws IOException {
        final Path photo = inbox.resolve("IMG_5678.jpg");
        Files.writeString(photo, "photo bytes");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.takeoutMode()).isFalse();
        assertThat(result.media()).containsExactly(new MediaFile(photo));
        assertThat(result.sidecars()).isEmpty();
    }

    @Test
    void svgTreatedAsMedia(@TempDir final Path inbox) throws IOException {
        final Path svg = inbox.resolve("drawing.svg");
        Files.writeString(svg, "<svg/>");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.media()).containsExactly(new MediaFile(svg));
    }

    @Test
    void videoExtensionIsIncludedAsMedia(@TempDir final Path inbox) throws IOException {
        final Path video = inbox.resolve("clip.mp4");
        Files.writeString(video, "video bytes");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.media()).containsExactly(new MediaFile(video));
        assertThat(new MediaTypeDetector().classify(video)).contains(MediaType.VIDEO);
    }

    @Test
    void nonMediaNonJsonFileIsIgnored(@TempDir final Path inbox) throws IOException {
        final Path photo = inbox.resolve("IMG_5678.jpg");
        Files.writeString(photo, "photo bytes");
        Files.writeString(inbox.resolve("readme.txt"), "not media");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.media()).containsExactly(new MediaFile(photo));
    }

    @Test
    void jsonSidecarSuffixIsCaseInsensitive(@TempDir final Path inbox) throws IOException {
        final Path photo = inbox.resolve("IMG_1234.jpg");
        Files.writeString(photo, "photo bytes");
        final Path sidecar = inbox.resolve("IMG_1234.jpg.JSON");
        Files.writeString(sidecar, "{}");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.takeoutMode()).isTrue();
        assertThat(result.sidecars()).containsEntry(new MediaFile(photo), new TakeoutSidecar(sidecar));
    }

    @Test
    void sameNamedPairsInDifferentDirectoriesDoNotCrossPair(@TempDir final Path inbox) throws IOException {
        final Path dirA = Files.createDirectories(inbox.resolve("2019-06"));
        final Path dirB = Files.createDirectories(inbox.resolve("2020-07"));
        final Path photoA = dirA.resolve("IMG_1234.jpg");
        final Path photoB = dirB.resolve("IMG_1234.jpg");
        Files.writeString(photoA, "photo bytes a");
        Files.writeString(photoB, "photo bytes b");
        final Path sidecarA = dirA.resolve("IMG_1234.jpg.json");
        final Path sidecarB = dirB.resolve("IMG_1234.jpg.json");
        Files.writeString(sidecarA, "{}");
        Files.writeString(sidecarB, "{}");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.sidecars())
                .containsEntry(new MediaFile(photoA), new TakeoutSidecar(sidecarA))
                .containsEntry(new MediaFile(photoB), new TakeoutSidecar(sidecarB));
    }

    @Test
    void danglingSidecarWithNoMatchingMediaIsDroppedSilently(@TempDir final Path inbox) throws IOException {
        Files.writeString(inbox.resolve("IMG_9999.jpg.json"), "{}");

        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.takeoutMode()).isTrue();
        assertThat(result.media()).isEmpty();
        assertThat(result.sidecars()).isEmpty();
    }

    @Test
    void emptyInboxProducesEmptyNonTakeoutResult(@TempDir final Path inbox) {
        final ScanResult result = this.scanner.scan(inbox);

        assertThat(result.takeoutMode()).isFalse();
        assertThat(result.media()).isEmpty();
        assertThat(result.sidecars()).isEmpty();
    }

    @Test
    void nonExistentInboxRootThrowsUncheckedIOException(@TempDir final Path inbox) {
        final Path missing = inbox.resolve("does-not-exist");

        assertThatThrownBy(() -> this.scanner.scan(missing)).isInstanceOf(UncheckedIOException.class);
    }
}
