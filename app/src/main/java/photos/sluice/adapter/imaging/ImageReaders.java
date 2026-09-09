package photos.sluice.adapter.imaging;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Opens a header-only ImageIO reader over a file, hands it to a read, and disposes of it either
 * way.
 */
final class ImageReaders {

    private ImageReaders() {
    }

    /**
     * What a read makes of one reader already positioned on a file.
     *
     * @param <T> what the read answers
     */
    @FunctionalInterface
    interface Read<T> {

        /**
         * Reads whatever the caller came for.
         *
         * @param reader {@link ImageReader} a reader with its input already set
         * @return the answer
         * @throws IOException where the reader cannot answer
         */
        T from(ImageReader reader) throws IOException;
    }

    /**
     * Hands file's reader to read, answering whenUnreadable where no reader claims the file or the
     * read itself fails.
     *
     * <p>Every failure lands on whenUnreadable rather than propagating, so one file nothing can
     * decode is one answer rather than an aborted pass over a whole folder.
     *
     * @param file {@link Path} the file to open
     * @param read a {@link Read} what to ask the reader
     * @param whenUnreadable the answer where nothing could be read
     * @param <T> what the read answers
     * @return what read made of the file, or whenUnreadable
     */
    static <T> T readOrElse(final Path file, final Read<T> read, final T whenUnreadable) {
        try (final ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                return whenUnreadable;
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return whenUnreadable;
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return read.from(reader);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException _) {
            return whenUnreadable;
        }
    }
}
