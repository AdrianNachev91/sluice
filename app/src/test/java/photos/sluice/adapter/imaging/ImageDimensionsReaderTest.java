package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    private final ImageDimensionsReader reader = new ImageDimensionsReader();

    @Test
    void readsExifSubIfdDimensionsFromIphoneHeic() {
        Optional<Dimensions> result = reader.read(FIXTURES.resolve("iphone-exif.heic"));

        assertThat(result).contains(new Dimensions(4032, 3024));
    }

    @Test
    void fallsBackToImageIoWhenNoExifPresent() {
        Optional<Dimensions> result = reader.read(FIXTURES.resolve("no-exif.jpg"));

        assertThat(result).contains(new Dimensions(2, 2));
    }

    @Test
    void returnsEmptyWhenFileIsNotAnImage() {
        Optional<Dimensions> result = reader.read(FIXTURES.resolve("not-an-image.txt"));

        assertThat(result).isEmpty();
    }

    @Test
    void multiImageTiffTakesTheLargestIndexNotTheFirst(@TempDir Path tempDir) throws IOException {
        Path tiff = tempDir.resolve("multi-image.tiff");
        writeTwoImageTiff(tiff, 64, 64, 512, 400);

        Optional<Dimensions> result = reader.read(tiff);

        assertThat(result).contains(new Dimensions(512, 400));
    }

    private static void writeTwoImageTiff(
            Path target, int firstWidth, int firstHeight, int secondWidth, int secondHeight)
            throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.writeToSequence(
                    new javax.imageio.IIOImage(blankImage(firstWidth, firstHeight), null, null), param);
            writer.writeToSequence(
                    new javax.imageio.IIOImage(blankImage(secondWidth, secondHeight), null, null), param);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage blankImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
