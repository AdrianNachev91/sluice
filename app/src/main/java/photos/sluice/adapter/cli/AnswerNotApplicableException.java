package photos.sluice.adapter.cli;

/**
 * Thrown when the key and option named answer no open finding on the run.
 *
 * <p>Nothing has been written when a caller meets this. The run stands exactly as it was.
 */
final class AnswerNotApplicableException extends IllegalStateException {

    /**
     * Creates the exception.
     *
     * @param said {@link String} what was named and why it matched nothing open
     */
    AnswerNotApplicableException(final String said) {
        super(said);
    }
}
