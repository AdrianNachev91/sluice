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
 * {@link java.security.DigestInputStream} in fixed-size chunks, so a multi-gigabyte video costs no
 * more memory than a small photo. The result is an uppercase hex string.
 */
@Component
public class Sha256Hasher implements Sha256Port {

    // Arbitrary but conventional I/O chunk size: large enough to amortize the per-read call
    // overhead, small enough to keep memory flat whatever the file's size.
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
    public String hash(final Path file) {
        final MessageDigest digest = newSha256Digest();
        // DigestInputStream feeds every byte it reads into the digest as a side effect, so the read
        // loop below exists only to drive that.
        try (final InputStream in = Files.newInputStream(file);
             final var digestIn = new DigestInputStream(in, digest)) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            //noinspection StatementWithEmptyBody -- reading drives the digest; the bytes themselves are discarded
            while (digestIn.read(buffer) != -1) {}
        } catch (final IOException e) {
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
            // SHA-256 is on the JDK's mandatory standard algorithm list, so a conforming JVM
            // always has it. Converting the checked exception signals a broken JVM rather than
            // anything a caller could handle.
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Hex-encodes the digest, two characters per byte.
     *
     * @param bytes byte[] the raw digest bytes
     * @return {@link String} the uppercase hex encoding of the bytes
     */
    private static String toUpperHex(final byte[] bytes) {
        final char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int b = bytes[i] & 0xFF;
            hex[i * 2] = HEX_DIGITS[b >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(hex);
    }
}
