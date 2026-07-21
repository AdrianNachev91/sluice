package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.adapter.imaging.TileRenderer.TileResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TileRendererTest {

    private static final Path FIXTURES = Path.of("src/test/resources/cull");
    private static final int TILE_SIZE = 224;

    private final TileRenderer renderer = new TileRenderer(_ -> Optional.empty());

    @Test
    void resizesALandscapeRasterImageFitWithinTheTileBoundsWithoutStretching(@TempDir Path tempDir)
            throws IOException {
        Path jpeg = tempDir.resolve("landscape.jpg");
        writeJpeg(jpeg, 1280, 640);

        TileResult result = renderer.render(jpeg, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    @Test
    void resizesAPortraitRasterImageFitWithinTheTileBoundsWithoutStretching(@TempDir Path tempDir)
            throws IOException {
        Path jpeg = tempDir.resolve("portrait.jpg");
        writeJpeg(jpeg, 640, 1280);

        TileResult result = renderer.render(jpeg, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE / 2);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    @Test
    void rendersARealSvgFixtureToABoundedTile() {
        TileResult result = renderer.render(FIXTURES.resolve("rectangle.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    // A real CC0 fixture from Wikimedia Commons (SVG_Gradient.svg), exercising Batik against
    // actual gradient/stop/transform features, not just a synthesized flat rectangle. Its root
    // <svg> element declares only a viewBox="0 0 300 200", with no width/height attributes - a
    // common, valid SVG authoring style. Batik's own default sizing cannot handle that style
    // correctly (verified: without svgAspectRatio's explicit hints, this fixture rendered visibly
    // stretched into a square). The exact 224x150 here asserts that the 3:2 aspect ratio was
    // actually preserved, not just that some bounded image came out.
    @Test
    void rendersARealGradientSvgFixturePreservingItsViewBoxAspectRatio() {
        TileResult result = renderer.render(FIXTURES.resolve("gradient.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(150);
    }

    // Many real-world SVGs (Illustrator exports especially) carry the standard SVG 1.1 public
    // DOCTYPE prolog. A blanket disallow-doctype-decl - an earlier draft of the XXE defense -
    // would have made this file fail XML parsing entirely, silently falling back to the
    // square-guess aspect ratio despite being perfectly legitimate. The narrower
    // external-entity-blocking defense parses it correctly while still rejecting XXE attacks.
    @Test
    void aDoctypeDeclaredSvgStillGetsItsRealAspectRatio() {
        TileResult result = renderer.render(FIXTURES.resolve("doctype-viewbox-only.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 4);
    }

    // A real file in this project's own library is a PNG mislabeled with an .svg extension. Batik
    // correctly fails to parse it as XML, and the raster fallback recovers a real tile instead of
    // a placeholder. Verified against the actual file (defqon_2027_overlay.svg) rather than a
    // synthesized case.
    @Test
    void aFileWithSvgExtensionThatIsActuallyPngFallsBackToRasterDecode() {
        TileResult result = renderer.render(FIXTURES.resolve("defqon_2027_overlay.svg"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    @Test
    void unknownCorruptFileFallsBackToAPlaceholder() {
        TileResult result = renderer.render(FIXTURES.resolve("not-an-image.dat"), TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // TwelveMonkeys can read this file's embedded-preview dimensions but not decode its pixel
    // data - a Missing TIFF tag JPEGQTables failure on the "old-style JPEG" TIFF compression,
    // verified empirically against the real fixture. renderRaster alone would fall back to a
    // placeholder. But the file also carries a standard EXIF embedded thumbnail, a complete,
    // independently decodable JPEG blob per the EXIF spec, unlike the TIFF-compressed main image.
    // renderExifThumbnail recovers that thumbnail as a real tile instead - a real, legible photo,
    // verified empirically. Its 160x120 source is below the judgeable bar (this old 2004-era
    // camera's recoverable preview really is that small), so unreviewable is still true even
    // though real pixels came back - contrast with the modern Sony fixture below.
    @Test
    void realCanonCr2FixtureFallsBackToItsExifThumbnailWhenThePrimaryDecodeFails() {
        Path cr2 = FIXTURES.resolve("raw-samples/canon-eos-20d.cr2");

        TileResult result = renderer.render(cr2, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 120 / 160);
    }

    // A current-generation (2023) Sony ILCE-6700 - unlike the old Canon/Nikon fixtures above, its
    // EXIF embedded thumbnail is a near-full-resolution 6192x4128, well above the judgeable bar.
    // Verified empirically: this is a real, sharp, clearly judgeable landscape photo, not a tiny
    // icon. Confirms modern camera files are not assumed to share the old-camera tiny-preview
    // problem - the same code path recovers a genuinely reviewable tile here.
    @Test
    void realModernSonyArwFixtureRecoversAJudgeablePreview() {
        Path arw = FIXTURES.resolve("raw-samples/sony-ilce-6700.arw");

        TileResult result = renderer.render(arw, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    // Fast, isolated coverage of the length-sanity guard itself. The real CR2 fixture above only
    // ever exercises one legitimate small value (6162 bytes), so a flipped comparison or a dropped
    // cap wouldn't be caught by that test alone. Boundary values are the cap itself (20MB, 20971520
    // bytes) and one past it, alongside zero/negative and a realistic small value.
    @ParameterizedTest
    @CsvSource({
            "0, false",
            "-1, false",
            "20971521, false",
            "6162, true",
            "20971520, true"
    })
    void isPlausibleThumbnailLengthMatchesExpectedBoundary(int length, boolean expected) {
        assertThat(TileRenderer.isPlausibleThumbnailLength(length)).isEqualTo(expected);
    }

    // A fake file with a RAW extension but genuinely unparseable content (not a real image at
    // all) has no EXIF thumbnail to fall back to either. Both fallbacks are exhausted here, so a
    // placeholder is correct. Distinguishes "primary decode fails but a thumbnail rescues it"
    // (CR2 above) from "nothing at all is recoverable" (this case).
    @Test
    void aFileWithRawExtensionAndNoRealImageContentAtAllFallsBackToAPlaceholder() {
        TileResult result = renderer.render(FIXTURES.resolve("fake-corrupt.cr2"), TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // TwelveMonkeys successfully decodes this file's one embedded image directly via the primary
    // raster path: a real, if low-quality, 160x120 thumbnail, verified empirically against the
    // real fixture. That source is below the judgeable bar, so unreviewable is true even though a
    // real (if tiny) tile came back - the primary-path case the whole judgeability check exists
    // for, not just the EXIF-thumbnail fallback.
    @Test
    void realNikonNefFixtureDecodesARealButTooSmallEmbeddedThumbnail() {
        Path nef = FIXTURES.resolve("raw-samples/nikon-d40.nef");

        TileResult result = renderer.render(nef, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 120 / 160);
    }

    // Real WebP sample from Google's own official, permissively-licensed WebP gallery
    // (developers.google.com/speed/webp/gallery1). Confirms the added TwelveMonkeys imageio-webp
    // dependency actually decodes real WebP bytes, not just that a reader is present.
    @Test
    void rendersARealWebpFixtureToABoundedTile() {
        TileResult result = renderer.render(FIXTURES.resolve("webp-sample.webp"), TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(Math.max(result.image().getWidth(), result.image().getHeight())).isEqualTo(TILE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tiff", "bmp", "gif"})
    void decodesViaTheGenericRasterPath(String format, @TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("photo." + format);
        ImageIO.write(blankImage(960, 640), format, file.toFile());

        TileResult result = renderer.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    // A raster source below the judgeable bar (640px) is flagged unreviewable even though the
    // decode itself succeeds cleanly - the primary raster path's own version of the small-preview
    // problem the Nikon NEF fixture demonstrates with real bytes above.
    @Test
    void aSmallRasterImageDecodesButIsFlaggedUnreviewable(@TempDir Path tempDir) throws IOException {
        Path tiny = tempDir.resolve("tiny.jpg");
        writeJpeg(tiny, 300, 200);

        TileResult result = renderer.render(tiny, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    // Pins down the exact boundary (< 640, not <= 640) rather than leaving it to the gap between
    // the fixture sizes used elsewhere (960px "large" vs 160-300px "small") - a flipped comparison
    // operator wouldn't be caught by any of those.
    @ParameterizedTest
    @CsvSource({
            "640, false",
            "639, true"
    })
    void aSourceAtTheJudgeableBoundary(int dimension, boolean expectedUnreviewable, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("boundary.jpg");
        writeJpeg(file, dimension, dimension);

        TileResult result = renderer.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isEqualTo(expectedUnreviewable);
    }

    // heic, heif, and avif all share the same HeifDecoder port and routing - no real decoder
    // exists yet for any of the three, so this only proves the routing, the same way the
    // decoder-returns-empty and too-small tests below do.
    @ParameterizedTest
    @ValueSource(strings = {"heic", "heif", "avif"})
    void heifFamilyRoutesToTheInjectedDecoderAndResizesItsResult(String extension, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("photo." + extension);
        Files.createFile(file);
        BufferedImage decoded = blankImage(800, 400);
        TileRenderer withDecoder = new TileRenderer(_ -> Optional.of(decoded));

        TileResult result = withDecoder.render(file, TILE_SIZE);

        assertThat(result.unreviewable()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE / 2);
    }

    // A real AVIF file (arctic-sky.avif, a public-domain USGS photo via Wikimedia Commons,
    // verified genuine ftyp/avif box structure) run through the default renderer, which has no
    // real HeifDecoder wired in yet - proves today's actual, honest behavior for a real file of
    // this format: a placeholder, not a crash or a silent mis-route. Once a real libheif-backed
    // adapter exists, this test should start asserting a real decoded tile instead.
    @Test
    void realAvifFixtureFallsBackToAPlaceholderUntilARealDecoderExists() {
        Path avif = FIXTURES.resolve("arctic-sky.avif");

        TileResult result = renderer.render(avif, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    // The HEIC/AVIF decode path's own version of the judgeability check: a decoder can hand back
    // real pixels that are still too small to trust, same as the raster and EXIF-thumbnail paths.
    @Test
    void heifDecoderReturningATooSmallImageIsFlaggedUnreviewable(@TempDir Path tempDir) throws IOException {
        Path fakeHeic = tempDir.resolve("photo.heic");
        Files.createFile(fakeHeic);
        BufferedImage decoded = blankImage(300, 200);
        TileRenderer withHeic = new TileRenderer(_ -> Optional.of(decoded));

        TileResult result = withHeic.render(fakeHeic, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE * 2 / 3);
    }

    @Test
    void heicFallsBackToAPlaceholderWhenTheDecoderReturnsEmpty(@TempDir Path tempDir) throws IOException {
        Path fakeHeic = tempDir.resolve("photo.heic");
        Files.createFile(fakeHeic);
        TileRenderer withNoDecoder = new TileRenderer(_ -> Optional.empty());

        TileResult result = withNoDecoder.render(fakeHeic, TILE_SIZE);

        assertThat(result.unreviewable()).isTrue();
        assertThat(result.image().getWidth()).isEqualTo(TILE_SIZE);
        assertThat(result.image().getHeight()).isEqualTo(TILE_SIZE);
    }

    private static void writeJpeg(Path target, int width, int height) throws IOException {
        ImageIO.write(blankImage(width, height), "jpg", target.toFile());
    }

    private static BufferedImage blankImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
