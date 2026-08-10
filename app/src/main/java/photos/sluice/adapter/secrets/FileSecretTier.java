package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Holds a credential in a permission-restricted file under the app-data directory, the tier that
 * answers where the machine offers no credential store.
 *
 * <p>Restricted rather than encrypted, and the difference is not a shortcut. Encrypting needs
 * somewhere to keep the encryption key, and the absence of exactly such a place is why this tier is
 * being used at all. A key kept beside the thing it encrypts protects nobody.
 *
 * <p>One file per credential rather than one file holding several. There is then no format to
 * parse, no partial-document failure mode, and clearing a credential is a delete.
 *
 * <p>What a read answers is the file's content stripped of surrounding whitespace, and nothing at
 * all when only whitespace is stored. A file holding only whitespace means the same thing as a
 * variable someone cleared in a shell.
 *
 * <p>The write creates a uniquely named temporary file, restricts it while it is still empty, fills
 * it, and moves it into place. The credential is therefore never on disk at a name anything else can
 * read. A process dying mid-write also leaves the previous file rather than a truncated one, which
 * would read back as a wrong credential. Nothing here is flushed to the device, so a machine losing
 * power is a weaker case than that.
 *
 * <p>A filesystem that cannot restrict a file to its owner fails the write instead of storing the
 * credential anyway. {@link SecretFilePermissions} carries the reasoning for that refusal.
 */
class FileSecretTier implements WritableSecretTier {

    static final int PRECEDENCE = 0;

    private static final String FILE_SUFFIX = ".key";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path directory;

    /**
     * Creates the tier over the directory its credential files live in.
     *
     * @param directory {@link Path} the directory holding one file per credential
     */
    FileSecretTier(final Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<String> read(final SecretId id) {
        final Path file = this.fileFor(id);
        final String stored;
        // The read itself answers whether the credential is there. An existence check ahead of it
        // cannot signal a failure, so it reports a present but unreadable file as absent. The user
        // is then told to add a key that is already stored.
        try {
            stored = this.readFile(file);
        } catch (final NoSuchFileException _) {
            return Optional.empty();
        } catch (final IOException e) {
            throw new SecretStoreException("Reading the credential for provider '" + id.provider()
                    + "' from " + file + " failed", e);
        }
        // Stripped on the way out. Nothing stops a user creating this file by hand, and the ordinary
        // ways of doing that append a newline. That newline would reach the provider as part of the
        // key.
        final String credential = stored.strip();
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(credential);
    }

    @Override
    public boolean holds(final SecretId id) {
        return this.read(id).isPresent();
    }

    @Override
    public SecretStatus statusWhenAnswering(final SecretId id) {
        return new SecretStatus.InFile();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public int precedence() {
        return PRECEDENCE;
    }

    @Override
    public void write(final SecretId id, final String secret) {
        final Path file = this.fileFor(id);
        Path temporary = null;
        try {
            Files.createDirectories(this.directory);
            temporary = Files.createTempFile(this.directory, id.provider(), TEMP_SUFFIX);
            // Restricting before the credential goes in, rather than after. A file written first is
            // readable by anyone until the restriction lands. Where the restriction is refused, it
            // never lands at all.
            final boolean restricted = this.restrict(temporary);
            if (!restricted) {
                throw new SecretStoreException("The filesystem at " + this.directory
                        + " cannot restrict a file to its owner, so the credential for provider '"
                        + id.provider() + "' was not stored");
            }
            this.writeFile(temporary, secret);
            // An atomic rename replaces whatever the name already held. A plain move is allowed by
            // its own contract to copy, which can leave a partial credential at the final name.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new SecretStoreException(
                    "Could not store the credential for provider '" + id.provider() + "' at " + file, e);
        } finally {
            discard(temporary);
        }
    }

    @Override
    public void erase(final SecretId id) {
        final Path file = this.fileFor(id);
        try {
            this.deleteFile(file);
        } catch (final IOException e) {
            throw new SecretStoreException(
                    "Could not clear the credential for provider '" + id.provider() + "' at " + file, e);
        }
    }

    /**
     * Reads the credential file's whole content. Package-private so a test can fail a read the same
     * way on every platform. An unreadable file on disk cannot do that. The same fixture fails at
     * open on one filesystem and at the first read on another, arriving as two exception types.
     *
     * @param file {@link Path} the credential file to read
     * @return {@link String} the file's content
     * @throws NoSuchFileException when no credential is stored for the id
     * @throws IOException when a stored credential cannot be read
     */
    String readFile(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Narrows the given file down to its owner. Package-private so a test can refuse. No filesystem
     * on the test matrix offers neither permission model, and none can be asked to accept a
     * restriction and drop it.
     *
     * @param file {@link Path} the file to restrict
     * @return boolean true when the filesystem applied an owner-only rule
     * @throws IOException when the filesystem supports a permission model but rejects the change
     */
    boolean restrict(final Path file) throws IOException {
        return SecretFilePermissions.restrictToOwner(file);
    }

    /**
     * Writes the credential to the given path. Package-private so a test can fail the write after
     * the temporary file exists, which is the state a volume dying part-way through leaves behind.
     * Arranging that on a real filesystem means breaking the volume.
     *
     * @param file {@link Path} the path to write to
     * @param secret {@link String} the credential to write
     * @throws IOException when the write fails
     */
    void writeFile(final Path file, final String secret) throws IOException {
        Files.writeString(file, secret, StandardCharsets.UTF_8);
    }

    /**
     * Removes the credential file, reporting no failure when there was nothing to remove.
     * Package-private so a test can refuse the removal while leaving the file in place. A real
     * filesystem refuses a delete only where a held handle blocks one, which is Windows behaviour
     * rather than something the whole matrix would reproduce.
     *
     * @param file {@link Path} the credential file to remove
     * @throws IOException when the removal fails
     */
    void deleteFile(final Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Removes the temporary file a write worked through, whatever became of that write. A move into
     * place leaves nothing here to remove.
     *
     * <p>A failure to remove it is swallowed. The write already failed, and the caller is about to
     * hear about that rather than about the leftover. Nothing sensitive stays behind either way. A
     * leftover holding the credential was restricted to its owner before the credential went in.
     * On the paths where the restriction never landed, the file is still empty.
     *
     * @param temporary {@link Path} the temporary file to remove, or null when none was created
     */
    private static void discard(final @Nullable Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (final IOException ignored) {
            // Nothing useful to do here, and the real failure is already on its way to the caller.
        }
    }

    /**
     * Resolves the file one provider's credential lives in.
     *
     * @param id {@link SecretId} the credential to locate
     * @return {@link Path} that credential's file
     */
    private Path fileFor(final SecretId id) {
        return this.directory.resolve(id.provider() + FILE_SUFFIX);
    }
}
