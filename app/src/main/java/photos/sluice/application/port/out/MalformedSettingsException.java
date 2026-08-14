package photos.sluice.application.port.out;

import java.nio.file.Path;

/**
 * Thrown when the stored settings file cannot be understood, so nothing can be written back into
 * it. That covers text that is not valid YAML at all, and an entry written as something other than
 * the group of settings it has to be.
 *
 * <p>Deliberately not an {@link IllegalStateException}. That supertype is the refusal family: a job
 * running, a folder root that cannot be worked in, a working root another process holds. Each of
 * those names something the user can put right on the screen they are already on. A broken config
 * file is a different sentence, and a surface catching the family to render "refused" would say the
 * wrong one about it.
 *
 * <p>It is not an I/O failure either. The bytes were read. What failed is making sense of them, and
 * trying again changes nothing until the file itself is edited.
 *
 * <p>It carries the file, so a surface can offer to open it without reading a path back out of the
 * message.
 */
public final class MalformedSettingsException extends RuntimeException {

    private final transient Path settingsFile;

    /**
     * Creates the exception, naming the file that cannot be understood.
     *
     * @param settingsFile {@link Path} the settings file
     * @param message {@link String} what was found in it, and what to do about it
     */
    public MalformedSettingsException(final Path settingsFile, final String message) {
        super(message);
        this.settingsFile = settingsFile;
    }

    /**
     * Creates the exception with the parse failure underneath it.
     *
     * @param settingsFile {@link Path} the settings file
     * @param message {@link String} what was found in it, and what to do about it
     * @param cause {@link Throwable} the underlying parse failure
     */
    public MalformedSettingsException(final Path settingsFile, final String message, final Throwable cause) {
        super(message, cause);
        this.settingsFile = settingsFile;
    }

    /**
     * The file that cannot be understood.
     *
     * @return {@link Path} the settings file
     */
    public Path settingsFile() {
        return this.settingsFile;
    }
}
