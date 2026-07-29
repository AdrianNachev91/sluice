package photos.sluice.adapter.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.domain.imaging.LowResGate;
import photos.sluice.domain.scan.MediaTypeDetector;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Renders a fixed-size preview tile for a single media file, trying a chain of format-specific
 * strategies before falling back to a labeled placeholder.
 *
 * <p>Most files render via a plain raster decode. HEIC/HEIF/AVIF files route through a
 * {@link HeifDecoder}. SVG files are transcoded via Batik, preserving their real aspect ratio.
 * A RAW file whose main image can't be decoded falls back to extracting its embedded EXIF
 * thumbnail directly from the file's own bytes.
 *
 * <p>Alongside the image, every render also decides whether the tile is fit to show a vision
 * model for a keep/junk judgment. That decision rests on the resolution of the actual preview
 * recovered, not the file's own claimed capture resolution. See {@link TileResult}.
 */
@Component
public class TileRenderer {

    // The TIFF-container RAW formats. A generic decode attempt is still made for them (see
    // render()). This set only supplies a real label for the placeholder if that attempt fails.
    private static final Set<String> RAW_EXTENSIONS =
            Set.of("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2");

    private static final int MAX_EXIF_THUMBNAIL_BYTES = 20 * 1024 * 1024;

    // Same constant LowResGate uses for "worth a close look" - reused, not duplicated, so the two
    // never drift apart. It answers a related but distinct question here: not whether the original
    // photo is low-res, but whether the specific preview we could actually recover is big enough to
    // trust a vision judgment on. A RAW file's true capture can be high-res while its only
    // recoverable preview is tiny (verified: a real Canon CR2's true resolution is 3504x2336, but
    // its recoverable EXIF thumbnail is only 160x120).
    private static final int MIN_JUDGEABLE_DIMENSION = LowResGate.MIN_DIMENSION;

    private final HeifDecoder heifDecoder;

    /**
     * Creates a renderer backed by the given HEIF/HEIC/AVIF decoder.
     *
     * @param heifDecoder {@link HeifDecoder} the decoder used for HEIF-family formats
     */
    public TileRenderer(HeifDecoder heifDecoder) {
        this.heifDecoder = heifDecoder;
    }

    /**
     * A rendered tile together with whether it is safe to show a vision model for a keep/junk
     * judgment.
     *
     * <p>{@code unreviewable} covers two different underlying cases the same way. One is a drawn
     * placeholder with no photo content at all. The other is a real but too-small recovered
     * preview, whose detail isn't enough to trust fine judgment calls like blur or a
     * photo-of-a-screen. Both mean the same thing to a caller assembling a montage: skip the
     * vision pass for this file, and route it elsewhere instead.
     */
    public record TileResult(BufferedImage image, boolean unreviewable) {
    }

    /**
     * Renders a tile-sized preview image for a media file, falling back to a placeholder if
     * decoding fails.
     *
     * @param file {@link Path} the media file to render
     * @param tileSize int the target tile size in pixels
     * @return {@link TileResult} the rendered tile result
     */
    public TileResult render(Path file, int tileSize) {
        String extension = MediaTypeDetector.extensionOf(file);
        if (extension.equals("svg")) {
            // A ".svg" file that isn't actually valid SVG happens in practice. A real file in this
            // project's own library is a PNG mislabeled with an .svg extension. Falling back to
            // the generic raster decode recovers a real tile for it instead of a placeholder.
            // A vector image has no fixed source resolution to judge - Batik renders it at
            // whatever size we ask for, so it's always reviewable when real.
            return renderSvg(file, tileSize)
                    .map(image -> new TileResult(image, false))
                    .or(() -> rasterResult(file, tileSize))
                    .orElseGet(() -> placeholderResult(tileSize, "SVG"));
        }
        if (extension.equals("heic") || extension.equals("heif") || extension.equals("avif")) {
            // AVIF shares HEIC/HEIF's ISOBMFF container. It's decodable by the same libheif
            // library, just with an AV1 payload instead of HEVC. Routed through the same port so a
            // real libheif-backed adapter picks up AVIF for free, with no separate decoder needed.
            return heifDecoder.decode(file)
                    .flatMap(image -> resizedResult(image, tileSize))
                    .orElseGet(() -> placeholderResult(tileSize, extension.toUpperCase(Locale.ROOT)));
        }
        return rasterResult(file, tileSize)
                .or(() -> exifThumbnailResult(file, tileSize))
                .orElseGet(() -> placeholderResult(tileSize, placeholderLabel(extension)));
    }

    /**
     * Builds a placeholder tile result marked unreviewable.
     *
     * @param tileSize int the tile size in pixels
     * @param label {@link String} the label to draw on the placeholder
     * @return {@link TileResult} the placeholder tile result
     */
    private static TileResult placeholderResult(int tileSize, String label) {
        return new TileResult(placeholder(tileSize, label), true);
    }

    /**
     * Picks the placeholder label for a file extension.
     *
     * @param extension {@link String} the file extension
     * @return {@link String} the label to draw on the placeholder tile
     */
    private static String placeholderLabel(String extension) {
        return RAW_EXTENSIONS.contains(extension) ? extension.toUpperCase(Locale.ROOT) : "NO PREVIEW";
    }

    /**
     * Renders a raster image file and determines whether its source resolution is judgeable.
     *
     * @param file {@link Path} the raster file to render
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link TileResult}, or empty if decoding failed
     */
    private static Optional<TileResult> rasterResult(Path file, int tileSize) {
        return renderRaster(file, tileSize)
                .map(image -> new TileResult(image, isSourceUnreviewable(file)));
    }

    /**
     * Decodes and resizes a raster image file to the tile size.
     *
     * @param file {@link Path} the file to decode
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link BufferedImage}, the resized image, or empty if decoding
     *     failed
     */
    private static Optional<BufferedImage> renderRaster(Path file, int tileSize) {
        try {
            return Optional.ofNullable(
                    Thumbnails.of(file.toFile())
                            .size(tileSize, tileSize)
                            .useExifOrientation(true)
                            .asBufferedImage());
        } catch (IOException | RuntimeException _) {
            // Thumbnailator throws unchecked exceptions too (e.g. IllegalArgumentException) when
            // no ImageIO reader claims the file, not just IOException. One bad file falls through
            // to the placeholder here rather than aborting the whole montage build.
            return Optional.empty();
        }
    }

    /**
     * Thumbnailator's file-based decode (renderRaster above) doesn't expose the source image's
     * pre-resize dimensions - it decodes and resizes in one step. This is a separate, lightweight
     * header-only read (same technique ImageDimensionsReader uses), purely to answer "was the
     * actual decoded source big enough to judge", without touching the already-tested decode path
     * above or re-decoding the full image.
     *
     * @param file {@link Path} the file whose source resolution to check
     * @return boolean true if the source is too small to judge
     */
    private static boolean isSourceUnreviewable(Path file) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                // Index 0 specifically, matching what renderRaster's own decode actually used -
                // not the largest across every sub-image (a different question ImageDimensionsReader
                // answers, about the file's true metadata resolution rather than what was decoded).
                // Confirmed by reading Thumbnailator 0.4.21's own source
                // (InputStreamImageSource.FIRST_IMAGE_INDEX = 0): its file-based decode always reads
                // index 0, for any file type, never the largest or a format-specific choice. That's
                // an internal implementation detail of a third-party library, not a documented
                // contract - worth re-checking here if Thumbnailator is ever upgraded.
                return Math.max(reader.getWidth(0), reader.getHeight(0)) < MIN_JUDGEABLE_DIMENSION;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException _) {
            // Unknown size fails open (treated as reviewable), matching LowResGate's own philosophy
            // for a file whose dimensions couldn't be determined.
            return false;
        }
    }

    /**
     * Many RAW formats store their main pixel data in a compression scheme TwelveMonkeys' generic
     * TIFF reader can't decode. Verified: a real Canon CR2's primary image fails with "Missing
     * TIFF tag JPEGQTables". But the file still carries a standard EXIF embedded thumbnail. That
     * thumbnail is a complete, independently-decodable JPEG blob per the EXIF spec, unlike the
     * TIFF-compressed main image, extractable via its offset/length tags with no need to
     * understand the RAW format at all. Verified empirically against two real cameras: an old
     * Canon EOS 20D's recoverable thumbnail is a tiny 160x120 (below the judgeable bar), while a
     * current Sony ILCE-6700's is a near-full-resolution 6192x4128 (well above it) - modern camera
     * files are not assumed to have the same tiny-preview problem older ones do.
     *
     * @param file {@link Path} the RAW file to extract a thumbnail from
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link TileResult}, the resized thumbnail tile result, or empty
     *     if unavailable
     */
    private static Optional<TileResult> exifThumbnailResult(Path file, int tileSize) {
        try {
            var metadata = ImageMetadataReader.readMetadata(file.toFile());
            var directory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
            // The IDE doesn't recognize metadata-extractor's own @Nullable annotation on this
            // method. The branch is real, exercised by TileRendererTest's no-EXIF-metadata fixture.
            //noinspection ConstantValue
            if (directory == null) {
                return Optional.empty();
            }
            Integer length = directory.getInteger(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH);
            if (length == null || !isPlausibleThumbnailLength(length)) {
                return Optional.empty();
            }
            byte[] bytes = new byte[length];
            try (var raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(directory.getAdjustedThumbnailOffset());
                raf.readFully(bytes);
            }
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            return decoded == null ? Optional.empty() : resizedResult(decoded, tileSize);
        } catch (ImageProcessingException | IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * The tag's value comes straight from the file's own bytes, not something this code controls.
     * A corrupted or adversarial file reporting an enormous length must not trigger a huge
     * allocation. A real EXIF thumbnail is always a small preview image. This cap is generous
     * relative to any legitimate one - the real Canon CR2 fixture used here is 6162 bytes - while
     * still ruling out a bogus multi-gigabyte value. Package-private (not private) so
     * TileRendererTest can exercise the boundary directly.
     *
     * @param length int the claimed thumbnail byte length
     * @return boolean true if the length is a plausible thumbnail size
     */
    static boolean isPlausibleThumbnailLength(int length) {
        return length > 0 && length <= MAX_EXIF_THUMBNAIL_BYTES;
    }

    /**
     * Resizes an already-decoded image and determines whether its resolution is judgeable.
     *
     * @param source {@link BufferedImage} the decoded source image
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link TileResult}, or empty if resizing failed
     */
    private static Optional<TileResult> resizedResult(BufferedImage source, int tileSize) {
        boolean unreviewable = Math.max(source.getWidth(), source.getHeight()) < MIN_JUDGEABLE_DIMENSION;
        return resize(source, tileSize).map(image -> new TileResult(image, unreviewable));
    }

    /**
     * Renders an SVG file to a raster image sized to preserve its aspect ratio.
     *
     * @param file {@link Path} the SVG file to render
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link BufferedImage}, or empty if transcoding failed
     */
    private static Optional<BufferedImage> renderSvg(Path file, int tileSize) {
        // Batik's own default canvas is a fixed 400x400 square, regardless of the document's real
        // aspect ratio, whenever width/height transcoding hints aren't given. Verified directly
        // against a real viewBox-only fixture - a common, valid SVG authoring style that omits
        // width/height in favor of viewBox alone. That fixture came out visibly stretched to
        // square before this fix. Parsing the real aspect ratio ourselves and passing explicit
        // hints for both dimensions is the only way to get Batik to honor it.
        double aspect = svgAspectRatio(file);
        float renderSize = tileSize * 2f;
        var transcoder = new BufferedImageTranscoder();
        if (aspect >= 1) {
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, renderSize);
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) (renderSize / aspect));
        } else {
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, renderSize);
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) (renderSize * aspect));
        }
        try {
            transcoder.transcode(new TranscoderInput(file.toUri().toString()), null);
        } catch (TranscoderException | RuntimeException _) {
            // Batik's transcode pipeline can throw unchecked exceptions too (e.g. from its CSS
            // cascade or DOM handling) for a malformed-but-well-formed-XML SVG, not just the
            // checked TranscoderException. One bad file falls through to the raster path or
            // placeholder here, matching renderRaster's own defense, rather than aborting the
            // whole montage build.
            return Optional.empty();
        }
        BufferedImage rendered = transcoder.image();
        return rendered == null ? Optional.empty() : resize(rendered, tileSize);
    }

    /**
     * Reads only the root {@code <svg>} element's viewBox (preferred) or width/height attributes
     * to determine the document's real aspect ratio, without doing a full Batik render first.
     * Falls back to 1.0 (square) - the same default Batik itself uses. That fallback fires when
     * the file isn't parseable XML at all (e.g. a raster file mislabeled with an .svg extension),
     * or when it declares neither viewBox nor width/height.
     *
     * @param file {@link Path} the SVG file to inspect
     * @return double the document's width/height aspect ratio
     */
    private static double svgAspectRatio(Path file) {
        try {
            // Many real-world SVGs (Illustrator exports especially) carry the standard SVG 1.1
            // public DOCTYPE prolog. Blocking DOCTYPE outright would silently lose the
            // aspect-ratio fix for those legitimate files too. Blocking external entities and DTD
            // fetching instead - the actual XXE vector - keeps a normal DOCTYPE parseable while
            // staying safe against arbitrary user-supplied SVG content. This is the standard OWASP
            // XXE-prevention posture for when DOCTYPE itself can't just be disallowed.
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // These are fixed SAX/JAXP feature-name strings defined by spec, never dereferenced
            // over the network - not real links, so there's no https variant to switch to.
            //noinspection HttpUrlsUsage
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            //noinspection HttpUrlsUsage
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            //noinspection HttpUrlsUsage
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            // A raster file mislabeled with an .svg extension fails XML parsing. That's an
            // expected, already-handled outcome here - it falls through to the 1.0 default. The
            // parser's default handler still logs fatal errors to stderr regardless, even though
            // the exception itself is caught, so a no-op handler silences that noise. (Passing
            // null instead of a no-op instance would just restore the noisy default handler.)
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root = builder.parse(file.toFile()).getDocumentElement();
            double[] viewBox = parseViewBox(root.getAttribute("viewBox"));
            if (viewBox != null) {
                return viewBox[0] / viewBox[1];
            }
            double width = parseLength(root.getAttribute("width"));
            double height = parseLength(root.getAttribute("height"));
            if (width > 0 && height > 0) {
                return width / height;
            }
        } catch (Exception _) {
            // Not parseable as XML at all, or malformed. The caller's own transcode attempt will
            // fail identically and fall back to the raster path or placeholder.
        }
        return 1.0;
    }

    /**
     * Parses an SVG viewBox attribute into width/height.
     *
     * @param viewBox {@link String} the raw viewBox attribute value
     * @return double[] the parsed [width, height], or null if not usable
     */
    private static double @Nullable [] parseViewBox(String viewBox) {
        if (viewBox.isBlank()) {
            return null;
        }
        String[] parts = viewBox.trim().split("[\\s,]+");
        if (parts.length != 4) {
            return null;
        }
        double width = Double.parseDouble(parts[2]);
        double height = Double.parseDouble(parts[3]);
        return width > 0 && height > 0 ? new double[] {width, height} : null;
    }

    /**
     * Parses an SVG length attribute as an absolute number.
     *
     * @param length {@link String} the raw length attribute value
     * @return double the parsed absolute length, or zero if not usable
     */
    private static double parseLength(String length) {
        // A percentage carries no absolute size on its own - it's relative to a reference
        // viewport this isolated-attribute read has no access to. Treated as unusable rather than
        // parsed as if the number before the '%' were an absolute length.
        if (length.isBlank() || length.endsWith("%")) {
            return 0;
        }
        String numeric = length.replaceAll("[^0-9.]+$", "");
        try {
            return numeric.isBlank() ? 0 : Double.parseDouble(numeric);
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /**
     * Resizes a decoded image to the tile size.
     *
     * @param source {@link BufferedImage} the image to resize
     * @param tileSize int the target tile size in pixels
     * @return an {@link Optional} {@link BufferedImage}, or empty if resizing failed
     */
    private static Optional<BufferedImage> resize(BufferedImage source, int tileSize) {
        try {
            return Optional.ofNullable(Thumbnails.of(source).size(tileSize, tileSize).asBufferedImage());
        } catch (IOException | RuntimeException _) {
            // Keeps render()'s "never throws" contract even for an already-decoded image. An
            // unusual color model or extreme dimensions from a HEIC/AVIF/SVG decode could
            // otherwise propagate out as an unchecked failure instead of degrading to a
            // placeholder.
            return Optional.empty();
        }
    }

    /**
     * Draws a stand-in tile for a file that couldn't be decoded: a solid dark gray square with
     * the label centered in bold white. Distinct from the montage's own background, so it reads
     * clearly as "no preview".
     *
     * @param tileSize int the placeholder's size in pixels
     * @param label {@link String} the label to draw
     * @return {@link BufferedImage} the placeholder image
     */
    private static BufferedImage placeholder(int tileSize, String label) {
        var image = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x44, 0x44, 0x44));
            g.fillRect(0, 0, tileSize, tileSize);
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, tileSize / 10f));
            FontMetrics metrics = g.getFontMetrics();
            int textWidth = metrics.stringWidth(label);
            int x = (tileSize - textWidth) / 2;
            int y = (tileSize - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(label, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Captures Batik's rendered raster directly in memory. Batik's {@code Transcoder} API writes
     * to a {@link TranscoderOutput} (a stream). There is no built-in way to get a
     * {@link BufferedImage} back directly, so this override captures the raster itself instead.
     * This is the standard idiom for using Batik as an in-process SVG decoder rather than a
     * file-to-file tool.
     */
    private static final class BufferedImageTranscoder extends ImageTranscoder {

        private @Nullable BufferedImage image;

        /**
         * Creates the in-memory image Batik renders into.
         *
         * @param width int the image width
         * @param height int the image height
         * @return {@link BufferedImage} a new ARGB buffered image
         */
        @Override
        public BufferedImage createImage(int width, int height) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        /**
         * Captures the rendered image instead of writing it to the output stream.
         *
         * @param image {@link BufferedImage} the rendered image
         * @param output {@link TranscoderOutput} unused, required by the Transcoder API
         */
        @Override
        public void writeImage(BufferedImage image, TranscoderOutput output) {
            this.image = image;
        }

        /**
         * Returns the captured rendered image.
         *
         * @return {@link BufferedImage} the rendered image, or null if not yet rendered
         */
        @Nullable BufferedImage image() {
            return image;
        }
    }
}
