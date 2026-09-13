package photos.sluice.application.port.out;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thrown by every {@link SiftPrepPort} read when the artifact cannot be trusted, permanently. That
 * covers absent entirely, unparseable JSON, a null document, and a required field or list a shape
 * guarantees is never empty or null.
 *
 * <p>A subtype of {@link UncheckedIOException}, so any catch of the wider type also catches this
 * one. Catching this one specifically is how a read that merely failed is told apart from damaged
 * content, and so never triggers a repair aimed at content that was fine. A file held open by a
 * backup or antivirus process, a permission denial, and a cloud placeholder that never hydrated are
 * three such causes. All three leave the underlying content intact. Only this exception means the
 * content itself cannot be trusted.
 *
 * <p>Where a merely-failed read then goes is the catching caller's own call.
 */
public final class MalformedPrepJsonException extends UncheckedIOException {

    /**
     * Creates the exception, wrapping cause in an {@link IOException} if it isn't already one.
     * Most callers pass a {@code JacksonException}, which is unchecked and not itself an
     * {@link IOException}.
     *
     * @param message {@link String} what was found malformed, and where
     * @param cause {@link Throwable} the underlying parse or validation failure
     */
    public MalformedPrepJsonException(final String message, final Throwable cause) {
        super(message, cause instanceof final IOException io ? io : new IOException(cause));
    }
}
