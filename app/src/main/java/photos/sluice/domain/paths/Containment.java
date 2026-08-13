package photos.sluice.domain.paths;

import java.nio.file.Path;

/**
 * Whether one path lies inside another, for the refusals that bound what a cull may take files from
 * and where it may put them. Other containment questions in this codebase set their own terms, so
 * this is not the one definition of "inside" that everything asks.
 *
 * <p>Strict, so a path equal to the root is not inside it. A root is a folder holding other things,
 * never a file anything may act on directly.
 *
 * <p>Both paths are made absolute and normalized first. That covers the two shapes a path read off
 * disk can take. A parent reference walks out of the root. A relative path names nothing at all
 * until it is anchored somewhere, and anchoring it is what lets the comparison mean something.
 *
 * <p>Symlinks are deliberately not resolved. Doing so costs a filesystem call for every path
 * checked. It also fails outright on a file that is already gone, which is the ordinary state of a
 * source an earlier run moved. Two residuals follow, and neither is reachable without write access
 * to the root itself. A symlinked file is moved as the link rather than its target, which is
 * harmless. A symlinked directory followed by a parent reference is not. This compares paths as
 * text, where {@code a/link/../b} reduces to {@code a/b}. A POSIX kernel follows the link first and
 * lands beside the target instead.
 */
public final class Containment {

    /**
     * Prevents instantiation of this static utility class.
     */
    private Containment() {
    }

    /**
     * Whether candidate resolves to a path strictly inside root.
     *
     * @param root {@link Path} the root the candidate must sit inside
     * @param candidate {@link Path} the path to check
     * @return boolean true when candidate lies under root, and is not root itself
     */
    public static boolean strictlyUnder(final Path root, final Path candidate) {
        final Path normalRoot = root.toAbsolutePath().normalize();
        final Path normalized = candidate.toAbsolutePath().normalize();
        return normalized.startsWith(normalRoot) && !normalized.equals(normalRoot);
    }
}
