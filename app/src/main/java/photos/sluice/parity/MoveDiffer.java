package photos.sluice.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compares the output of two independent sort-engine runs against identical starting inputs,
 * purely by relative path. It has no adapter dependency. This is plain {@code java.nio.file} tree
 * walking, so it needs no port and can run in a plain unit test.
 *
 * <p>Two distinct comparisons share the same underlying set-diff. A destination-tree diff walks
 * two roots (e.g. each run's Sorted and Review folders) and compares the relative paths found. A
 * delete-set diff compares "what got removed" from each run's own before/after Inbox snapshot
 * instead. A deleted file leaves no trace to walk, so the caller has to derive that set itself.
 */
public final class MoveDiffer {

    /**
     * The result of comparing two sets of relative paths: the paths found only on each side.
     */
    public record Diff(Set<String> onlyInA, Set<String> onlyInB) {
        /**
         * Checks whether both sides of the diff are empty.
         *
         * @return boolean true if there are no differences on either side
         */
        public boolean identical() {
            return onlyInA.isEmpty() && onlyInB.isEmpty();
        }
    }

    /**
     * Relative file paths under root, forward-slash normalized so the diff reads the same
     * regardless of which OS produced the path string. A missing root (e.g. a run that never
     * created its Review folder) is treated as an empty tree, not an error.
     *
     * @param root {@link Path} the directory tree to walk
     * @return a {@link Set} of {@link String} forward-slash-normalized relative paths of all regular files under root
     */
    public Set<String> relativeFilePaths(final Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Diffs the relative file paths found under two directory trees.
     *
     * @param rootA {@link Path} the first directory tree
     * @param rootB {@link Path} the second directory tree
     * @return {@link Diff} the set-diff between the two trees
     */
    public Diff diffTrees(final Path rootA, final Path rootB) {
        return diff(relativeFilePaths(rootA), relativeFilePaths(rootB));
    }

    /**
     * Computes the set-diff between two sets of relative paths.
     *
     * @param a a {@link Set} of {@link String} the first set of paths
     * @param b a {@link Set} of {@link String} the second set of paths
     * @return {@link Diff} paths only in a and paths only in b
     */
    public Diff diff(final Set<String> a, final Set<String> b) {
        final Set<String> onlyInA = new HashSet<>(a);
        onlyInA.removeAll(b);
        final Set<String> onlyInB = new HashSet<>(b);
        onlyInB.removeAll(a);
        return new Diff(onlyInA, onlyInB);
    }
}
