package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// Flowchart + scenario table: app/docs/design/adapter/fs/media-store.md.
@Component
public class NioMediaStore implements MediaStore {

    @Override
    public List<Path> listFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    @Override
    public Instant lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read last modified time of " + path, e);
        }
    }

    @Override
    public Path move(Path source, Path destDir) {
        return moveTo(source, resolveDestination(source, destDir));
    }

    @Override
    public Path resolveDestination(Path source, Path destDir) {
        return resolveCollision(destDir, source.getFileName().toString());
    }

    @Override
    public Path moveTo(Path source, Path destination) {
        ensureDirectory(destination.getParent());
        try {
            Files.move(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move " + source + " to " + destination, e);
        }
        return destination;
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

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read size of " + path, e);
        }
    }

    @Override
    public void appendLine(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append line to " + file, e);
        }
    }

    @Override
    public void write(Path file, String content) {
        try {
            Files.writeString(file, content + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    @Override
    public List<String> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read lines from " + file, e);
        }
    }

    @Override
    public void removeEmptyDirectories(Path root) {
        List<Path> directories;
        try (Stream<Path> walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory).filter(dir -> !dir.equals(root)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
        // Deepest directories first. A chain of nested empty directories then collapses bottom-up
        // in this single pass. By the time a shallower directory is checked, any empty child it
        // had has already been removed, leaving it genuinely empty too if nothing else remains.
        directories.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .forEach(this::deleteIfEmptyOfFiles);
    }

    @Override
    public void removeIfEmptyOfFiles(Path dir) {
        if (!Files.exists(dir) || containsAnyFile(dir)) {
            return;
        }
        // Every subdirectory below dir is now known empty of files too (containsAnyFile already
        // checked the whole subtree), so this prunes all of them bottom-up, leaving dir itself
        // with no children - at which point it is safe to remove too.
        removeEmptyDirectories(dir);
        deleteIfEmptyOfFiles(dir);
    }

    private void deleteIfEmptyOfFiles(Path dir) {
        if (!Files.exists(dir) || containsAnyFile(dir)) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove empty directory " + dir, e);
        }
    }

    private static boolean containsAnyFile(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect " + dir, e);
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
