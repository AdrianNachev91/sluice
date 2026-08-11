package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityFrameworkKeychainTest {

    private static final String NAME_PREFIX = "SluiceFixture:";
    private static final String NAME = NAME_PREFIX + "round-trip";
    private static final String NEVER_STORED = NAME_PREFIX + "never-stored";
    private static final String LABEL = "Sluice test fixture";

    // The status codes are transcribed here a second time, straight from Apple's SecBase.h, and the
    // duplication is the point. The production constants are hand-copied integers in a class the
    // coverage gate excludes. A transposed digit there is invisible to the compiler, the IDE and
    // every other test. It would surface only as a user seeing a bare number where a sentence
    // belonged. Two independent transcriptions disagree the moment either one is wrong.
    //
    // These run on every runner, unlike the native cases below, because nothing here calls out.
    @Nested
    class StatusMessages {

        @Test
        void explainsAKeychainThatCannotPutItsRequestOnScreen() {
            assertThat(SecurityFrameworkKeychain.explain(-25308))
                    .contains("nothing here can put that request on screen")
                    .contains("-25308");
        }

        @Test
        void neverBlamesALockedKeychainForAnInteractionItCouldNotRaise() {
            assertThat(SecurityFrameworkKeychain.explain(-25308)).doesNotContain("locked");
        }

        @Test
        void explainsADismissedRequest() {
            assertThat(SecurityFrameworkKeychain.explain(-128))
                    .contains("dismissed")
                    .contains("-128");
        }

        // Asserting the hedge itself, because a version promising the workaround fixes it would
        // satisfy every other substring here.
        @Test
        void offersTheAuthenticationWorkaroundWithoutAssertingItIsTheCause() {
            assertThat(SecurityFrameworkKeychain.explain(-25293))
                    .contains("would not authenticate")
                    .contains("If it keeps happening")
                    .contains("has been reported to clear it")
                    .contains("-25293");
        }

        @Test
        void explainsAMachineWithNoDefaultKeychain() {
            assertThat(SecurityFrameworkKeychain.explain(-25307))
                    .contains("no default keychain")
                    .contains("-25307");
        }

        @Test
        void answersAnUnwordedStatusWithItsNumber() {
            assertThat(SecurityFrameworkKeychain.explain(-34018)).isEqualTo("error -34018");
        }
    }

    // Only a real keychain can answer these. A global pointer read one dereference short, a
    // dictionary paired wrong, a length field four bytes too narrow. All three pass against a
    // double.
    //
    // The OS guard deliberately does not also guard on whether a keychain answers. Whether a hosted
    // runner can reach one is the unknown these exist to buy, so a Mac that cannot fails them
    // rather than skipping them.
    //
    // Entries land in the real login keychain of whatever machine runs this, and are removed again
    // afterwards. The capitalised prefix is unreachable for a provider id, which the rule
    // validating one keeps lower case.
    //
    // The timeout runs on its own thread so the clock can fire during a native downcall, which
    // cannot be interrupted. It abandons the thread rather than unwinding it, which still ends the
    // suite with a named failure instead of a runner held until the job's own limit.
    @Nested
    @EnabledOnOs(OS.MAC)
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class AgainstTheRealKeychain {

        // Binding here rather than through the selector is deliberate. The selector turns a refusal
        // into an absent tier, which would quietly reduce these cases to no-ops if the binding were
        // ever built wrong. Calling the binding directly means that fails the build instead.
        private final MacKeychain keychain = SecurityFrameworkKeychain.open();

        @AfterEach
        void removeWhateverWasStored() {
            this.keychain.delete(NAME);
        }

        @Test
        void storesACredentialAndReadsTheSameValueBack() {
            this.write(NAME, "sk-synthetic-0001");

            assertThat(this.read(NAME)).isEqualTo("sk-synthetic-0001");
        }

        // The value crosses the seam as bytes and lives here as text, so the encode and the decode
        // have to agree. A credential outside the ASCII range is what tells them apart. One that
        // stays inside it would survive a mismatch in the low byte of every character.
        @Test
        void storesACredentialCarryingCharactersOutsideAscii() {
            this.write(NAME, "sk-synthetic-éüß-0001");

            assertThat(this.read(NAME)).isEqualTo("sk-synthetic-éüß-0001");
        }

        // Nothing else reaches the second of the two calls a replace takes, and a wrong version of
        // it stores the first value forever while reporting success.
        @Test
        void replacesAnEntryTheKeychainAlreadyHeld() {
            this.write(NAME, "sk-synthetic-0001");
            this.write(NAME, "sk-synthetic-0002");

            assertThat(this.read(NAME)).isEqualTo("sk-synthetic-0002");
        }

        // A missing entry must arrive as an answer rather than a fault. Getting it wrong turns every
        // read on a fresh install into a failure.
        @Test
        void answersWithNothingForAnEntryTheKeychainDoesNotHold() {
            assertThat(this.keychain.read(NEVER_STORED)).isEmpty();
        }

        // A separate native query from the read, so the round-trip above proves nothing about it.
        @Test
        void reportsWhetherAnEntryExistsWithoutFailing() {
            assertThat(this.keychain.holds(NEVER_STORED)).isFalse();

            this.write(NAME, "sk-synthetic-0001");

            assertThat(this.keychain.holds(NAME)).isTrue();
        }

        @Test
        void removesAStoredEntry() {
            this.write(NAME, "sk-synthetic-0001");

            this.keychain.delete(NAME);

            assertThat(this.keychain.read(NAME)).isEmpty();
        }

        // The ordinary case rather than an edge one, since a removal clears every writable tier and
        // most of them hold nothing for that provider.
        @Test
        void removingAnEntryTheKeychainDoesNotHoldIsNotAFailure() {
            this.keychain.delete(NEVER_STORED);
        }

        // The only way to make a working keychain refuse anything, so without it the whole
        // refusal-reporting path goes unrun. The tier field is asserted because a surface branches
        // on it, and this is the one place a real refusal can be watched carrying it.
        @Test
        void reportsARefusalGivenToAStore() {
            final MacKeychain noSuchItemClass =
                    SecurityFrameworkKeychain.open("sluice-fixture-no-such-item-class");

            assertThatThrownBy(() -> noSuchItemClass.write(NAME, LABEL,
                    "sk-synthetic-0001".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining(NAME)
                    .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                            .isEqualTo(SecretStoreException.Tier.KEYRING));
        }

        private void write(final String name, final String secret) {
            this.keychain.write(name, LABEL, secret.getBytes(StandardCharsets.UTF_8));
        }

        private String read(final String name) {
            return new String(this.keychain.read(name).orElseThrow(), StandardCharsets.UTF_8);
        }
    }
}
