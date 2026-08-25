package photos.sluice.application.port.in;

/**
 * Thrown when an import is refused over what it was pointed at, before it starts or once it is
 * under way.
 *
 * <p>Its message is written for the person who picked the folder, so a caller shows the sentence as
 * it stands.
 */
public class ImportSourceException extends IllegalArgumentException {

    /**
     * Creates the refusal.
     *
     * @param said {@link String} what is wrong with what was chosen
     */
    public ImportSourceException(final String said) {
        super(said);
    }

    /**
     * Creates the refusal over the failure that produced it.
     *
     * @param said {@link String} what is wrong with what was chosen
     * @param cause {@link Throwable}
     */
    public ImportSourceException(final String said, final Throwable cause) {
        super(said, cause);
    }
}
