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

@Component
public class Sha256Hasher implements Sha256Port {

    // Arbitrary but conventional I/O chunk size: large enough to amortize the per-read call
    // overhead, small enough to keep memory flat regardless of file size (a multi-GB video hashes
    // in constant memory, not proportional to its size).
    private static final int BUFFER_SIZE = 8192;
    // Uppercase to match the hex casing already used by the on-disk hash index, since lookups
    // there are exact string comparisons.
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    @Override
    public String hash(Path file) {
        MessageDigest digest = newSha256Digest();
        // DigestInputStream wraps the file stream and feeds every byte it reads into the digest
        // as a side effect, so the digest is computed incrementally over the stream rather than
        // requiring the whole file in memory at once. The read loop exists only to drive that
        // side effect - the returned bytes themselves are discarded.
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            //noinspection StatementWithEmptyBody -- reading drives the digest; the bytes themselves are discarded
            while (digestIn.read(buffer) != -1) {}
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file, e);
        }
        return toUpperHex(digest.digest());
    }

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

    // Each byte maps to two hex characters by splitting it into its high and low nibble (4-bit
    // half): >>> 4 isolates the high nibble, & 0x0F masks off everything but the low nibble.
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
