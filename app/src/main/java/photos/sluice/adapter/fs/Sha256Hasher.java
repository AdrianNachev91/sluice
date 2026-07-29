package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.Sha256Port;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A {@link Sha256Port} that computes a file's SHA-256 hash by streaming its bytes through a
 * {@link java.security.DigestInputStream} in fixed-size chunks. This keeps memory use constant
 * regardless of file size, so hashing a multi-gigabyte video costs no more memory than a small
 * photo. The result is returned as an uppercase hex string, matching the casing already used by
 * the on-disk hash index.
 */
@Component
public class Sha256Hasher implements Sha256Port {

    // Arbitrary but conventional I/O chunk size: large enough to amortize the per-read call
    // overhead, small enough to keep memory flat regardless of file size (a multi-GB video hashes
    // in constant memory, not proportional to its size).
    private static final int BUFFER_SIZE = 8192;
    // Uppercase to match the hex casing already used by the on-disk hash index, since lookups
    // there are exact string comparisons.
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /**
     * Computes the SHA-256 hash of a file's contents.
     *
     * @param file {@link Path} the file to hash
     * @return {@link String} the uppercase hex-encoded SHA-256 hash
     */
    @Override
    public String hash(Path file) {
        MessageDigest digest = newSha256Digest();
        // DigestInputStream wraps the file stream and feeds every byte it reads into the digest
        // as a side effect, so the digest is computed incrementally over the stream rather than
        // requiring the whole file in memory at once. The read loop exists only to drive that
        // side effect - the returned bytes themselves are discarded.
        try (InputStream in = Files.newInputStream(file);
             var digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            //noinspection StatementWithEmptyBody -- reading drives the digest; the bytes themselves are discarded
            while (digestIn.read(buffer) != -1) {}
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file, e);
        }
        return toUpperHex(digest.digest());
    }

    /**
     * Creates a new SHA-256 message digest instance.
     *
     * @return {@link MessageDigest} a fresh SHA-256 digest
     */
    private static MessageDigest newSha256Digest() {
        try {
            // SHA-256 is on the JDK's mandatory standard algorithm list, so every conforming JVM
            // supports it; this checked exception exists only to satisfy the general-purpose
            // MessageDigest API and cannot fire in practice. Converting it to unchecked signals a
            // broken JVM, not a normal, callable-handleable error.
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Each byte maps to two hex characters by splitting it into its high and low nibble (4-bit
     * half): {@code >>> 4} isolates the high nibble, {@code & 0x0F} masks off everything but the
     * low nibble.
     *
     * @param bytes byte[] the raw digest bytes
     * @return {@link String} the uppercase hex encoding of the bytes
     */
    private static String toUpperHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2] = HEX_DIGITS[b >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(hex);
    }
}
