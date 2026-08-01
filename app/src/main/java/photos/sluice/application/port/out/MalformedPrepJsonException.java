package photos.sluice.application.port.out;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thrown by {@link CullPrepPort#readIndex} and {@link CullPrepPort#readSidecar} when the artifact
 * cannot be trusted, permanently. That covers absent entirely, unparseable JSON, a null document,
 * and a required field or list a shape guarantees is never empty or null.
 *
 * <p>A subtype of {@link UncheckedIOException}. Every existing catch of the wider type still
 * catches this one unchanged. {@link photos.sluice.application.service.PrepDirDoctor} catches it
 * specifically. That way a read that merely failed propagates instead of being diagnosed as
 * corrupt. A file held open by a backup or antivirus process, a permission denial, and a cloud
 * placeholder that never hydrated are three such causes. All three leave the underlying content
 * intact. Only this exception means the content itself cannot be trusted.
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
