package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// A real directory rather than a fake filesystem. What this tier is for is the state it leaves on
// disk, so a fake would answer the questions worth asking here.
class FileSecretTierTest {

    private static final SecretId ANTHROPIC = new SecretId("anthropic", "ANTHROPIC_API_KEY");
    private static final SecretId OTHER = new SecretId("other", "OTHER_PROVIDER_API_KEY");
    private static final String KEY_FILE = "anthropic.key";

    @Nested
    class Read {

        @Test
        void readsBackACredentialItStored(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        // A credential is an opaque string the provider chose, and nothing says it is ASCII. The
        // bytes on disk are asserted against UTF-8 directly, since a round trip through this class
        // would agree with itself under any charset it picked.
        @Test
        void storesAndReadsBackANonAsciiCredentialAsUtf8(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);
            final String credential = "sk-clé-Ωmega-鍵-0001";

            tier.write(ANTHROPIC, credential);

            assertThat(secrets.resolve(KEY_FILE)).binaryContent()
                    .isEqualTo(credential.getBytes(StandardCharsets.UTF_8));
            assertThat(tier.read(ANTHROPIC)).contains(credential);
        }

        // Another provider's credential is stored, so the directory is neither missing nor empty.
        // The empty result comes from this provider having nothing of its own.
        @Test
        void readsNoCredentialWhenNoFileWasEverStoredForTheProvider(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);
            tier.write(OTHER, "sk-synthetic-other");

            assertThat(tier.read(ANTHROPIC)).isEmpty();
        }

        // Nothing in the app writes this file yet, so a user filling it does it by hand, and the
        // ordinary ways of doing that append a newline. It would otherwise travel into the API
        // request as part of the key.
        @Test
        void stripsWhitespaceAroundAHandWrittenCredential(@TempDir final Path secrets) throws IOException {
            Files.writeString(secrets.resolve(KEY_FILE), "sk-synthetic-0001\n", StandardCharsets.UTF_8);
            final var tier = new FileSecretTier(secrets);

            assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        // The credential directory is only created by a save, so the first read on a fresh install
        // reaches a path whose parent is not there either.
        @Test
        void readsNoCredentialWhenTheDirectoryDoesNotExist(@TempDir final Path parent) {
            final var tier = new FileSecretTier(parent.resolve("secrets"));

            assertThat(tier.read(ANTHROPIC)).isEmpty();
        }

        // A file someone emptied by hand means the same thing as a variable someone cleared in a
        // shell. Handing the app a blank credential would send it to the API with one.
        @Test
        void aStoredFileWhoseContentIsBlankReadsAsNoCredential(@TempDir final Path secrets) throws IOException {
            Files.writeString(secrets.resolve(KEY_FILE), "   \n", StandardCharsets.UTF_8);
            final var tier = new FileSecretTier(secrets);

            assertThat(tier.read(ANTHROPIC)).isEmpty();
        }

        // The credential really is stored, so absence is not an honest answer here. Reporting one
        // would send the user to add a key that is already on disk, and there is no tier below this
        // one to fall through to.
        @Test
        void aStoredCredentialThatCannotBeReadThrowsRatherThanReportingAbsence(@TempDir final Path secrets) {
            new FileSecretTier(secrets).write(ANTHROPIC, "sk-synthetic-0001");
            final var tier = new FailingReadTier(secrets);

            assertThatThrownBy(() -> tier.read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    .hasCauseInstanceOf(IOException.class);
        }

        // A secrets directory that lost its execute bit, or a file whose access rules stop naming
        // the current user, cannot be inspected at all. An existence check answers no in both
        // cases, which is the same answer it gives for a machine that has no credential. Only the
        // read attempt tells the two apart.
        @Test
        void aCredentialFileThatCannotBeInspectedFailsLoudRatherThanReadingAsAbsent(
                @TempDir final Path secrets) {
            final var tier = new UnreachableFileTier(secrets);

            assertThatThrownBy(() -> tier.read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    .hasCauseInstanceOf(AccessDeniedException.class);
        }

        // A failed read cannot tell a missing file from an unreachable one, so the message reports
        // the attempt rather than describing what is on disk. The whole sentence is pinned rather
        // than a keyword, since a rewording that started claiming the file is there would keep
        // passing a keyword check.
        @Test
        void theFailureMessageReportsTheAttemptRatherThanTheStateOfTheFile(
                @TempDir final Path secrets) {
            final var tier = new FailingReadTier(secrets);

            assertThatThrownBy(() -> tier.read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessage("Reading the credential for provider 'anthropic' from "
                            + secrets.resolve(KEY_FILE) + " failed");
        }
    }

    @Nested
    class Holds {

        @Test
        void answersYesForAStoredCredentialAndNoForOneNeverStored(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);
            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(tier.holds(ANTHROPIC)).isTrue();
            assertThat(tier.holds(OTHER)).isFalse();
        }

        // A blank file reads as no credential, so reporting one held here would let a screen
        // announce a stored key the caller can never retrieve.
        @Test
        void answersNoForAStoredFileWhoseContentIsBlank(@TempDir final Path secrets) throws IOException {
            Files.writeString(secrets.resolve(KEY_FILE), "  ", StandardCharsets.UTF_8);
            final var tier = new FileSecretTier(secrets);

            assertThat(tier.holds(ANTHROPIC)).isFalse();
        }

        @Test
        void failsLoudRatherThanAnsweringNoWhenAStoredCredentialCannotBeRead(@TempDir final Path secrets) {
            new FileSecretTier(secrets).write(ANTHROPIC, "sk-synthetic-0001");
            final var tier = new FailingReadTier(secrets);

            assertThatThrownBy(() -> tier.holds(ANTHROPIC)).isInstanceOf(SecretStoreException.class);
        }
    }

    @Nested
    class Write {

        @Test
        void replacesACredentialAlreadyStored(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);
            tier.write(ANTHROPIC, "sk-synthetic-first");

            tier.write(ANTHROPIC, "sk-synthetic-second");

            assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-second");
        }

        // The credential directory sits beside the config file rather than in it, and nothing else
        // creates it. A fresh install stores its first credential into a directory that is not
        // there yet.
        @Test
        void createsTheCredentialDirectoryWhenItIsNotThereYet(@TempDir final Path parent) {
            final Path secrets = parent.resolve("secrets");
            final var tier = new FileSecretTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(secrets).isDirectory();
            assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        // The write goes to a temporary name and is moved into place. A leftover under the
        // temporary name would be a second copy of the credential, unprotected by the move.
        @Test
        void leavesOnlyTheCredentialFileBehind(@TempDir final Path secrets) throws IOException {
            final var tier = new FileSecretTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(fileNamesIn(secrets)).containsExactly(KEY_FILE);
        }

        // Two installs can share one app-data directory, and one process can run two jobs. A
        // temporary name derived from the provider alone would be the same path for all of them,
        // where each write publishes or destroys another's.
        @Test
        void everyWriteWorksThroughATemporaryFileOfItsOwn(@TempDir final Path secrets) {
            final var tier = new RecordingTemporaryTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-first");
            tier.write(ANTHROPIC, "sk-synthetic-second");

            assertThat(tier.temporaries).hasSize(2).doesNotHaveDuplicates();
        }

        @Test
        void aWriteThatFailsThrowsNamingTheProvider(@TempDir final Path secrets) {
            final var tier = new FailingWriteTier(secrets);

            assertThatThrownBy(() -> tier.write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    .hasCauseInstanceOf(IOException.class);
        }

        // A crash mid-write must leave the previous credential rather than a truncated one. The
        // temporary file really was created before the failure, so the cleanup has something to do.
        @Test
        void aFailedWriteClearsItsTemporaryFileAndKeepsTheStoredCredential(@TempDir final Path secrets)
                throws IOException {
            final var tier = new FileSecretTier(secrets);
            tier.write(ANTHROPIC, "sk-synthetic-first");

            assertThatThrownBy(() -> new FailingWriteTier(secrets).write(ANTHROPIC, "sk-synthetic-second"))
                    .isInstanceOf(SecretStoreException.class);

            assertThat(fileNamesIn(secrets)).containsExactly(KEY_FILE);
            assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-first");
        }
    }

    @Nested
    class Protection {

        // The refusal is the loudest safety claim this tier makes, and no filesystem can be asked
        // to produce one on demand.
        @Test
        void refusesToStoreAnythingOnAFilesystemThatCannotRestrictAFile(@TempDir final Path secrets) {
            final var tier = new RefusingRestrictTier(secrets);

            assertThatThrownBy(() -> tier.write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic");
        }

        // The refusal says the credential was not stored, and writing it first would make that
        // sentence false for as long as the delete afterwards takes. On a filesystem that cannot
        // restrict anything, the delete is the only thing standing between a plaintext credential
        // and every account on the machine.
        @Test
        void neverWritesTheCredentialAtAllWhenTheRestrictionIsRefused(@TempDir final Path secrets)
                throws IOException {
            final var tier = new RefusingRestrictTier(secrets);

            assertThatThrownBy(() -> tier.write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class);

            assertThat(tier.wroteContent).isFalse();
            assertThat(fileNamesIn(secrets)).isEmpty();
        }

        // A file created by an ordinary write is readable by anyone until the restriction lands.
        // Restricting the empty file first closes that window rather than narrowing it.
        @Test
        void restrictsTheTemporaryFileBeforeTheCredentialGoesIntoIt(@TempDir final Path secrets) {
            final var tier = new StepRecordingTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(tier.steps).containsExactly("restrict", "write");
        }

        @Test
        void aRestrictionTheFilesystemRejectsFailsTheWrite(@TempDir final Path secrets) throws IOException {
            final var tier = new FailingRestrictTier(secrets);

            assertThatThrownBy(() -> tier.write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class)
                    .hasCauseInstanceOf(IOException.class);

            assertThat(fileNamesIn(secrets)).isEmpty();
        }

        // One of these two runs per CI leg, and between them the matrix covers both permission
        // models against a real filesystem.
        @Test
        void storesTheCredentialReadableByItsOwnerAlone(@TempDir final Path secrets) throws IOException {
            assumeTrue(supports(secrets, PosixFileAttributeView.class),
                    "the filesystem answers with POSIX permissions");
            final var tier = new FileSecretTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(Files.getPosixFilePermissions(secrets.resolve(KEY_FILE)))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
        }

        @Test
        void storesTheCredentialUnderASingleAccessRuleNamingItsOwner(@TempDir final Path secrets)
                throws IOException {
            assumeTrue(supports(secrets, AclFileAttributeView.class),
                    "the filesystem answers with access rules");
            assumeFalse(ownerOfAFileIn(secrets) instanceof GroupPrincipal,
                    "this process creates files owned by a group, which no single rule can restrict");
            final var tier = new FileSecretTier(secrets);

            tier.write(ANTHROPIC, "sk-synthetic-0001");

            final var acl = Files.getFileAttributeView(secrets.resolve(KEY_FILE),
                    AclFileAttributeView.class);
            assertThat(acl.getAcl()).singleElement()
                    .satisfies(entry -> assertThat(entry.principal()).isEqualTo(acl.getOwner()));
        }
    }

    @Nested
    class Erase {

        @Test
        void removesTheStoredCredential(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);
            tier.write(ANTHROPIC, "sk-synthetic-0001");

            tier.erase(ANTHROPIC);

            assertThat(tier.read(ANTHROPIC)).isEmpty();
            assertThat(secrets.resolve(KEY_FILE)).doesNotExist();
        }

        // A remove reaches every writable tier, including ones that never held the credential.
        // Holding none has to be an ordinary outcome rather than a failure.
        @Test
        void erasingWhenNothingIsStoredIsNotAnError(@TempDir final Path secrets) {
            final var tier = new FileSecretTier(secrets);

            assertThatCode(() -> tier.erase(ANTHROPIC)).doesNotThrowAnyException();
        }

        // A removal that fails leaves the credential readable, so reporting success would tell a
        // user their key is gone while it still answers every read.
        @Test
        void aFailedRemovalFailsLoudNamingTheProvider(@TempDir final Path secrets) {
            final var tier = new FailingDeleteTier(secrets);
            tier.write(ANTHROPIC, "sk-synthetic-0004");

            assertThatThrownBy(() -> tier.erase(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic");
        }
    }

    @Nested
    class Placement {

        // A permission-restricted file is the fallback for machines offering no credential store,
        // so this tier is the one that always answers.
        @Test
        void isAvailableOnEveryMachine(@TempDir final Path secrets) {
            assertThat(new FileSecretTier(secrets).available()).isTrue();
        }

        // The tiers are ordered on this number, and the ordering happens outside this class. The
        // method has to report the declared constant rather than a value worked out elsewhere.
        @Test
        void reportsItsDeclaredPrecedence(@TempDir final Path secrets) {
            assertThat(new FileSecretTier(secrets).precedence()).isEqualTo(FileSecretTier.PRECEDENCE);
        }

        // A restricted file is protected by permissions rather than encryption. A screen cannot
        // show it the sentence it shows for a machine's own credential store.
        @Test
        void reportsItselfAsTheFileTierWhenItAnswers(@TempDir final Path secrets) {
            assertThat(new FileSecretTier(secrets).statusWhenAnswering(ANTHROPIC))
                    .isEqualTo(new SecretStatus.InFile());
        }
    }

    private static List<String> fileNamesIn(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (final Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static boolean supports(final Path directory,
            final Class<? extends FileAttributeView> view) throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView(view);
    }

    // Answers who owns a file this process creates in the given directory. A probe file rather than
    // the credential file, because the assumption it feeds has to be settled before the write runs.
    private static UserPrincipal ownerOfAFileIn(final Path directory) throws IOException {
        final Path probe = Files.createTempFile(directory, "owner-probe", ".tmp");
        try {
            return Files.getFileAttributeView(probe, AclFileAttributeView.class).getOwner();
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    // Fails the read itself rather than the check ahead of it, so the tier's own classification of
    // absent against unreadable runs for real.
    private static final class FailingReadTier extends FileSecretTier {

        private FailingReadTier(final Path directory) {
            super(directory);
        }

        @Override
        String readFile(final Path file) throws IOException {
            throw new IOException("the volume stopped responding part-way through the read");
        }
    }

    // Fails the write after the temporary file exists, so the cleanup path is exercised against the
    // mess a real mid-write failure leaves. Thrown as IOException rather than something unchecked,
    // because that is the type the cleanup is attached to.
    private static final class FailingWriteTier extends FileSecretTier {

        private FailingWriteTier(final Path directory) {
            super(directory);
        }

        @Override
        void writeFile(final Path file, final String secret) throws IOException {
            super.writeFile(file, secret);
            throw new IOException("the volume stopped responding part-way through the write");
        }
    }

    // Fails the read the way a directory nobody may look into does. The path it names is not on
    // disk, so an existence check ahead of the read would report absence rather than this failure.
    private static final class UnreachableFileTier extends FileSecretTier {

        private UnreachableFileTier(final Path directory) {
            super(directory);
        }

        @Override
        String readFile(final Path file) throws IOException {
            throw new AccessDeniedException(file.toString());
        }
    }

    // Stands in for a filesystem offering neither permission model, which is the one branch no real
    // filesystem on the test matrix takes. Records whether the credential was written regardless.
    private static final class RefusingRestrictTier extends FileSecretTier {

        private boolean wroteContent;

        private RefusingRestrictTier(final Path directory) {
            super(directory);
        }

        @Override
        boolean restrict(final Path file) {
            return false;
        }

        @Override
        void writeFile(final Path file, final String secret) throws IOException {
            this.wroteContent = true;
            super.writeFile(file, secret);
        }
    }

    // Records the order the two steps a write's safety rests on actually ran in.
    private static final class StepRecordingTier extends FileSecretTier {

        private final List<String> steps = new ArrayList<>();

        private StepRecordingTier(final Path directory) {
            super(directory);
        }

        @Override
        boolean restrict(final Path file) throws IOException {
            this.steps.add("restrict");
            return super.restrict(file);
        }

        @Override
        void writeFile(final Path file, final String secret) throws IOException {
            this.steps.add("write");
            super.writeFile(file, secret);
        }
    }

    // Stands in for a filesystem that offers a permission model and then rejects the change.
    private static final class FailingRestrictTier extends FileSecretTier {

        private FailingRestrictTier(final Path directory) {
            super(directory);
        }

        @Override
        boolean restrict(final Path file) throws IOException {
            throw new IOException("the volume rejected the permission change");
        }
    }

    // Records which path each write worked through, since the name is generated and the caller
    // never sees it.
    private static final class RecordingTemporaryTier extends FileSecretTier {

        private final List<Path> temporaries = new ArrayList<>();

        private RecordingTemporaryTier(final Path directory) {
            super(directory);
        }

        @Override
        void writeFile(final Path file, final String secret) throws IOException {
            this.temporaries.add(file);
            super.writeFile(file, secret);
        }
    }

    // Fails the removal without touching the file, so the credential is genuinely still there when
    // the assertion checks that a failed erase did not quietly lose it.
    private static final class FailingDeleteTier extends FileSecretTier {

        private FailingDeleteTier(final Path directory) {
            super(directory);
        }

        @Override
        void deleteFile(final Path file) throws IOException {
            throw new IOException("the volume rejected the delete");
        }
    }
}
