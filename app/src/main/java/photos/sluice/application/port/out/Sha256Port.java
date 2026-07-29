package photos.sluice.application.port.out;

import java.nio.file.Path;

/**
 * The effect boundary application services use to compute a file's SHA-256 hash, the identity the
 * hash index and duplicate detection are built on.
 */
public interface Sha256Port {

    /**
     * Computes a file's SHA-256 hash.
     *
     * @param file {@link Path} the file to hash
     * @return {@link String} the hex-encoded SHA-256 hash
     */
    String hash(Path file);
}
