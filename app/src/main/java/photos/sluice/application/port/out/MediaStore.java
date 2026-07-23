package photos.sluice.application.port.out;

import java.nio.file.Path;
import java.util.List;

public interface MediaStore {

    // Every regular file under root, recursively, as absolute paths. Order is unspecified.
    List<Path> listFiles(Path root);

    Path move(Path source, Path destDir);

    // The exact free path move(source, destDir) would land on, without performing the move - the
    // first name not already occupied under destDir (source's own leaf, then " (2)", " (3)", ...).
    // Exists so a caller can durably record where a decision is headed BEFORE moving it, closing the
    // crash window between "decided the destination" and "the move actually happened."
    Path resolveDestination(Path source, Path destDir);

    // Moves source to exactly destination - no collision handling of its own, since the caller is
    // expected to have already reserved that exact path via resolveDestination.
    Path moveTo(Path source, Path destination);

    Path copy(Path source, Path destDir);

    void delete(Path path);

    void ensureDirectory(Path dir);

    boolean exists(Path path);

    long size(Path path);

    void appendLine(Path file, String line);

    // Creates file with exactly this content, replacing whatever was there before - unlike
    // appendLine, which accumulates. For a file meant to hold a single, self-contained record
    // (rewritten wholesale whenever it changes) rather than a growing log of entries.
    void write(Path file, String content);

    // Every line of file, in order, or empty if file does not exist - the read-side counterpart to
    // appendLine, for resuming from a crash-safety log written one line per completed step.
    List<String> readLines(Path file);

    // Removes every subdirectory under root left empty of all files (root itself is never a
    // candidate). A directory holding a genuine non-media leftover is left in place.
    void removeEmptyDirectories(Path root);

    // Removes dir itself, and everything under it, provided dir contains no file anywhere in its
    // subtree. A no-op (dir is left untouched, including any empty subdirectories) if even one
    // file remains anywhere below it.
    void removeIfEmptyOfFiles(Path dir);
}
