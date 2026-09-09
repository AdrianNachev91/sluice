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

import javax.imageio.ImageReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link ImageDimensionsPort} that reads an image's true pixel dimensions from embedded
 * metadata where possible. A decode via ImageIO backs that up, either when no usable metadata
 * exists or when the metadata answer is small enough to change a routing decision.
 *
 * <p>Camera and container formats can expose more than one directory that might carry dimensions,
 * and not every one holds the real capture resolution. Every candidate directory is checked, and
 * the largest reported dimensions win, so a small embedded preview loses to any sibling carrying
 * the true size. Where no sibling carries it, the decode described on {@link #read} is the second
 * opinion instead.
 *
 * <p>Flowchart, the real-fixture evidence and the scenario table:
 * {@code app/docs/design/adapter/imaging/image-dimensions-reader.md}.
 */
@Component
public class ImageDimensionsReader implements ImageDimensionsPort {

    /**
     * Reads an image's pixel dimensions. Embedded metadata answers first. A decode via ImageIO
     * cross-checks that answer whenever it comes out small.
     *
     * <p>Any single metadata directory can describe a sub-image rather than the capture. An editor
     * can resize a photo and leave the EXIF pixel-dimension tags behind at the old size, and a
     * tiled HEIC's container box can carry one tile's size rather than the grid's.
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
     * A file can carry dimensions in both directory types at once, one of them a tile or a preview.
     * Largest wins, rather than whichever appears first. Ties keep the first argument, an arbitrary
     * but pinned-down choice.
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
     * A SubIFD's dimensions can be tagged either way depending on the manufacturer: the
     * EXIF-specific pixel-dimension tags, or the generic TIFF ImageWidth/ImageHeight pair. Both are
     * trusted equally, since both live on a SubIFD rather than on the container's own top-level
     * directory. Package-private so a test can exercise the tag-priority order with hand-built
     * directories, rather than needing a crafted real file per branch.
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
            largest = largestOf(largest, extractor.apply(directory));
        }
        return largest;
    }

    /**
     * One HeifDirectory's width/height, where it has them. A HEIF/AVIF file can expose several, one
     * carrying only brand info and another the real size, which is why the caller runs this across
     * every instance rather than the first.
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
     * The largest dimensions any Exif SubIFD or HeifDirectory reports. A RAW file's multi-image IFD
     * chain can expose several SubIFDs where only a later one holds the true capture. A HEIF or
     * AVIF file can carry its size in a HeifDirectory instead of, or alongside, a SubIFD. Taking
     * the first of either type silently loses those.
     *
     * <p>Trusting the largest is a one-directional safety margin. {@link LowResGate} only ever flags
     * a file low-res on small reported dimensions, so under-reporting a real capture is the risk
     * this guards against. Picking the larger candidate can only raise the reported size, so this
     * rule alone cannot push a photo below the flag. It also cannot lift a file whose every
     * directory under-reports, which is why {@link #read} cross-checks a small result against a
     * decode.
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
     * A tag that is present but zero is as good as absent, and real phone exports carry zeroed
     * pixel-dimension tags. Taking a zero as an answer reports the smallest possible image rather
     * than no answer, which skips the decode fallback and makes a full-resolution photo look
     * low-resolution downstream.
     *
     * @param width {@link Integer} the width tag's value, or null if the tag is absent
     * @param height {@link Integer} the height tag's value, or null if the tag is absent
     * @return boolean true if both are present and positive
     */
    private static boolean usable(final @Nullable Integer width, final @Nullable Integer height) {
        return width != null && height != null && width > 0 && height > 0;
    }

    /**
     * The file's own dimensions, decoded rather than read off metadata.
     *
     * @param file {@link Path} the image file to decode
     * @return an {@link Optional} {@link Dimensions}, the largest image's dimensions, or empty if
     * no reader could handle the file
     */
    private static Optional<Dimensions> readViaImageIo(final Path file) {
        return ImageReaders.readOrElse(file, ImageDimensionsReader::largestImage, Optional.empty());
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
            largest = largestOf(largest, usable(width, height) ? new Dimensions(width, height) : null);
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
