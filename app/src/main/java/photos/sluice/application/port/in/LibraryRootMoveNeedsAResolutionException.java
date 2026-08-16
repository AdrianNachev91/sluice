package photos.sluice.application.port.in;

/**
 * Thrown when an ordinary save would move the library root.
 *
 * <p>Moving it leaves the hash index describing the old library, and there is no answer to that a
 * caller can be assumed to want. So the seam refuses rather than picking one, and
 * {@link LibraryRootUseCase} is where a caller states which. Enforced here rather than in a dialog,
 * because a second caller would otherwise inherit none of it.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class LibraryRootMoveNeedsAResolutionException extends IllegalStateException {

    /**
     * Creates the exception.
     *
     * @param message {@link String} what was refused, and what to do about it
     */
    public LibraryRootMoveNeedsAResolutionException(final String message) {
        super(message);
    }
}
