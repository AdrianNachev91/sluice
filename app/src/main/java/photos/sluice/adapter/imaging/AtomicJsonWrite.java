package photos.sluice.adapter.imaging;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a JSON document so the destination name never holds a half-written file.
 *
 * <p>Writing straight to the destination leaves a truncated file behind when the process dies
 * mid-write, or when the disk fills. What that costs depends on the artifact, and it is never
 * nothing. A rebuild, a repair the user has to answer, or a montage re-culled at the model's price.
 * The bytes go to a temporary file in the same directory instead, and an atomic rename publishes
 * them. A plain move is allowed by its own contract to fall back on a copy, and a copy interrupted
 * partway leaves the destination holding exactly the half-written file this avoids.
 *
 * <p>The temporary name is unique per call rather than derived from the destination. One prep
 * directory holds an index, a sidecar per montage and a shard per montage, so a shared fixed name
 * would collide between them.
 *
 * <p>A write that fails partway leaves nothing behind: the temporary file holds no document worth
 * keeping, so it is deleted. A rename that fails is the opposite case and the temporary file
 * survives. It holds the complete document, and on the shard path that document is what a billed
 * model call just produced. Deleting it to keep the directory tidy would throw away the only copy.
 * A process killed between the two leaves a temporary file no cleanup could have run for. The next
 * prep of that scope clears the directory wholesale.
 *
 * <p>{@code adapter.vision} carries its own copy of this class. The two adapter subpackages may not
 * depend on each other, the same constraint that keeps their {@code index.json} DTOs separate.
 */
final class AtomicJsonWrite {

    private static final String TEMP_SUFFIX = ".tmp";

    private AtomicJsonWrite() {
    }

    /**
     * Serializes document to target through a temporary file in target's own directory.
     *
     * <p>{@link Path#getParent()} answers null for a bare filename, which is why the absolute form
     * is taken first.
     *
     * @param target {@link Path} the destination file
     * @param mapper {@link JsonMapper} the mapper to serialize with
     * @param document {@link Object} the document to serialize
     * @throws IOException if the temporary file, the write, or the rename fails
     */
    @SuppressWarnings("DuplicatedCode")
    static void write(final Path target, final JsonMapper mapper, final Object document) throws IOException {
        final Path directory = target.toAbsolutePath().getParent();
        final Path temporary = Files.createTempFile(directory, target.getFileName().toString(), TEMP_SUFFIX);
        boolean written = false;
        try {
            try (final var output = Files.newOutputStream(temporary)) {
                mapper.writeValue(output, document);
            }
            written = true;
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (!written) {
                discard(temporary);
            }
        }
    }

    /**
     * Removes the temporary file of a write that produced no document.
     *
     * @param temporary {@link Path} the temporary file to remove
     */
    private static void discard(final Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (final IOException ignored) {
            // The caller is already reporting why the write failed, which is the useful outcome.
        }
    }
}
