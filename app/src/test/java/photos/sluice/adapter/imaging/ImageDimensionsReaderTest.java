package photos.sluice.adapter.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.heif.HeifDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.domain.imaging.LowResGate;
import photos.sluice.domain.model.Dimensions;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDimensionsReaderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/dating");
    private static final Path SIFT_FIXTURES = Path.of("src/test/resources/sift");

    private final ImageDimensionsReader reader = new ImageDimensionsReader();

    @Test
    void readsExifSubIfdDimensionsFromIphoneHeic() {
        final Optional<Dimensions> result = this.reader.read(FIXTURES.resolve("iphone-exif.heic"));

        assertThat(result).contains(new Dimensions(4032, 3024));
    }

    @Test
    void fallsBackToImageIoWhenNoExifPresent() {
        final Optional<Dimensions> result = this.reader.read(FIXTURES.resolve("no-exif.jpg"));

        assertThat(result).contains(new Dimensions(2, 2));
    }

    @Test
    void returnsEmptyWhenFileIsNotAnImage() {
        final Optional<Dimensions> result = this.reader.read(FIXTURES.resolve("not-an-image.txt"));

        assertThat(result).isEmpty();
    }

    @Test
    void multiImageTiffTakesTheLargestIndexNotTheFirst(@TempDir final Path tempDir) throws IOException {
        final Path tiff = tempDir.resolve("multi-image.tiff");
        writeTwoImageTiff(tiff, 64, 64, 512, 400);

        final Optional<Dimensions> result = this.reader.read(tiff);

        assertThat(result).contains(new Dimensions(512, 400));
    }

    // This camera exposes exactly one Exif SubIFD, already carrying the true capture resolution.
    @Test
    void readsTrueCaptureResolutionFromARealCr2WithASingleSubIfd() {
        final Path cr2 = SIFT_FIXTURES.resolve("raw-samples/canon-eos-20d.cr2");

        final Optional<Dimensions> result = this.reader.read(cr2);

        assertThat(result).contains(new Dimensions(3504, 2336));
    }

    // This camera exposes several Exif SubIFD directories. The first, the embedded preview's own,
    // carries no width or height tags at all, and only a later one holds the native capture
    // resolution. Trusting the first alone would return the tiny embedded thumbnail, flagging a
    // real high-resolution photo as low-res.
    @Test
    void readsTrueCaptureResolutionFromARealNefWhereTheFirstSubIfdHasNoDimensions() {
        final Path nef = SIFT_FIXTURES.resolve("raw-samples/nikon-d40.nef");

        final Optional<Dimensions> result = this.reader.read(nef);

        assertThat(result).contains(new Dimensions(3040, 2014));
    }

    // This camera carries the native capture resolution under the generic TIFF tag pair on one
    // SubIFD, and a smaller embedded-preview resolution under the EXIF-specific pair on another.
    @Test
    void readsTrueCaptureResolutionFromARealModernSonyArw() {
        final Path arw = SIFT_FIXTURES.resolve("raw-samples/sony-ilce-6700.arw");

        final Optional<Dimensions> result = this.reader.read(arw);

        assertThat(result).contains(new Dimensions(6656, 4608));
    }

    // This file carries no embedded EXIF at all. It exposes its dimensions only through the HEIF
    // container's own width and height box, as a HeifDirectory rather than an Exif SubIFD.
    @Test
    void readsDimensionsFromARealAvifFixtureViaItsHeifDirectory() {
        final Optional<Dimensions> result = this.reader.read(SIFT_FIXTURES.resolve("arctic-sky.avif"));

        assertThat(result).contains(new Dimensions(1600, 1063));
    }

    // The imageio-webp reader extends this class's ImageIO fallback with no code of its own here,
    // so nothing but a real decode says whether that works.
    @Test
    void readsDimensionsFromARealWebpFixture() {
        final Optional<Dimensions> result = this.reader.read(SIFT_FIXTURES.resolve("webp-sample.webp"));

        assertThat(result).contains(new Dimensions(1024, 772));
    }

    // A JPEG whose EXIF pixel-dimension tags sit under the low-res bar while its encoded picture
    // sits over it. That is the shape an editor leaves behind when it resizes pixels but not tags.
    // Trusting the tags alone would route a perfectly good photo to Review.
    @Test
    void aSubThresholdMetadataSizeLosesToTheLargerSizeADecodeFinds() {
        final Path jpeg = SIFT_FIXTURES.resolve("stale-exif-dimensions.jpg");

        final Optional<Dimensions> result = this.reader.read(jpeg);

        assertThat(result).contains(new Dimensions(1024, 768));
    }

    // No ImageIO reader handles AVIF, so a sub-threshold HEIF reading has nothing to corroborate
    // it. This fixture really is 320x240, so the reading happens to be right. The class cannot
    // tell that apart from a tile size standing in for a full-resolution grid. A real tiled HEIC
    // does exactly that, reporting a 512x512 tile for a 4032x3024 photo. Reporting nothing leaves
    // the file alone. Reporting the small number would exile a photo of that second shape.
    @Test
    void aSubThresholdMetadataSizeNoDecoderCanCorroborateIsNotReported() {
        final Path avif = SIFT_FIXTURES.resolve("small-heif-only.avif");

        final Optional<Dimensions> result = this.reader.read(avif);

        assertThat(result).isEmpty();
    }

    // The real 4032x3024 iPhone HEIC's HeifDirectory reports 512x512, Apple's tile size for the
    // grid that makes up the picture. Its Exif SubIFD carries the true capture size, which is why
    // reading the whole file still returns 4032x3024. Strip that EXIF and the tile size is all
    // that is left, under the 640 bar, on a genuinely full-resolution photo. This pins the premise
    // the sub-threshold cross-check rests on, against a real file rather than an argument.
    @Test
    void aRealTiledHeicsHeifDirectoryReportsATileSizeNotTheCaptureSize() throws Exception {
        final Path heic = FIXTURES.resolve("iphone-exif.heic");

        final Metadata metadata = ImageMetadataReader.readMetadata(heic.toFile());
        final Dimensions fromHeif = Objects.requireNonNull(ImageDimensionsReader.largestAcross(
                metadata.getDirectoriesOfType(HeifDirectory.class), ImageDimensionsReader::heifDimensions));

        assertThat(fromHeif).isEqualTo(new Dimensions(512, 512));
        assertThat(Math.max(fromHeif.width(), fromHeif.height())).isLessThan(LowResGate.MIN_DIMENSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bmp", "gif"})
    void readsDimensionsFromAGenericRasterFormat(final String format, @TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("photo." + format);
        ImageIO.write(blankImage(300, 200), format, file.toFile());

        final Optional<Dimensions> result = this.reader.read(file);

        assertThat(result).contains(new Dimensions(300, 200));
    }

    // Hand-built, because no real fixture here carries both tag pairs with different values.
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
    void subIfdDimensionsFallsBackToTheGenericTagPairWhenTheExifSpecificOneIsZeroed() {
        // Real phone exports carry the Exif-specific pair present but zeroed. A zero is not an
        // answer, so it has to fall through the same way an absent tag does.
        final var directory = new ExifSubIFDDirectory();
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, 0);
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT, 0);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_WIDTH, 3456);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT, 4608);

        assertThat(ImageDimensionsReader.subIfdDimensions(directory)).isEqualTo(new Dimensions(3456, 4608));
    }

    @Test
    void subIfdDimensionsIsNullWhenEveryTagPairIsZeroed() {
        // A shape that would misroute a full-resolution photo. Reporting Dimensions(0, 0) here
        // reads as the smallest possible image rather than as no answer. It also suppresses the
        // decode fallback that finds the real size.
        final var directory = new ExifSubIFDDirectory();
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, 0);
        directory.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT, 0);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_WIDTH, 0);
        directory.setInt(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT, 0);

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

    // Hand-built, because every real fixture here carries dimensions in one directory type only.
    @Test
    void largestOfPrefersTheBiggerOfTwoPresentValues() {
        final var subIfd = new Dimensions(160, 120);
        final var heif = new Dimensions(1600, 1063);

        assertThat(ImageDimensionsReader.largestOf(subIfd, heif)).isEqualTo(heif);
        assertThat(ImageDimensionsReader.largestOf(heif, subIfd)).isEqualTo(heif);
    }

    // Which side wins changes nothing functionally, both reporting the same maxDimension, so
    // nothing else would notice the tie-break rule moving.
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
            final Path target, final int firstWidth, final int firstHeight, final int secondWidth,
            final int secondHeight)
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
