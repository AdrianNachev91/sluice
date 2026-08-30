package photos.sluice.adapter.cli;

/**
 * Thrown when a destructive command is run without the flag that confirms it.
 *
 * <p>Nothing has happened yet when a caller meets this.
 */
final class ConfirmationRequiredException extends IllegalStateException {

    /**
     * Creates the exception.
     *
     * @param said {@link String} what would be lost, and that {@code --yes} repeats the command
     */
    ConfirmationRequiredException(final String said) {
        super(said);
    }
}
