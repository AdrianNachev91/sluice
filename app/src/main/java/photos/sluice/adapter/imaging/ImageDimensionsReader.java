package photos.sluice.adapter.imaging;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ImageDimensionsPort;
import photos.sluice.domain.model.Dimensions;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.nio.file.Path;
import java.util.Optional;

@Component
public class ImageDimensionsReader implements ImageDimensionsPort {

    @Override
    public Optional<Dimensions> read(Path file) {
        return readExifSubIfd(file).or(() -> readViaImageIo(file));
    }

    private static Optional<Dimensions> readExifSubIfd(Path file) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (ImageProcessingException | IOException | RuntimeException _) {
            // metadata-extractor throws unchecked exceptions (e.g. ArrayIndexOutOfBoundsException)
            // on some malformed real-world files, not just its checked exception type; one bad
            // file falls through to the ImageIO fallback rather than aborting.
            return Optional.empty();
        }
        var directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        // The IDE doesn't recognize metadata-extractor's own @Nullable annotation on this method;
        // the branch is real (exercised by ImageDimensionsReaderTest's no-EXIF fixture).
        //noinspection ConstantValue
        if (directory == null) {
            return Optional.empty();
        }
        // The Exif SubIFD's own width/height tags are the real capture resolution; a container's
        // top-level width/height tag (e.g. HEIF's) can instead be an embedded thumbnail, so only
        // this specific directory/tag pair is trusted here.
        Integer width = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
        Integer height = directory.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
        if (width == null || height == null) {
            return Optional.empty();
        }
        return Optional.of(new Dimensions(width, height));
    }

    // A multi-image file (e.g. a TIFF with an embedded thumbnail) exposes images in raw physical
    // order with no marker for "which one is the real photo" - the largest by pixel dimension
    // across every index is taken, never index 0 alone.
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
