package photos.sluice.application.port.out;

import java.nio.file.Path;

public interface MediaStore {

    Path move(Path source, Path destDir);

    Path copy(Path source, Path destDir);

    void delete(Path path);

    void ensureDirectory(Path dir);

    boolean exists(Path path);

    long size(Path path);

    void appendLine(Path file, String line);

    // Removes every subdirectory under root left empty of all files (root itself is never a
    // candidate). A directory holding a genuine non-media leftover is left in place.
    void removeEmptyDirectories(Path root);
}
