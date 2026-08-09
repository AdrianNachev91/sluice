package photos.sluice.domain.paths;

import photos.sluice.domain.paths.PathViolation.Overlap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule that keeps the three folder roots out of each other. Every committed file proves its own
 * liveness by its presence in the hash index. If the library sits inside the inbox, or the other way
 * round, a fresh sort run sees the library copy. It then marks the inbox original redundant and
 * deletes the only copy.
 *
 * <p>The rules are asymmetric because the documented layout puts the inbox inside the working root.
 * The Sorted, Review, Duplicates, and Unreviewable directories are themselves derived from the
 * working root. A blanket "all three roots must be disjoint" check would reject that canonical
 * setup:
 *
 * <ul>
 *   <li>The library and the inbox must not contain each other, in either direction.
 *   <li>The inbox must not be the working root, or an ancestor of it. The working root may still be
 *       an ancestor of the inbox, since that's the documented layout.
 * </ul>
 *
 * <p>This is path arithmetic and nothing else. Every argument must already be a real, existing
 * directory, resolved through whatever the filesystem does with symlinks and junctions. That is what
 * catches two configured paths naming one directory under different names.
 */
public final class RootLayout {

    /**
     * Prevents instantiation of this static utility class.
     */
    private RootLayout() {
    }

    /**
     * Checks three real, existing roots against the rules above.
     *
     * @param repoRoot {@link Path} the real working root
     * @param libraryRoot {@link Path} the real library root
     * @param inbox {@link Path} the real inbox root
     * @return a {@link List} of {@link PathViolation}, empty when the layout is legal
     */
    public static List<PathViolation> violations(final Path repoRoot, final Path libraryRoot, final Path inbox) {
        final List<PathViolation> violations = new ArrayList<>();
        if (contains(libraryRoot, inbox) || contains(inbox, libraryRoot)) {
            violations.add(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
        }
        if (contains(inbox, repoRoot)) {
            violations.add(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX));
        }
        return List.copyOf(violations);
    }

    /**
     * Whether the descendant path starts with the ancestor path. True when the two are equal.
     *
     * @param ancestor {@link Path} the candidate ancestor
     * @param descendant {@link Path} the candidate descendant
     * @return boolean true if descendant is ancestor, or lies under it
     */
    private static boolean contains(final Path ancestor, final Path descendant) {
        return descendant.startsWith(ancestor);
    }
}
