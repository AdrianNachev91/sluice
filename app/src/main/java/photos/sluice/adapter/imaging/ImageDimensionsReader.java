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

@Component
public class ImageDimensionsReader implements ImageDimensionsPort {

    @Override
    public Optional<Dimensions> read(Path file) {
        return readMetadataDimensions(file).or(() -> readViaImageIo(file));
    }

    // A RAW file's multi-image IFD chain (thumbnail, preview, full capture) can expose more than
    // one Exif SubIFD directory. Verified against a real Nikon NEF fixture: its first SubIFD (the
    // embedded preview's own) carries no width/height tags at all, while a later SubIFD holds the
    // true native capture resolution. getFirstDirectoryOfType alone would silently miss it and
    // fall through to the ImageIO path's much smaller embedded thumbnail. Every SubIFD with usable
    // tags is checked here, and the largest is trusted - the same largest-not-first principle
    // largestImage() below already applies to the ImageIO fallback.
    //
    // Trusting the largest is a one-directional safety margin. LowResGate only ever flags a file
    // low-res when its reported dimensions are small, so under-reporting a real capture's size
    // (the verified Nikon failure mode) is the risk this guards against. A corrupted file whose
    // non-primary SubIFD happens to report an inflated bogus value could in principle cause a
    // genuinely low-res file to escape that flag. It could never cause the reverse: misrouting a
    // real high-res photo as low-res.
    //
    // HEIC/HEIF/AVIF files can carry their dimensions in a separate HeifDirectory, instead of or
    // alongside an Exif SubIFD. A real AVIF fixture verified this: it has no embedded EXIF at all,
    // only the container's own native width/height box. A real iPhone HEIC works via the SubIFD
    // path alone, since Apple's own HEIC files do carry full EXIF. A plain AVIF conversion with no
    // EXIF needs this second check to be found at all.
    private static Optional<Dimensions> readMetadataDimensions(Path file) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (ImageProcessingException | IOException | RuntimeException _) {
            // metadata-extractor throws unchecked exceptions too (e.g.
            // ArrayIndexOutOfBoundsException) on some malformed real-world files, not just its
            // checked exception type. One bad file falls through to the ImageIO fallback here
            // rather than aborting.
            return Optional.empty();
        }
        Dimensions fromSubIfd = largestAcross(metadata.getDirectoriesOfType(ExifSubIFDDirectory.class), ImageDimensionsReader::subIfdDimensions);
        Dimensions fromHeif = largestAcross(metadata.getDirectoriesOfType(HeifDirectory.class), ImageDimensionsReader::heifDimensions);
        return Optional.ofNullable(largestOf(fromSubIfd, fromHeif));
    }

    // A file could in principle carry dimensions in both directory types at once (an edited HEIC
    // whose EXIF wasn't refreshed to match a later HEIF-box resize, for example). Comparing across
    // both rather than trusting whichever type appears first extends the same largest-wins
    // safety margin largestAcross already applies within a single type. Ties keep the first
    // argument, an arbitrary but pinned-down choice - it doesn't matter which equally-large
    // candidate is reported, only that a real one is.
    static @Nullable Dimensions largestOf(@Nullable Dimensions a, @Nullable Dimensions b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return maxDimension(a) >= maxDimension(b) ? a : b;
    }

    static <T extends Directory> @Nullable Dimensions largestAcross(
            Collection<T> directories, Function<T, @Nullable Dimensions> extractor) {
        Dimensions largest = null;
        for (T directory : directories) {
            Dimensions candidate = extractor.apply(directory);
            if (candidate != null && (largest == null || maxDimension(candidate) > maxDimension(largest))) {
                largest = candidate;
            }
        }
        return largest;
    }

    // A SubIFD's dimensions can be tagged either way depending on the manufacturer, verified
    // against real fixtures. Canon uses the EXIF-specific pixel-dimension tags (0xA002/0xA003).
    // Nikon instead leaves those empty on the SubIFD holding the true capture resolution, and uses
    // the generic TIFF ImageWidth/ImageHeight tags (0x0100/0x0101) there instead. Both are trusted
    // equally here, since both live on a SubIFD, not the container's own top-level directory (the
    // untrusted case the class comment above describes).
    // Package-private (not private) so ImageDimensionsReaderTest can exercise the tag-priority
    // order directly with hand-built directories, without needing a crafted real file for every
    // branch combination.
    static @Nullable Dimensions subIfdDimensions(ExifSubIFDDirectory directory) {
        Integer width = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
        Integer height = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
        if (width == null || height == null) {
            width = directory.getInteger(ExifSubIFDDirectory.TAG_IMAGE_WIDTH);
            height = directory.getInteger(ExifSubIFDDirectory.TAG_IMAGE_HEIGHT);
        }
        return width == null || height == null ? null : new Dimensions(width, height);
    }

    // A HEIF/AVIF file can expose more than one HeifDirectory. A real AVIF fixture verified this:
    // one instance carries only brand info, a separate instance carries the actual width/height.
    // Every instance is checked here too, not just the first.
    static @Nullable Dimensions heifDimensions(HeifDirectory directory) {
        Integer width = directory.getInteger(HeifDirectory.TAG_IMAGE_WIDTH);
        Integer height = directory.getInteger(HeifDirectory.TAG_IMAGE_HEIGHT);
        return width == null || height == null ? null : new Dimensions(width, height);
    }

    // A multi-image file (e.g. a TIFF with an embedded thumbnail) exposes images in raw physical
    // order, with no marker for "which one is the real photo". The largest by pixel dimension
    // across every index is taken here, never index 0 alone.
    private static Optional<Dimensions> readViaImageIo(Path file) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
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

    private static Optional<Dimensions> largestImage(ImageReader reader) throws IOException {
        Dimensions largest = null;
        int numImages = reader.getNumImages(true);
        for (int i = 0; i < numImages; i++) {
            var candidate = new Dimensions(reader.getWidth(i), reader.getHeight(i));
            if (largest == null || maxDimension(candidate) > maxDimension(largest)) {
                largest = candidate;
            }
        }
        return Optional.ofNullable(largest);
    }

    private static int maxDimension(Dimensions dimensions) {
        return Math.max(dimensions.width(), dimensions.height());
    }
}
