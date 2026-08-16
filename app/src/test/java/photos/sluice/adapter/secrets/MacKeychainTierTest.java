package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A fake keychain rather than the real one, so every case here runs on every runner rather than
// only on macOS.
class MacKeychainTierTest {

    private static final SecretId ANTHROPIC = new SecretId("anthropic", "ANTHROPIC_API_KEY");
    private static final SecretId OTHER = new SecretId("other-provider", "OTHER_API_KEY");

    @Nested
    class Read {

        @Test
        void answersWithTheStoredCredential() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(keychain).read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        @Test
        void answersWithNothingWhenTheKeychainHoldsNoEntry() {
            assertThat(tier(new FakeKeychain()).read(ANTHROPIC)).isEmpty();
        }

        // A tier naming every entry the same way would pass every other test in this class.
        @Test
        void neverAnswersWithAnotherProvidersCredential() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(keychain).read(OTHER)).isEmpty();
        }

        // A credential inside the ASCII range would survive a mismatch in the low byte of every
        // character, so it could not tell the decode from the encode.
        @Test
        void decodesACredentialCarryingCharactersOutsideAscii() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-éüß-0001");

            assertThat(tier(keychain).read(ANTHROPIC)).contains("sk-synthetic-éüß-0001");
        }

        @Test
        void stripsSurroundingWhitespaceOffTheStoredValue() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "  sk-synthetic-0001\n");

            assertThat(tier(keychain).read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        @Test
        void treatsAnEntryHoldingOnlyWhitespaceAsHoldingNothing() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", " \n ");

            assertThat(tier(keychain).read(ANTHROPIC)).isEmpty();
        }

        // Copying the Linux tier's second existence check across would leave every other case in
        // this class green.
        @Test
        void neverAsksWhetherTheEntryExistsAfterAReadFindsNothing() {
            final var keychain = new FakeKeychain();

            tier(keychain).read(ANTHROPIC);

            assertThat(keychain.namesSearched).isEmpty();
        }

        @Test
        void failsLoudWhenAWorkingKeychainRefusesTheRead() {
            final var keychain = new FakeKeychain();
            keychain.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(keychain).read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                            .isEqualTo(SecretStoreException.Tier.KEYRING));
        }

        @Test
        void answersWithNothingWhenTheWholeKeychainIsUnusableHere() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();

            assertThat(tier(keychain).read(ANTHROPIC)).isEmpty();
        }
    }

    @Nested
    class Holds {

        @Test
        void answersTrueOnlyWhenTheKeychainHoldsAnEntry() {
            final var keychain = new FakeKeychain();

            assertThat(tier(keychain).holds(ANTHROPIC)).isFalse();

            keychain.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(keychain).holds(ANTHROPIC)).isTrue();
        }

        // Diverges from the value-reading tiers deliberately, so a reader does not take it for a
        // defect.
        @Test
        void reportsAnEntryHoldingOnlyWhitespaceAsHeld() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "   ");

            assertThat(tier(keychain).holds(ANTHROPIC)).isTrue();
        }

        // Answering through a read would compile and pass every other case in this class.
        @Test
        void neverReadsTheStoredValueToAnswer() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");

            tier(keychain).holds(ANTHROPIC);

            assertThat(keychain.namesRead).isEmpty();
        }

        @Test
        void failsLoudWhenAWorkingKeychainRefusesTheSearch() {
            final var keychain = new FakeKeychain();
            keychain.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(keychain).holds(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class);
        }

        @Test
        void answersFalseWhenTheWholeKeychainIsUnusableHere() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();

            assertThat(tier(keychain).holds(ANTHROPIC)).isFalse();
        }
    }

    @Nested
    class Availability {

        @Test
        void answersAvailableWhenTheKeychainCanBeReached() {
            assertThat(tier(new FakeKeychain()).available()).isTrue();
        }

        @Test
        void answersUnavailableWhenTheKeychainRefusesEveryCall() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();

            assertThat(tier(keychain).available()).isFalse();
        }

        // Reaching for a real provider's entry would make availability depend on whether a
        // credential happens to be stored.
        @Test
        void neverAsksAboutAProvidersOwnEntryToDecideAvailability() {
            final var keychain = new FakeKeychain();

            tier(keychain).available();

            assertThat(keychain.namesSearched).doesNotContain("anthropic");
            assertThat(keychain.namesSearched).isNotEmpty();
        }

        @Test
        void probesThroughExistenceRatherThanThroughARead() {
            final var keychain = new FakeKeychain();

            tier(keychain).available();

            assertThat(keychain.namesRead).isEmpty();
        }
    }

    // The tier under the store it actually runs beneath, rather than on its own. Both of the
    // Windows binding's real defects lived in this join, where each class was correct alone.
    @Nested
    class UnderTheStore {

        // The tripwire for a documented divergence. If reporting the keyring while the value comes
        // from elsewhere ever stops being acceptable, this is the test that has to change.
        @Test
        void reportsTheKeyringWhileTheValueComesFromTheFileTier() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "   ");
            final var file = new StubFileTier(ANTHROPIC, "from-file");
            final SecretStore store = new TieredSecretStore(
                    new EnvironmentSecretTier(_ -> null), List.of(tier(keychain), file));

            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InKeyring());
            assertThat(store.secret(ANTHROPIC)).contains("from-file");
        }

        // A machine whose keychain answers nothing must still work off the file below it. Getting
        // this wrong is how the Windows binding could store a credential it never read back.
        @Test
        void answersFromTheFileTierWhenTheKeychainIsUnusableHere() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();
            final var file = new StubFileTier(ANTHROPIC, "from-file");
            final SecretStore store = new TieredSecretStore(
                    new EnvironmentSecretTier(_ -> null), List.of(tier(keychain), file));

            assertThat(store.secret(ANTHROPIC)).contains("from-file");
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InFile());
        }
    }

    @Nested
    class Registration {

        @Test
        void outranksTheFileTier() {
            assertThat(MacKeychainTier.PRECEDENCE).isGreaterThan(FileSecretTier.PRECEDENCE);
        }

        @Test
        void namesItselfTheKeyring() {
            assertThat(tier(new FakeKeychain()).storedLocation())
                    .isEqualTo(new SecretStatus.InKeyring());
        }
    }

    @Nested
    class Write {

        @Test
        void storesTheCredentialUnderTheProvidersOwnEntry() {
            final var keychain = new FakeKeychain();

            tier(keychain).write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(keychain.storedText("anthropic")).isEqualTo("sk-synthetic-0001");
        }

        @Test
        void encodesACredentialCarryingCharactersOutsideAscii() {
            final var keychain = new FakeKeychain();

            tier(keychain).write(ANTHROPIC, "sk-synthetic-éüß-0001");

            assertThat(keychain.storedText("anthropic")).isEqualTo("sk-synthetic-éüß-0001");
        }

        @Test
        void labelsTheEntryWithTheAppAndTheProvider() {
            final var keychain = new FakeKeychain();

            tier(keychain).write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(keychain.labels).containsEntry("anthropic", "Sluice:anthropic");
        }

        @Test
        void replacesACredentialTheKeychainAlreadyHeld() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");

            tier(keychain).write(ANTHROPIC, "sk-synthetic-0002");

            assertThat(keychain.storedText("anthropic")).isEqualTo("sk-synthetic-0002");
        }

        // One entry rather than everything, so this stays distinct from the case below it.
        @Test
        void failsLoudWhenAWorkingKeychainRefusesTheWrite() {
            final var keychain = new FakeKeychain();
            keychain.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(keychain).write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class);
        }

        // Nothing else pins the write's asymmetry with read and erase, which do absorb this.
        @Test
        void failsLoudEvenWhenTheWholeKeychainIsUnusableHere() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();

            assertThatThrownBy(() -> tier(keychain).write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class);
        }
    }

    @Nested
    class Erase {

        @Test
        void clearsTheProvidersEntry() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");

            tier(keychain).erase(ANTHROPIC);

            assertThat(keychain.entries).doesNotContainKey("anthropic");
        }

        @Test
        void leavesAnotherProvidersCredentialAlone() {
            final var keychain = new FakeKeychain();
            keychain.store("anthropic", "sk-synthetic-0001");
            keychain.store("other-provider", "sk-synthetic-0002");

            tier(keychain).erase(ANTHROPIC);

            assertThat(keychain.entries).containsOnlyKeys("other-provider");
        }

        @Test
        void clearingWhatTheKeychainDoesNotHoldIsNotAnError() {
            tier(new FakeKeychain()).erase(ANTHROPIC);
        }

        @Test
        void failsLoudWhenAWorkingKeychainRefusesTheRemoval() {
            final var keychain = new FakeKeychain();
            keychain.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(keychain).erase(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class);
        }

        @Test
        void clearingIsNotAFailureWhenTheWholeKeychainIsUnusableHere() {
            final var keychain = new FakeKeychain();
            keychain.refuseEverything();

            tier(keychain).erase(ANTHROPIC);
        }
    }

    private static MacKeychainTier tier(final MacKeychain keychain) {
        return new MacKeychainTier(keychain);
    }

    // A stub rather than the real file tier, which would want a temp directory and a written
    // credential to answer at all. All this test needs from the tier below is that it holds
    // something. The disk work would be setup with no bearing on what is proved.
    //
    // It answers for one id only. A store consulting the lower tier with the wrong credential's id
    // would otherwise still be handed a value, and the test would pass.
    private record StubFileTier(SecretId answersFor, String held) implements WritableSecretTier {

        @Override
        public Optional<String> read(final SecretId id) {
            return this.answersFor.equals(id) ? Optional.of(this.held) : Optional.empty();
        }

        @Override
        public boolean holds(final SecretId id) {
            return this.answersFor.equals(id);
        }

        @Override
        public SecretStatus.StoredLocation storedLocation() {
            return new SecretStatus.InFile();
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int precedence() {
            return FileSecretTier.PRECEDENCE;
        }

        @Override
        public void write(final SecretId id, final String secret) {
            throw new AssertionError("this test never saves");
        }

        @Override
        public void erase(final SecretId id) {
            throw new AssertionError("this test never removes");
        }
    }

    // Values are held as bytes because that is what the seam carries, so the tier's own encode and
    // decode are exercised rather than skipped.
    private static final class FakeKeychain implements MacKeychain {

        private final Map<String, byte[]> entries = new HashMap<>();
        private final Map<String, String> labels = new HashMap<>();
        private final Set<String> namesRead = new HashSet<>();
        private final Set<String> namesSearched = new HashSet<>();
        private final Set<String> refusedNames = new HashSet<>();
        private boolean refusing;

        @Override
        public Optional<byte[]> read(final String name) {
            this.refuseIfBroken();
            this.refuseIfNamed(name);
            this.namesRead.add(name);
            return Optional.ofNullable(this.entries.get(name));
        }

        @Override
        public boolean holds(final String name) {
            this.refuseIfBroken();
            this.refuseIfNamed(name);
            this.namesSearched.add(name);
            return this.entries.containsKey(name);
        }

        @Override
        public void write(final String name, final String label, final byte[] secret) {
            this.refuseIfBroken();
            this.refuseIfNamed(name);
            this.entries.put(name, secret);
            this.labels.put(name, label);
        }

        @Override
        public void delete(final String name) {
            this.refuseIfBroken();
            this.refuseIfNamed(name);
            this.entries.remove(name);
        }

        private void store(final String name, final String secret) {
            this.entries.put(name, secret.getBytes(StandardCharsets.UTF_8));
        }

        private String storedText(final String name) {
            return new String(this.entries.get(name), StandardCharsets.UTF_8);
        }

        private void refuseEverything() {
            this.refusing = true;
        }

        // The case the availability probe has to tell apart from a keychain answering nothing.
        private void refuseOnly(final String name) {
            this.refusedNames.add(name);
        }

        private void refuseIfBroken() {
            if (this.refusing) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "this keychain refuses every call");
            }
        }

        private void refuseIfNamed(final String name) {
            if (this.refusedNames.contains(name)) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "this keychain cannot answer for " + name);
            }
        }
    }
}
