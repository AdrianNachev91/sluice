package photos.sluice.application.port.out;

import java.nio.file.Path;
import java.util.List;

public interface MediaStore {

    // Every regular file under root, recursively, as absolute paths. Order is unspecified.
    List<Path> listFiles(Path root);

    Path move(Path source, Path destDir);

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
