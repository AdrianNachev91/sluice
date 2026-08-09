package photos.sluice.adapter.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.heif.HeifDirectory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ImageDimensionsPort;
import photos.sluice.domain.imaging.LowResGate;
import photos.sluice.domain.model.Dimensions;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link ImageDimensionsPort} that reads an image's true pixel dimensions from embedded
 * metadata where possible. A decode via ImageIO backs that up, either when no usable metadata
 * exists or when the metadata answer is small enough to change a routing decision.
 *
 * <p>Camera and container formats can expose more than one directory that might carry dimensions.
 * A RAW file's multi-image chain can carry multiple Exif SubIFDs, and a HEIC/AVIF file can carry
 * multiple HEIF directories. Not every directory found holds the real capture resolution.
 * Every candidate directory is checked, and the largest reported dimensions win. A small embedded
 * preview therefore loses to any sibling directory carrying the true size. Where no sibling
 * carries it, the decode described on {@link #read} is the second opinion instead.
 */
@Component
public class ImageDimensionsReader implements ImageDimensionsPort {

    /**
     * Reads an image's pixel dimensions. Embedded metadata answers first. A decode via ImageIO
     * cross-checks that answer whenever it comes out small.
     *
     * <p>Any single metadata directory can describe a sub-image rather than the capture. Two real
     * shapes do exactly that. An editor can resize a photo and leave the EXIF pixel-dimension tags
     * behind at the old size. A tiled HEIC stores its picture as a grid of small tiles. The
     * container box a metadata parse reaches there can carry one tile's size, not the grid's.
     *
     * <p>At or above {@link LowResGate#MIN_DIMENSION} the exact number changes no routing decision,
     * so the metadata answer stands and the decode is skipped. Below it the number decides whether a
     * file is called low-res, so the metadata reading counts as a candidate rather than an answer.
     * The decode then runs as a second source and the larger of the two wins. When no decoder can
     * read the format at all, nothing corroborates the small reading, so empty is reported. An
     * unknown size leaves a real photo where it belongs, while an uncorroborated small one exiles
     * it as low-res.
     *
     * @param file {@link Path} the image file to read
     * @return an {@link Optional} {@link Dimensions}; empty if none could be determined, or if a
     *         sub-threshold metadata reading had no decoder available to corroborate it
     */
    @Override
    public Optional<Dimensions> read(final Path file) {
        final Dimensions fromMetadata = readMetadataDimensions(file).orElse(null);
        if (fromMetadata != null && maxDimension(fromMetadata) >= LowResGate.MIN_DIMENSION) {
            return Optional.of(fromMetadata);
        }
        return readViaImageIo(file).map(decoded -> largestOf(decoded, fromMetadata));
    }

    /**
     * A file really does carry dimensions in both directory types at once. A real iPhone HEIC is
     * one: its HeifDirectory reads a 512x512 tile, its Exif SubIFD reads the true 4032x3024
     * capture. Comparing across both rather than trusting whichever type appears first extends
     * the same largest-wins safety margin largestAcross already applies within a single type.
     * Ties keep the first argument, an arbitrary but pinned-down choice - it doesn't matter which
     * equally-large candidate is reported, only that a real one is.
     *
     * @param a {@link Dimensions} the first candidate dimensions, or null
     * @param b {@link Dimensions} the second candidate dimensions, or null
     * @return {@link Dimensions} the larger of the two by maximum dimension, or whichever is
     * non-null
     */
    static @Nullable Dimensions largestOf(final @Nullable Dimensions a, final @Nullable Dimensions b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return maxDimension(a) >= maxDimension(b) ? a : b;
    }

    /**
     * A SubIFD's dimensions can be tagged either way depending on the manufacturer, verified
     * against real fixtures. Canon uses the EXIF-specific pixel-dimension tags (0xA002/0xA003).
     * Nikon instead leaves those empty on the SubIFD holding the true capture resolution, and uses
     * the generic TIFF ImageWidth/ImageHeight tags (0x0100/0x0101) there instead. Both are trusted
     * equally here, since both live on a SubIFD, not the container's own top-level directory (the
     * untrusted case the class comment above describes).
     * Package-private (not private) so ImageDimensionsReaderTest can exercise the tag-priority
     * order directly with hand-built directories, without needing a crafted real file for every
     * branch combination.
     *
     * @param directory {@link ExifSubIFDDirectory} the SubIFD directory to inspect
     * @return {@link Dimensions} the directory's width/height as dimensions, or null if neither
     * tag pair is present
     */
    static @Nullable Dimensions subIfdDimensions(final ExifSubIFDDirectory directory) {
        Integer width = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
        Integer height = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
        if (!usable(width, height)) {
            width = directory.getInteger(ExifSubIFDDirectory.TAG_IMAGE_WIDTH);
            height = directory.getInteger(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT);
        }
        return usable(width, height) ? new Dimensions(width, height) : null;
    }

    /**
     * Finds the largest dimensions reported across a collection of metadata directories.
     *
     * @param directories a {@link Collection} of T, the directories to inspect
     * @param extractor a {@link Function} of T to {@link Dimensions}, extracts candidate
     * dimensions from a single directory
     * @return {@link Dimensions} the largest dimensions found, or null if none had usable
     * dimensions
     */
    static <T extends Directory> @Nullable Dimensions largestAcross(
            final Collection<T> directories, final Function<T, @Nullable Dimensions> extractor) {
        Dimensions largest = null;
        for (final T directory : directories) {
            final Dimensions candidate = extractor.apply(directory);
            if (candidate != null && (largest == null || maxDimension(candidate) > maxDimension(largest))) {
                largest = candidate;
            }
        }
        return largest;
    }

    /**
     * A HEIF/AVIF file can expose more than one HeifDirectory. A real AVIF fixture verified this:
     * one instance carries only brand info, a separate instance carries the actual width/height.
     * Every instance is checked here too, not just the first.
     *
     * @param directory {@link HeifDirectory} the HEIF directory to inspect
     * @return {@link Dimensions} the directory's width/height as dimensions, or null if not
     * present
     */
    static @Nullable Dimensions heifDimensions(final HeifDirectory directory) {
        final Integer width = directory.getInteger(HeifDirectory.TAG_IMAGE_WIDTH);
        final Integer height = directory.getInteger(HeifDirectory.TAG_IMAGE_HEIGHT);
        return usable(width, height) ? new Dimensions(width, height) : null;
    }

    /**
     * A RAW file's multi-image IFD chain (thumbnail, preview, full capture) can expose more than
     * one Exif SubIFD directory. Verified against a real Nikon NEF fixture: its first SubIFD (the
     * embedded preview's own) carries no width/height tags at all, while a later SubIFD holds the
     * true native capture resolution. getFirstDirectoryOfType alone would silently miss it and
     * fall through to the ImageIO path's much smaller embedded thumbnail. Every SubIFD with usable
     * tags is checked here, and the largest is trusted - the same largest-not-first principle
     * largestImage() below already applies to the ImageIO fallback.
     *
     * <p>Trusting the largest is a one-directional safety margin. LowResGate only ever flags a file
     * low-res when its reported dimensions are small, so under-reporting a real capture's size
     * (the verified Nikon failure mode) is the risk this guards against. A corrupted file whose
     * non-primary SubIFD happens to report an inflated bogus value could in principle cause a
     * genuinely low-res file to escape that flag. Picking the larger candidate only ever raises the
     * reported size, so this rule on its own cannot push a photo below the flag. It also cannot
     * lift a file whose every directory under-reports, which is why read() cross-checks a small
     * result against a decode.
     *
     * <p>HEIC/HEIF/AVIF files can carry their dimensions in a separate HeifDirectory, instead of or
     * alongside an Exif SubIFD. A real AVIF fixture verified this: it has no embedded EXIF at all,
     * only the container's own native width/height box. A plain AVIF conversion with no EXIF needs
     * this second check to be found at all. A real iPhone HEIC carries both directory types. Its
     * HEIF side reads a 512x512 tile rather than the assembled picture, so the SubIFD's larger
     * value is the one that has to win.
     *
     * @param file {@link Path} the image file to inspect
     * @return an {@link Optional} {@link Dimensions}, the largest trusted dimensions found across
     * SubIFD and HEIF directories, or empty
     */
    private static Optional<Dimensions> readMetadataDimensions(final Path file) {
        final Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (ImageProcessingException | IOException | RuntimeException _) {
            // metadata-extractor throws unchecked exceptions too (e.g.
            // ArrayIndexOutOfBoundsException) on some malformed real-world files, not just its
            // checked exception type. One bad file falls through to the ImageIO fallback here
            // rather than aborting.
            return Optional.empty();
        }
        final Dimensions fromSubIfd = largestAcross(metadata.getDirectoriesOfType(ExifSubIFDDirectory.class),
                ImageDimensionsReader::subIfdDimensions);
        final Dimensions fromHeif = largestAcross(metadata.getDirectoriesOfType(HeifDirectory.class),
                ImageDimensionsReader::heifDimensions);
        return Optional.ofNullable(largestOf(fromSubIfd, fromHeif));
    }

    /**
     * A tag that is present but zero is as good as absent, and has to be rejected the same way. Real
     * phone exports carry zeroed pixel-dimension tags. Treating a zero as a real answer reports a
     * dimension of 0, which reads as the smallest possible image rather than as no answer at all.
     * That skips the decode fallback that finds the true size. It also makes a full-resolution photo
     * look low-resolution to every caller downstream.
     *
     * @param width {@link Integer} the width tag's value, or null if the tag is absent
     * @param height {@link Integer} the height tag's value, or null if the tag is absent
     * @return boolean true if both are present and positive
     */
    private static boolean usable(final @Nullable Integer width, final @Nullable Integer height) {
        return width != null && height != null && width > 0 && height > 0;
    }

    /**
     * A multi-image file (e.g. a TIFF with an embedded thumbnail) exposes images in raw physical
     * order, with no marker for "which one is the real photo". The largest by pixel dimension
     * across every index is taken here, never index 0 alone.
     *
     * @param file {@link Path} the image file to decode
     * @return an {@link Optional} {@link Dimensions}, the largest image's dimensions, or empty if
     * no reader could handle the file
     */
    private static Optional<Dimensions> readViaImageIo(final Path file) {
        try (final ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                return Optional.empty();
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return largestImage(reader);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * Finds the largest image dimensions across every image index a reader exposes.
     *
     * @param reader {@link ImageReader} the image reader positioned on an input source
     * @return an {@link Optional} {@link Dimensions}, the largest dimensions found, or empty if
     * the reader exposes no images
     */
    private static Optional<Dimensions> largestImage(final ImageReader reader) throws IOException {
        Dimensions largest = null;
        final int numImages = reader.getNumImages(true);
        for (int i = 0; i < numImages; i++) {
            // Same rejection as the metadata path. A reader reporting zero has no answer either,
            // and nothing sits behind this one to recover from a zero treated as real.
            final int width = reader.getWidth(i);
            final int height = reader.getHeight(i);
            final Dimensions candidate = usable(width, height) ? new Dimensions(width, height) : null;
            if (candidate != null && (largest == null || maxDimension(candidate) > maxDimension(largest))) {
                largest = candidate;
            }
        }
        return Optional.ofNullable(largest);
    }

    /**
     * Returns the larger of a dimensions' width and height.
     *
     * @param dimensions {@link Dimensions} the dimensions to measure
     * @return int the largest single dimension
     */
    private static int maxDimension(final Dimensions dimensions) {
        return Math.max(dimensions.width(), dimensions.height());
    }
}
