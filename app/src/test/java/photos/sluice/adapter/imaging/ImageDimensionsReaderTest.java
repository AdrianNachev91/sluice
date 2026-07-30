package photos.sluice.adapter.imaging;

import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.heif.HeifDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.domain.model.Dimensions;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDimensionsReaderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/dating");
    private static final Path CULL_FIXTURES = Path.of("src/test/resources/cull");

    private final ImageDimensionsReader reader = new ImageDimensionsReader();

    @Test
    void readsExifSubIfdDimensionsFromIphoneHeic() {
        final Optional<Dimensions> result = reader.read(FIXTURES.resolve("iphone-exif.heic"));

        assertThat(result).contains(new Dimensions(4032, 3024));
    }

    @Test
    void fallsBackToImageIoWhenNoExifPresent() {
        final Optional<Dimensions> result = reader.read(FIXTURES.resolve("no-exif.jpg"));

        assertThat(result).contains(new Dimensions(2, 2));
    }

    @Test
    void returnsEmptyWhenFileIsNotAnImage() {
        final Optional<Dimensions> result = reader.read(FIXTURES.resolve("not-an-image.txt"));

        assertThat(result).isEmpty();
    }

    @Test
    void multiImageTiffTakesTheLargestIndexNotTheFirst(@TempDir final Path tempDir) throws IOException {
        final Path tiff = tempDir.resolve("multi-image.tiff");
        writeTwoImageTiff(tiff, 64, 64, 512, 400);

        final Optional<Dimensions> result = reader.read(tiff);

        assertThat(result).contains(new Dimensions(512, 400));
    }

    // A real Canon EOS 20D CR2 exposes exactly one Exif SubIFD, and it already carries the true
    // capture resolution directly. Not a regression-prone case on its own, but it confirms the
    // multi-directory scan still returns the correct value when only one SubIFD exists.
    @Test
    void readsTrueCaptureResolutionFromARealCr2WithASingleSubIfd() {
        final Path cr2 = CULL_FIXTURES.resolve("raw-samples/canon-eos-20d.cr2");

        final Optional<Dimensions> result = reader.read(cr2);

        assertThat(result).contains(new Dimensions(3504, 2336));
    }

    // A real Nikon D40 NEF exposes multiple Exif SubIFD directories. The first (the embedded
    // preview's own) has no width/height tags at all - only a later one holds the true 3040x2014
    // native capture resolution. Trusting only the first SubIFD would return the tiny 160x120
    // embedded thumbnail instead, wrongly flagging a real high-res photo as low-res in LowResGate.
    // Verified against the actual fixture bytes, not a synthesized case.
    @Test
    void readsTrueCaptureResolutionFromARealNefWhereTheFirstSubIfdHasNoDimensions() {
        final Path nef = CULL_FIXTURES.resolve("raw-samples/nikon-d40.nef");

        final Optional<Dimensions> result = reader.read(nef);

        assertThat(result).contains(new Dimensions(3040, 2014));
    }

    // A current-generation (2023) Sony ILCE-6700 carries the true 6656x4608 native capture
    // resolution under the generic TIFF tag pair on one SubIFD, and a separate, smaller
    // 6192x4128 embedded-preview resolution under the EXIF-specific tag pair on another. The
    // largest-across-directories rule correctly picks the true capture size, not the preview.
    @Test
    void readsTrueCaptureResolutionFromARealModernSonyArw() {
        final Path arw = CULL_FIXTURES.resolve("raw-samples/sony-ilce-6700.arw");

        final Optional<Dimensions> result = reader.read(arw);

        assertThat(result).contains(new Dimensions(6656, 4608));
    }

    // A real AVIF file (verified genuine ftyp/avif box structure) carries no embedded EXIF at all,
    // unlike the iPhone HEIC fixture above. It exposes its dimensions only via the HEIF container's
    // own native width/height box, as a separate HeifDirectory rather than an Exif SubIFD. Confirmed
    // by directly dumping every directory metadata-extractor found for this file: a real 1600x1063,
    // matching the file's actual known dimensions.
    @Test
    void readsDimensionsFromARealAvifFixtureViaItsHeifDirectory() {
        final Optional<Dimensions> result = reader.read(CULL_FIXTURES.resolve("arctic-sky.avif"));

        assertThat(result).contains(new Dimensions(1600, 1063));
    }

    // The added TwelveMonkeys imageio-webp dependency (added for TileRenderer's tile decode) also
    // extends this class's readViaImageIo fallback for free, with no code change needed here.
    // This just confirms it actually works, rather than assuming it from the dependency addition
    // alone.
    @Test
    void readsDimensionsFromARealWebpFixture() {
        final Optional<Dimensions> result = reader.read(CULL_FIXTURES.resolve("webp-sample.webp"));

        assertThat(result).contains(new Dimensions(1024, 772));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bmp", "gif"})
    void readsDimensionsFromAGenericRasterFormat(final String format, @TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("photo." + format);
        ImageIO.write(blankImage(300, 200), format, file.toFile());

        final Optional<Dimensions> result = reader.read(file);

        assertThat(result).contains(new Dimensions(300, 200));
    }

    // Fast, isolated coverage of subIfdDimensions's tag-priority order, direct from hand-built
    // directories. The real-fixture tests above already prove the two actual observed cases work,
    // but they don't pin down the priority rule against a regression in an untested branch
    // combination (e.g. a directory carrying both tag pairs with different values).
    @Test
    void subIfdDimensionsPrefersTheExifSpecificTagPairWhenBothAreDirectlyPresent() {
        final var directory = new ExifSubIFDDirectory();
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, 3504);
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT, 2336);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_WIDTH, 384);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT, 256);

        assertThat(ImageDimensionsReader.subIfdDimensions(directory))
                .isEqualTo(new Dimensions(3504, 2336));
    }

    @Test
    void subIfdDimensionsFallsBackToTheGenericTagPairWhenTheExifSpecificOneIsAbsent() {
        final var directory = new ExifSubIFDDirectory();
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_WIDTH, 3040);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT, 2014);

        assertThat(ImageDimensionsReader.subIfdDimensions(directory))
                .isEqualTo(new Dimensions(3040, 2014));
    }

    @Test
    void subIfdDimensionsIsNullWhenNeitherTagPairIsPresent() {
        final var directory = new ExifSubIFDDirectory();

        assertThat(ImageDimensionsReader.subIfdDimensions(directory)).isNull();
    }

    @Test
    void heifDimensionsReadsTheWidthAndHeightTags() {
        final var directory = new HeifDirectory();
        directory.setInt(HeifDirectory.TAG_IMAGE_WIDTH, 1600);
        directory.setInt(HeifDirectory.TAG_IMAGE_HEIGHT, 1063);

        assertThat(ImageDimensionsReader.heifDimensions(directory)).isEqualTo(new Dimensions(1600, 1063));
    }

    @Test
    void heifDimensionsIsNullWhenTagsAreAbsent() {
        final var directory = new HeifDirectory();

        assertThat(ImageDimensionsReader.heifDimensions(directory)).isNull();
    }

    // No real fixture exercises this branch - every real fixture in this suite carries
    // dimensions in exactly one directory type. Direct coverage against hand-built values pins
    // the cross-type comparison itself, since that's the part with no empirical case yet.
    @Test
    void largestOfPrefersTheBiggerOfTwoPresentValues() {
        final var subIfd = new Dimensions(160, 120);
        final var heif = new Dimensions(1600, 1063);

        assertThat(ImageDimensionsReader.largestOf(subIfd, heif)).isEqualTo(heif);
        assertThat(ImageDimensionsReader.largestOf(heif, subIfd)).isEqualTo(heif);
    }

    // Pins down the tie-break rule (keep the first argument) now that it's an observable choice,
    // not just an implementation detail - which side wins doesn't matter functionally, since both
    // report the same maxDimension, but the rule itself should stay verified rather than incidental.
    @Test
    void largestOfKeepsTheFirstArgumentWhenBothMaxDimensionsAreEqual() {
        final var first = new Dimensions(4000, 3000);
        final var second = new Dimensions(4000, 2000);

        assertThat(ImageDimensionsReader.largestOf(first, second)).isEqualTo(first);
        assertThat(ImageDimensionsReader.largestOf(second, first)).isEqualTo(second);
    }

    @Test
    void largestOfFallsBackToWhicheverSideIsPresentWhenTheOtherIsNull() {
        final var present = new Dimensions(1600, 1063);

        assertThat(ImageDimensionsReader.largestOf(present, null)).isEqualTo(present);
        assertThat(ImageDimensionsReader.largestOf(null, present)).isEqualTo(present);
    }

    @Test
    void largestOfIsNullWhenBothSidesAreNull() {
        assertThat(ImageDimensionsReader.largestOf(null, null)).isNull();
    }

    private static void writeTwoImageTiff(
            final Path target, final int firstWidth, final int firstHeight, final int secondWidth, final int secondHeight)
            throws IOException {
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        final ImageWriter writer = writers.next();
        try (final ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            writer.writeToSequence(
                    new javax.imageio.IIOImage(blankImage(firstWidth, firstHeight), null, null), param);
            writer.writeToSequence(
                    new javax.imageio.IIOImage(blankImage(secondWidth, secondHeight), null, null), param);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage blankImage(final int width, final int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
