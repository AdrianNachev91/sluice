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

    private static final int BUFFER_SIZE = 8192;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    @Override
    public String hash(Path file) {
        MessageDigest digest = newSha256Digest();
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
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

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
