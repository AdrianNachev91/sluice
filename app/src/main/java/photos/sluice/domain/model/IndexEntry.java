package photos.sluice.domain.model;

import java.nio.file.Path;

/**
 * One row of the library hash index: a file's SHA-256 content hash paired with the library path it
 * was committed to. A later sort run recognizes a redundant re-import by hash alone, and deletes
 * the Inbox copy on the strength of it.
 *
 * <p>So a row asserts that those bytes are in the library. A path anywhere else would make that
 * sort delete an original whose only surviving copy is the one the row names.
 */
public record IndexEntry(String sha256, Path path) {
}
