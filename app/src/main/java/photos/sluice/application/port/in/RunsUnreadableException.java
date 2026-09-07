package photos.sluice.application.port.in;

import java.nio.file.Path;

/**
 * Thrown when work is refused because the sift-prep root itself could not be read, so what sits
 * under it is unknown.
 *
 * <p>Apart from {@link UnfinishedRunsException} because nothing was diagnosed. That one names the
 * runs standing in the way and offers applying or discarding them. Here there is no list at all,
 * since an empty read establishes nothing about what is under the root.
 *
 * <p>Carries the root rather than a count, since the root is the one thing a reader can go and
 * look at.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class RunsUnreadableException extends IllegalStateException {

    private final transient Path root;

    /**
     * Creates the exception, naming the root that could not be read.
     *
     * @param root {@link Path} the sift-prep root whose contents are unknown
     */
    public RunsUnreadableException(final Path root) {
        super("Cannot determine whether any sift is still unfinished: " + root
                + " cannot be read. Most likely the folder is held by another process or not there "
                + "anymore.");
        this.root = root;
    }

    /**
     * The folder that could not be read.
     *
     * @return {@link Path} the sift-prep root
     */
    public Path root() {
        return this.root;
    }
}
