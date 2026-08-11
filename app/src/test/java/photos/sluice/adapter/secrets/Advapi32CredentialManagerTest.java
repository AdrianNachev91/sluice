package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The only proof the binding works, and it needs the real Windows Credential Manager. Every
// assumption in that class is about what a native function does with the bytes handed to it, and
// nothing but the function itself can answer it. A struct field at the wrong offset compiles. So
// does a string missing its terminator, and an error code read from the wrong place. All three pass
// a test written against a double.
//
// The OS guard names what the class can run on rather than skipping a configuration. A machine that
// is not Windows has no Windows Credential Manager, so this is the whole population.
//
// Entries land in the real credential store of whatever machine runs this. They are named apart
// from anything the app itself stores, and removed again afterwards.
@EnabledOnOs(OS.WINDOWS)
class Advapi32CredentialManagerTest {

    // The prefix is asserted rather than the whole name, because the oversized one below runs to
    // 40,000 characters and a failure would print all of them.
    private static final String TARGET_PREFIX = "SluiceFixture:";
    private static final String TARGET = TARGET_PREFIX + "round-trip";
    private static final String NEVER_STORED = TARGET_PREFIX + "never-stored";
    private static final String USER_NAME = "sluice-fixture";
    private static final String OVERSIZED_TARGET = TARGET_PREFIX + "x".repeat(40000);

    // Binding here rather than through the selector is deliberate. The selector turns a refusal into
    // an absent tier, which would quietly reduce this whole class to a no-op if the binding were
    // ever built wrong. Calling the binding directly means that fails the build instead.
    private final WindowsCredentialManager credentials = Advapi32CredentialManager.open();

    @AfterEach
    void removeWhateverWasStored() {
        this.credentials.delete(TARGET);
    }

    @Test
    void storesACredentialAndReadsTheSameBytesBack() {
        this.credentials.write(TARGET, USER_NAME, bytes("sk-synthetic-0001"));

        assertThat(this.credentials.read(TARGET)).hasValueSatisfying(
                stored -> assertThat(stored).isEqualTo(bytes("sk-synthetic-0001")));
    }

    // The blob is bytes to Windows and text to everything above it, so the two encodings have to
    // agree. A credential outside the ASCII range is what tells them apart. One that stays inside it
    // would survive a UTF-8 and UTF-16 mismatch in the low byte of every character.
    @Test
    void storesACredentialCarryingCharactersOutsideAsciiByteForByte() {
        this.credentials.write(TARGET, USER_NAME, bytes("sk-synthetic-éüß-0001"));

        assertThat(this.credentials.read(TARGET)).hasValueSatisfying(
                stored -> assertThat(new String(stored, StandardCharsets.UTF_8))
                        .isEqualTo("sk-synthetic-éüß-0001"));
    }

    @Test
    void replacesAnEntryTheStoreAlreadyHeld() {
        this.credentials.write(TARGET, USER_NAME, bytes("sk-synthetic-0001"));
        this.credentials.write(TARGET, USER_NAME, bytes("sk-synthetic-0002"));

        assertThat(this.credentials.read(TARGET)).hasValueSatisfying(
                stored -> assertThat(stored).isEqualTo(bytes("sk-synthetic-0002")));
    }

    // The code for a missing entry is the one refusal this binding reads as an answer rather than a
    // fault. Getting it wrong turns every read on a fresh install into a failure.
    @Test
    void answersWithNothingForAnEntryTheStoreDoesNotHold() {
        assertThat(this.credentials.read(NEVER_STORED)).isEmpty();
    }

    @Test
    void removesAStoredEntry() {
        this.credentials.write(TARGET, USER_NAME, bytes("sk-synthetic-0001"));

        this.credentials.delete(TARGET);

        assertThat(this.credentials.read(TARGET)).isEmpty();
    }

    // Windows reports a missing entry the same way whether a read or a delete asked for it, so the
    // delete has to read that code too. The store above clears every writable tier on any removal,
    // which makes this the ordinary case rather than an edge one.
    @Test
    void removingAnEntryTheStoreDoesNotHoldIsNotAFailure() {
        this.credentials.delete(NEVER_STORED);
    }

    // An oversized secret is the refusal a write can be given on demand. Windows caps a credential
    // blob at 2560 bytes and rejects a larger one outright. The exact code is not asserted, because
    // what this proves is that a refusal arrives as a failure rather than as silence.
    @Test
    void reportsARefusalGivenToAWrite() {
        final byte[] oversized = new byte[3000];
        Arrays.fill(oversized, (byte) 'x');

        assertThatThrownBy(() -> this.credentials.write(TARGET, USER_NAME, oversized))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining(TARGET)
                .hasMessageContaining("error code")
                // A surface wording a credential failure branches on the tier field rather than
                // on message prose, so the field is part of what this binding promises.
                .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                        .isEqualTo(SecretStoreException.Tier.KEYRING));
    }

    // A read and a removal need their own, because they route a refusal through the code that first
    // asks whether it only means the entry is absent. The write above never reaches that code.
    // Proving one branch of it says nothing about the other, and the wrong answer there turns a real
    // failure into a silent "no credential stored".
    //
    // An oversized entry name is the refusal those two can be given. Windows caps a generic
    // credential's name at 32767 characters.
    // The reported code is what separates this from the other way these calls can fail. A binding
    // that is not callable at all throws the same type and carries no code, so asserting the type
    // alone would pass either way.
    @Test
    void reportsARefusalGivenToAReadRatherThanCallingTheEntryAbsent() {
        assertThatThrownBy(() -> this.credentials.read(OVERSIZED_TARGET))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining("error code")
                .hasMessageContaining(TARGET_PREFIX);
    }

    @Test
    void reportsARefusalGivenToARemovalRatherThanCallingTheEntryAbsent() {
        assertThatThrownBy(() -> this.credentials.delete(OVERSIZED_TARGET))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining("error code")
                .hasMessageContaining(TARGET_PREFIX);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
