package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Flowchart + scenario table: app/docs/design/adapter/fs/media-store.md.
@Component
public class NioMediaStore implements MediaStore {

    @Override
    public Path move(Path source, Path destDir) {
        Path dest = prepareDestination(source, destDir);
        try {
            Files.move(source, dest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move " + source + " to " + dest, e);
        }
        return dest;
    }

    @Override
    public Path copy(Path source, Path destDir) {
        Path dest = prepareDestination(source, destDir);
        try {
            // Unlike move (a rename, where attributes ride along for free), a plain copy is not
            // required to preserve timestamps - and the date-resolution fallback chain relies on
            // mtime, so a copied file must keep its original one.
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy " + source + " to " + dest, e);
        }
        return dest;
    }

    @Override
    public void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    @Override
    public void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory " + dir, e);
        }
    }

    private Path prepareDestination(Path source, Path destDir) {
        ensureDirectory(destDir);
        return resolveCollision(destDir, source.getFileName().toString());
    }

    // First try the original leaf name, then append " (2)", " (3)", ... before the extension
    // until a free path is found. Never overwrites an existing file.
    private static Path resolveCollision(Path destDir, String leaf) {
        Path candidate = destDir.resolve(leaf);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = baseName(leaf);
        String extension = extension(leaf);
        int n = 2;
        do {
            candidate = destDir.resolve(base + " (" + n + ")" + extension);
            n++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static String baseName(String leaf) {
        int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? leaf : leaf.substring(0, dot);
    }

    private static String extension(String leaf) {
        int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? "" : leaf.substring(dot);
    }
}
