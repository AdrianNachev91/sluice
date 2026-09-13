package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.adapter.imaging.TileRenderer.TileResult;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TileRendererTest {

    private static final Path FIXTURES = Path.of("src/test/resources/sift");
    private static final int TILE_SIZE = 224;

    private final TileRenderer renderer = new TileRenderer(_ -> Optional.empty());

    @Test
    void resizesALandscapeRasterImageFitWithinTheTileBoundsWithoutStretching(@TempDir final Path tempDir)
            throws IOException {
        final Path jpeg = tempDir.resolve("landscape.jpg");
        writeJpeg(jpeg, 1280, 640);

        final TileResult result = this.renderer.render(jpeg, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    @Test
    void resizesAPortraitRasterImageFitWithinTheTileBoundsWithoutStretching(@TempDir final Path tempDir)
            throws IOException {
        final Path jpeg = tempDir.resolve("portrait.jpg");
        writeJpeg(jpeg, 640, 1280);

        final TileResult result = this.renderer.render(jpeg, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE / 2);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    @Test
    void rendersARealSvgFixtureToABoundedTile() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("rectangle.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    // A real CC0 fixture from Wikimedia Commons (SVG_Gradient.svg), exercising Batik against
    // actual gradient, stop and transform features rather than a synthesized flat rectangle. Its
    // root svg element declares only a viewBox, with no width or height attributes, which is a
    // common and valid authoring style. Batik's own default sizing renders that stretched into a
    // square unless svgAspectRatio hands it explicit hints.
    @Test
    void rendersARealGradientSvgFixturePreservingItsViewBoxAspectRatio() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("gradient.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(150);
    }

    // Many real-world SVGs, Illustrator exports especially, carry the standard SVG 1.1 public
    // DOCTYPE prolog. A blanket disallow-doctype-decl was rejected as the XXE defense for that
    // reason. It fails XML parsing on a perfectly legitimate file, then falls back silently to the
    // square-guess aspect ratio. Blocking external entities alone parses this correctly and still
    // rejects XXE.
    @Test
    void aDoctypeDeclaredSvgStillGetsItsRealAspectRatio() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("doctype-viewbox-only.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 4);
    }

    // A real mislabelling found in this project's own media: a PNG carrying an .svg extension.
    // Batik fails to parse it as XML, and the raster fallback is what recovers a tile.
    @Test
    void aFileWithSvgExtensionThatIsActuallyPngFallsBackToRasterDecode() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("defqon_2027_overlay.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    @Test
    void unknownCorruptFileFallsBackToAPlaceholder() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("not-an-image.dat"), TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // TwelveMonkeys can read this file's embedded-preview dimensions but not decode its pixel
    // data, failing on a missing TIFF JPEGQTables tag in the old-style JPEG compression. The file
    // also carries a standard EXIF embedded thumbnail, which is an independently decodable JPEG
    // blob per the spec, so that is what comes back instead. This 2004-era camera's recoverable
    // preview really is only 160x120, below the judgeable bar, so unreviewable stays true even
    // though real pixels came back.
    @Test
    void realCanonCr2FixtureFallsBackToItsExifThumbnailWhenThePrimaryDecodeFails() {
        final Path cr2 = FIXTURES.resolve("raw-samples/canon-eos-20d.cr2");

        final TileResult result = this.renderer.render(cr2, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 120 / 160);
    }

    // A 2023 Sony ILCE-6700, whose EXIF embedded thumbnail is a near-full-resolution 6192x4128,
    // well above the judgeable bar. So the tiny-preview problem is the old cameras', not a
    // property of the RAW path itself.
    @Test
    void realModernSonyArwFixtureRecoversAJudgeablePreview() {
        final Path arw = FIXTURES.resolve("raw-samples/sony-ilce-6700.arw");

        final TileResult result = this.renderer.render(arw, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    // The real RAW fixtures only ever exercise one legitimate small value, so a flipped comparison
    // or a dropped cap would not be caught by them. The values below are the cap itself and one
    // past it, alongside zero, negative and a realistic small value.
    @ParameterizedTest
    @CsvSource({
            "0, false",
            "-1, false",
            "20971521, false",
            "6162, true",
            "20971520, true"
    })
    void isPlausibleThumbnailLengthMatchesExpectedBoundary(final int length, final boolean expected) {
        assertThat(TileRenderer.isPlausibleThumbnailLength(length)).isEqualTo(expected);
    }

    // A RAW extension over content that is not an image at all, so there is no EXIF thumbnail to
    // fall back to either. Both fallbacks are exhausted, which is what makes a placeholder right.
    @Test
    void aFileWithRawExtensionAndNoRealImageContentAtAllFallsBackToAPlaceholder() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("fake-corrupt.cr2"), TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // TwelveMonkeys decodes this file's one embedded image on the primary raster path, a real if
    // low-quality 160x120 thumbnail. That source is below the judgeable bar, so this is the
    // primary path's own version of the case, not the EXIF-thumbnail fallback's.
    @Test
    void realNikonNefFixtureDecodesARealButTooSmallEmbeddedThumbnail() {
        final Path nef = FIXTURES.resolve("raw-samples/nikon-d40.nef");

        final TileResult result = this.renderer.render(nef, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 120 / 160);
    }

    // Real WebP sample from Google's own permissively-licensed WebP gallery
    // (developers.google.com/speed/webp/gallery1), so this proves the imageio-webp reader decodes
    // real bytes rather than merely being present.
    @Test
    void rendersARealWebpFixtureToABoundedTile() {
        final TileResult result = this.renderer.render(FIXTURES.resolve("webp-sample.webp"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tiff", "bmp", "gif"})
    void decodesViaTheGenericRasterPath(final String format, @TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("photo." + format);
        ImageIO.write(blankImage(960, 640), format, file.toFile());

        final TileResult result = this.renderer.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    @Test
    void aSmallRasterImageDecodesButIsFlaggedUnreviewable(@TempDir final Path tempDir) throws IOException {
        final Path tiny = tempDir.resolve("tiny.jpg");
        writeJpeg(tiny, 300, 200);

        final TileResult result = this.renderer.render(tiny, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    // Every other fixture here sits well clear of the bar. A flipped comparison operator would not
    // be caught by any of them.
    @ParameterizedTest
    @CsvSource({
            "640, false",
            "639, true"
    })
    void aSourceAtTheJudgeableBoundary(final int dimension, final boolean expectedUnreviewable,
                                       @TempDir final Path tempDir)
            throws IOException {
        final Path file = tempDir.resolve("boundary.jpg");
        writeJpeg(file, dimension, dimension);

        final TileResult result = this.renderer.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isEqualTo(expectedUnreviewable);
    }

    // A stub decoder, so what this proves is the routing rather than any decode.
    @ParameterizedTest
    @ValueSource(strings = {"heic", "heif", "avif"})
    void heifFamilyRoutesToTheInjectedDecoderAndResizesItsResult(final String extension, @TempDir final Path tempDir)
            throws IOException {
        final Path file = tempDir.resolve("photo." + extension);
        Files.createFile(file);
        final BufferedImage decoded = blankImage(800, 400);
        final TileRenderer withDecoder = new TileRenderer(_ -> Optional.of(decoded));

        final TileResult result = withDecoder.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    // A real AVIF file: a public-domain USGS photo via Wikimedia Commons, with genuine ftyp/avif
    // box structure. It runs through the real CliHeifDecoder rather than the stub every other test
    // here uses, so this covers the HEIF family end to end. Its 1600x1063 source fitted within
    // 224x224 lands on 224x149.
    @Test
    void realAvifFixtureDecodesToARealTileViaTheCliHeifDecoder() {
        final TileRenderer withRealHeifDecoder = new TileRenderer(new CliHeifDecoder("heif-convert"));
        final Path avif = FIXTURES.resolve("arctic-sky.avif");

        final TileResult result = withRealHeifDecoder.render(avif, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(149);
    }

    @Test
    void heifDecoderReturningATooSmallImageIsFlaggedUnreviewable(@TempDir final Path tempDir) throws IOException {
        final Path fakeHeic = tempDir.resolve("photo.heic");
        Files.createFile(fakeHeic);
        final BufferedImage decoded = blankImage(300, 200);
        final TileRenderer withHeic = new TileRenderer(_ -> Optional.of(decoded));

        final TileResult result = withHeic.render(fakeHeic, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    // A stub decoder, because no real HeifDecoder can be made to throw on demand. The port's
    // signature permits an unchecked exception, and an implementation backed by a native library
    // or another process can raise one.
    @Test
    void heicFallsBackToAPlaceholderWhenTheDecoderThrows(@TempDir final Path tempDir) throws IOException {
        final Path fakeHeic = tempDir.resolve("photo.heic");
        Files.createFile(fakeHeic);
        final TileRenderer withThrowingDecoder = new TileRenderer(_ -> {
            throw new IllegalStateException("decoder blew up");
        });

        final TileResult result = withThrowingDecoder.render(fakeHeic, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    @Test
    void heicFallsBackToAPlaceholderWhenTheDecoderReturnsEmpty(@TempDir final Path tempDir) throws IOException {
        final Path fakeHeic = tempDir.resolve("photo.heic");
        Files.createFile(fakeHeic);
        final TileRenderer withNoDecoder = new TileRenderer(_ -> Optional.empty());

        final TileResult result = withNoDecoder.render(fakeHeic, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // Whether the size check and the decode read the same sub-image is an assumption, not
    // something this code can check at runtime. Thumbnailator 0.4.21's own source has them agree,
    // at InputStreamImageSource.FIRST_IMAGE_INDEX = 0. That is an internal detail rather than a
    // public contract. So the fixture below carries two sub-images differing visibly in size and
    // colour, and the tile's rendered content is checked against what unreviewable claims. A
    // version that picked the other sub-image fails here rather than disagreeing silently.
    @Test
    void unreviewableFlagMatchesTheSubImageActuallyRendered(@TempDir final Path tempDir) throws IOException {
        final Path tiff = tempDir.resolve("two-page.tiff");
        writeTwoPageTiff(tiff, 50, 50, Color.RED, 2000, 2000, Color.BLUE);

        final TileResult result = this.renderer.render(tiff, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        final int centerX = result.image().getWidth() / 2;
        final int centerY = result.image().getHeight() / 2;
        assertThat(new Color(result.image().getRGB(centerX, centerY))).isEqualTo(Color.RED);
    }

    private static void writeTwoPageTiff(
            final Path target, final int firstWidth, final int firstHeight, final Color firstColor,
            final int secondWidth, final int secondHeight, final Color secondColor) throws IOException {
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        final ImageWriter writer = writers.next();
        try (final ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            writer.writeToSequence(
                    new IIOImage(solidImage(firstWidth, firstHeight, firstColor), null, null), param);
            writer.writeToSequence(
                    new IIOImage(solidImage(secondWidth, secondHeight, secondColor), null, null), param);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage solidImage(final int width, final int height, final Color color) {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void writeJpeg(final Path target, final int width, final int height) throws IOException {
        ImageIO.write(blankImage(width, height), "jpg", target.toFile());
    }

    private static BufferedImage blankImage(final int width, final int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
