package photos.sluice.application.port.out;

import photos.sluice.domain.job.CancellationSignal;

import java.nio.file.Path;

/**
 * Thrown by a transfer that was given up on part-way through, at the caller's own request.
 *
 * <p>Not a failure. The user asked to stop and then asked again rather than wait out the file in
 * flight, so this reports that the second ask was honoured. The source is untouched, and the
 * destination is not left holding a prefix of it under its own name.
 *
 * <p>Only reachable through a transfer handed a signal that answers true to
 * {@link CancellationSignal#isAbandonRequested()}. An engine passing no signal cannot see it.
 *
 * <p>An {@link IllegalStateException} subtype so a catch clause is a choice rather than an
 * obligation.
 */
public final class TransferAbandonedException extends IllegalStateException {

    private final transient Path source;

    /**
     * Creates the exception, naming the file that was being transferred.
     *
     * @param source {@link Path} the file the abandoned transfer was reading
     */
    public TransferAbandonedException(final Path source) {
        super("Stopped part-way through " + source);
        this.source = source;
    }

    /**
     * Returns the file the abandoned transfer was reading.
     *
     * @return {@link Path} the source of the abandoned transfer
     */
    public Path source() {
        return this.source;
    }
}
