package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Thrown when a working root cannot be claimed because another process already holds it.
 *
 * <p>The message is written to be shown to a person, and deliberately names neither the folder nor
 * the claim. Both are machinery nobody chose to run, and a second window is something a person
 * understands without either. The root travels on the exception instead, so a caller that does need
 * to name it has it without parsing anything.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the claim was
 * refused needs no knowledge of this type at all.
 */
public final class WorkingRootBusyException extends IllegalStateException {

    private final transient Path workingRoot;

    /**
     * Creates the exception, naming the root that is already held.
     *
     * @param workingRoot {@link Path} the working root another process holds
     */
    public WorkingRootBusyException(final Path workingRoot) {
        this(workingRoot, null);
    }

    /**
     * Creates the exception with the failure that revealed the root was held.
     *
     * @param workingRoot {@link Path} the working root another process holds
     * @param cause {@link Throwable} the underlying failure, or null when the claim was simply refused
     */
    public WorkingRootBusyException(final Path workingRoot, final @Nullable Throwable cause) {
        super("Another Sluice process is already running: close it and try again.", cause);
        this.workingRoot = workingRoot;
    }

    /**
     * Returns the working root another process holds.
     *
     * @return {@link Path} the held working root
     */
    public Path workingRoot() {
        return this.workingRoot;
    }
}
