package photos.sluice.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Compares the output of two independent sort-engine runs against identical starting inputs,
// purely by relative path. No adapter dependency - this is plain java.nio.file tree walking, so
// it needs no port and can run in a plain unit test.
//
// Two distinct comparisons share the same underlying set-diff. A destination-tree diff walks two
// roots (e.g. each run's Sorted+Review) and compares the relative paths found. A delete-set diff
// compares "what got removed" from each run's own before/after Inbox snapshot instead, since a
// deleted file leaves no trace to walk and the caller has to derive that set itself.
public final class MoveDiffer {

    public record Diff(Set<String> onlyInA, Set<String> onlyInB) {
        public boolean identical() {
            return onlyInA.isEmpty() && onlyInB.isEmpty();
        }
    }

    // Relative file paths under root, forward-slash normalized so the diff reads the same
    // regardless of which OS produced the path string. A missing root (e.g. a run that never
    // created its Review folder) is treated as an empty tree, not an error.
    public Set<String> relativeFilePaths(Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Diff diffTrees(Path rootA, Path rootB) {
        return diff(relativeFilePaths(rootA), relativeFilePaths(rootB));
    }

    public Diff diff(Set<String> a, Set<String> b) {
        Set<String> onlyInA = new HashSet<>(a);
        onlyInA.removeAll(b);
        Set<String> onlyInB = new HashSet<>(b);
        onlyInB.removeAll(a);
        return new Diff(onlyInA, onlyInB);
    }
}
