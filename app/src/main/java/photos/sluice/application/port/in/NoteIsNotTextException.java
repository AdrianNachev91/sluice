package photos.sluice.application.port.in;

import java.nio.file.Path;

/**
 * A note Sluice wrote beside a folder's photos, which holds something other than text.
 *
 * <p>Carries the file, which is what separates this from the generic failure a reader would
 * otherwise meet. A folder can hold several notes: one per near-copy group, plus the one a sort and
 * a sift append to. Naming none of them leaves the reader with nothing to open.
 *
 * @see RescueUseCase#rescue
 */
public class NoteIsNotTextException extends RuntimeException {

    private final transient Path file;

    /**
     * Creates the failure over the note that could not be read as text.
     *
     * @param file {@link Path} the note itself
     * @param cause {@link Throwable} what the read threw
     */
    public NoteIsNotTextException(final Path file, final Throwable cause) {
        super(file.toString(), cause);
        this.file = file;
    }

    /**
     * The note whose bytes are not text.
     *
     * @return {@link Path} the file to name to whoever has to fix it
     */
    public Path file() {
        return this.file;
    }
}
