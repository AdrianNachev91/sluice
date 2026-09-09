package photos.sluice.application.port.in;

import java.nio.file.Path;

/**
 * Thrown when an ordinary save would move the library root.
 *
 * <p>Moving it leaves the hash index describing the old library, and there is no answer to that a
 * caller can be assumed to want. So the seam refuses rather than picking one, and
 * {@link LibraryRootUseCase} is where a caller states which. Enforced here rather than in a dialog,
 * because a second caller would otherwise inherit none of it.
 *
 * <p>Carries the root being moved away from, so a surface can say which folder it is asking about.
 * The message is for a log: a caller wording something for a person has the path and its own
 * vocabulary, and neither is this class's to choose.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class LibraryRootResolutionRequiredException extends IllegalStateException {

    private final transient Path previousLibraryRoot;

    /**
     * Creates the exception.
     *
     * @param previousLibraryRoot {@link Path} the library root the save would move away from
     * @param message {@link String} what was refused, for a log
     */
    public LibraryRootResolutionRequiredException(final Path previousLibraryRoot, final String message) {
        super(message);
        this.previousLibraryRoot = previousLibraryRoot;
    }

    /**
     * The library root this save would move away from.
     *
     * @return {@link Path} the root in force before the refused save
     */
    public Path previousLibraryRoot() {
        return this.previousLibraryRoot;
    }
}
