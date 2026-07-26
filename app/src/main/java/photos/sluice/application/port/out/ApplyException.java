package photos.sluice.application.port.out;

// Thrown by ApplyEngine.apply() when the prep directory's shards do not validate cleanly from
// scratch: a missing shard, an off-contract decision, or a decision whose file is neither on disk
// nor already recorded as applied. The message carries every problem found, aggregated, so a bad run
// is fixed in one pass instead of one error per re-run. Checked, because this is an expected,
// recoverable outcome: fix the shards (or re-run the culler) and apply again. Zero files are ever
// moved when this is thrown.
public class ApplyException extends Exception {

    /**
     * Creates the exception with an aggregated problem message.
     *
     * @param message {@link String} the aggregated validation problems
     */
    public ApplyException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an aggregated problem message and an underlying cause.
     *
     * @param message {@link String} the aggregated validation problems
     * @param cause {@link Throwable} the underlying cause
     */
    public ApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
