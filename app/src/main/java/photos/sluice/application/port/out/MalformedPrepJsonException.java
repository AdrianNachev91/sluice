package photos.sluice.application.port.out;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thrown by every {@link CullPrepPort} read - {@link CullPrepPort#readIndex},
 * {@link CullPrepPort#readSidecar}, {@link CullPrepPort#readShard} and
 * {@link CullPrepPort#readShardFile} - when the artifact cannot be trusted, permanently. That
 * covers absent entirely, unparseable JSON, a null document, and a required field or list a shape
 * guarantees is never empty or null.
 *
 * <p>A subtype of {@link UncheckedIOException}, so any catch of the wider type also catches this
 * one. Four callers catch it specifically:
 * {@link photos.sluice.application.service.PrepDirDoctor},
 * {@link photos.sluice.application.service.ApplyPlanner},
 * {@link photos.sluice.application.service.PrepDirRemedies}, and {@code Sidecars}. That way a
 * read that merely failed is never mistaken for damaged content, and so never triggers a repair
 * aimed at content that was fine. A file held open by a backup or antivirus process, a permission
 * denial, and a cloud placeholder that never hydrated are three such causes. All three leave the
 * underlying content intact. Only this exception means the content itself cannot be trusted.
 *
 * <p>Where a merely-failed read then goes is the catching caller's own call. {@link
 * photos.sluice.application.service.PrepDirDoctor} reports it as DAMAGED rather than letting it
 * propagate, because a diagnosis that throws would take down the caller reading every other run.
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
