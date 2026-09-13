package photos.sluice.application.port.in;

import java.util.List;

/**
 * Thrown when the library root cannot move because sift runs on disk have not finished.
 *
 * <p>An unfinished run holds move records naming destinations under the library root that was in
 * force when they were written. Resume one after a move and its records verify against a folder the
 * settings do not name. The run then stalls behind a refusal with no remedy on any screen. Refusing
 * the move is what keeps a user from walking into that state.
 *
 * <p>A prep dir that cannot be read is still counted, deliberately, so a run nobody can inspect
 * still blocks a move. The sift-prep root failing to list refuses too, as
 * {@link RunsUnreadableException}, since neither unknown may read as nothing there.
 *
 * <p>A completed run's records are inert, since nothing resumes one. A damaged run counts as
 * unfinished: nobody has established what it holds, and its way out is the same as any other's.
 *
 * <p>Carries the scopes rather than only a sentence, so a screen can list them beside the two ways
 * out, applying them or discarding them.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class UnfinishedRunsException extends IllegalStateException {

    private final List<String> scopes;

    /**
     * Creates the exception.
     *
     * @param message {@link String} what was refused, and what to do about it
     * @param scopes a {@link List} of {@link String} the scopes of the runs that have not finished
     */
    public UnfinishedRunsException(final String message, final List<String> scopes) {
        super(message);
        this.scopes = List.copyOf(scopes);
    }

    /**
     * The scopes of the runs standing in the way.
     *
     * @return a {@link List} of {@link String} the unfinished runs' scopes
     */
    public List<String> scopes() {
        return this.scopes;
    }
}
