package photos.sluice.domain.model;

import java.nio.file.Path;

/**
 * One row of the library hash index: a file's SHA-256 content hash paired with the library path
 * it was committed to. The commit, apply, and rescue use cases each append an entry here whenever
 * they move a file into the library. A later sort run then recognizes a redundant re-import by
 * hash alone.
 */
public record IndexEntry(String sha256, Path path) {
}
