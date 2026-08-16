package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A fake service rather than the real one, so every case here runs on every runner. What the real
// Secret Service does with these calls is LibsecretServiceTest's subject, and it can only be asked
// on Linux.
class LinuxSecretServiceTierTest {

    private static final SecretId ANTHROPIC = new SecretId("anthropic", "ANTHROPIC_API_KEY");
    private static final SecretId OTHER = new SecretId("other-provider", "OTHER_API_KEY");

    @Nested
    class Read {

        @Test
        void answersWithTheStoredCredential() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(service).read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        @Test
        void answersWithNothingWhenTheServiceHoldsNoEntry() {
            assertThat(tier(new FakeSecretService()).read(ANTHROPIC)).isEmpty();
        }

        // A tier naming every entry the same way would pass every other test in this class.
        @Test
        void neverAnswersWithAnotherProvidersCredential() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(service).read(OTHER)).isEmpty();
        }

        // Only a hand-written entry can hold whitespace, since a save strips before storing.
        @Test
        void stripsSurroundingWhitespaceOffTheStoredValue() {
            final var service = new FakeSecretService();
            service.store("anthropic", "  sk-synthetic-0001\n");

            assertThat(tier(service).read(ANTHROPIC)).contains("sk-synthetic-0001");
        }

        @Test
        void treatsAnEntryHoldingOnlyWhitespaceAsHoldingNothing() {
            final var service = new FakeSecretService();
            service.store("anthropic", " \n ");

            assertThat(tier(service).read(ANTHROPIC)).isEmpty();
        }

        @Test
        void failsLoudWhenAWorkingServiceRefusesTheRead() {
            final var service = new FakeSecretService();
            service.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(service).read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class);
        }

        @Test
        void answersWithNothingWhenTheWholeServiceIsUnusableHere() {
            final var service = new FakeSecretService();
            service.refuseEverything();

            assertThat(tier(service).read(ANTHROPIC)).isEmpty();
        }

        @Test
        void failsLoudWhenTheEntryIsThereAndTheServiceWouldNotOpenIt() {
            final var service = new FakeSecretService();
            service.storeUnopenable("anthropic");

            assertThatThrownBy(() -> tier(service).read(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                            .isEqualTo(SecretStoreException.Tier.KEYRING));
        }

        // The existence check above must not fire for an entry the service did hand over. A
        // whitespace-only value is the case that separates the two: it arrives as a real answer
        // and is emptied here, rather than never arriving at all.
        @Test
        void treatsAnEntryHoldingOnlyWhitespaceAsAbsentRatherThanAsUnopenable() {
            final var service = new FakeSecretService();
            service.store("anthropic", "   ");

            assertThat(tier(service).read(ANTHROPIC)).isEmpty();
        }
    }

    @Nested
    class Holds {

        @Test
        void answersTrueOnlyWhenTheServiceHoldsAnEntry() {
            final var service = new FakeSecretService();

            assertThat(tier(service).holds(ANTHROPIC)).isFalse();

            service.store("anthropic", "sk-synthetic-0001");

            assertThat(tier(service).holds(ANTHROPIC)).isTrue();
        }

        // Diverges from the value-reading tiers deliberately, so a reader does not take it for a
        // defect. Only a hand-written entry can reach it: a save strips and refuses a blank.
        @Test
        void reportsAnEntryHoldingOnlyWhitespaceAsHeld() {
            final var service = new FakeSecretService();
            service.store("anthropic", "   ");

            assertThat(tier(service).holds(ANTHROPIC)).isTrue();
        }

        @Test
        void neverReadsTheStoredValueToAnswer() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");

            tier(service).holds(ANTHROPIC);

            assertThat(service.namesRead).isEmpty();
        }

        @Test
        void failsLoudWhenAWorkingServiceRefusesTheSearch() {
            final var service = new FakeSecretService();
            service.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(service).holds(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class);
        }

        @Test
        void answersFalseWhenTheWholeServiceIsUnusableHere() {
            final var service = new FakeSecretService();
            service.refuseEverything();

            assertThat(tier(service).holds(ANTHROPIC)).isFalse();
        }
    }

    @Nested
    class Availability {

        @Test
        void answersAvailableWhenTheServiceCanBeReached() {
            assertThat(tier(new FakeSecretService()).available()).isTrue();
        }

        @Test
        void answersUnavailableWhenTheServiceRefusesEveryCall() {
            final var service = new FakeSecretService();
            service.refuseEverything();

            assertThat(tier(service).available()).isFalse();
        }

        // Reaching for a real provider's entry would make availability depend on whether a
        // credential happens to be stored.
        @Test
        void neverAsksAboutAProvidersOwnEntryToDecideAvailability() {
            final var service = new FakeSecretService();

            tier(service).available();

            assertThat(service.namesSearched).doesNotContain("anthropic");
            assertThat(service.namesSearched).isNotEmpty();
        }

        @Test
        void probesThroughExistenceRatherThanThroughARead() {
            final var service = new FakeSecretService();

            tier(service).available();

            assertThat(service.namesRead).isEmpty();
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
            final var service = new FakeSecretService();
            service.store("anthropic", "   ");
            final var file = new StubFileTier(ANTHROPIC, "from-file");
            final SecretStore store = new TieredSecretStore(
                    new EnvironmentSecretTier(_ -> null), List.of(tier(service), file));

            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InKeyring());
            assertThat(store.secret(ANTHROPIC)).contains("from-file");
        }
    }

    @Nested
    class Registration {

        @Test
        void outranksTheFileTier() {
            assertThat(LinuxSecretServiceTier.PRECEDENCE).isGreaterThan(FileSecretTier.PRECEDENCE);
        }

        @Test
        void namesItselfTheKeyring() {
            assertThat(tier(new FakeSecretService()).storedLocation())
                    .isEqualTo(new SecretStatus.InKeyring());
        }
    }

    @Nested
    class Write {

        @Test
        void storesTheCredentialUnderTheProvidersOwnEntry() {
            final var service = new FakeSecretService();

            tier(service).write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(service.entries).containsEntry("anthropic", "sk-synthetic-0001");
        }

        @Test
        void labelsTheEntryWithTheAppAndTheProvider() {
            final var service = new FakeSecretService();

            tier(service).write(ANTHROPIC, "sk-synthetic-0001");

            assertThat(service.labels).containsEntry("anthropic", "Sluice:anthropic");
        }

        @Test
        void replacesACredentialTheServiceAlreadyHeld() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");

            tier(service).write(ANTHROPIC, "sk-synthetic-0002");

            assertThat(service.entries).containsEntry("anthropic", "sk-synthetic-0002");
        }

        // Refuses one entry rather than everything, because the store only routes a save here
        // once availability has answered. A wholly broken service never reaches this method.
        @Test
        void failsLoudWhenAWorkingServiceRefusesTheWrite() {
            final var service = new FakeSecretService();
            service.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(service).write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class);
        }

        // The write is the one operation that never absorbs a refusal. Nothing else pins that
        // asymmetry, and the service can die between the availability check and this call.
        @Test
        void failsLoudEvenWhenTheWholeServiceIsUnusableHere() {
            final var service = new FakeSecretService();
            service.refuseEverything();

            assertThatThrownBy(() -> tier(service).write(ANTHROPIC, "sk-synthetic-0001"))
                    .isInstanceOf(SecretStoreException.class);
        }
    }

    @Nested
    class Erase {

        @Test
        void clearsTheProvidersEntry() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");

            tier(service).erase(ANTHROPIC);

            assertThat(service.entries).doesNotContainKey("anthropic");
        }

        @Test
        void leavesAnotherProvidersCredentialAlone() {
            final var service = new FakeSecretService();
            service.store("anthropic", "sk-synthetic-0001");
            service.store("other-provider", "sk-synthetic-0002");

            tier(service).erase(ANTHROPIC);

            assertThat(service.entries).containsOnlyKeys("other-provider");
        }

        @Test
        void clearingWhatTheServiceDoesNotHoldIsNotAnError() {
            tier(new FakeSecretService()).erase(ANTHROPIC);
        }

        @Test
        void failsLoudWhenAWorkingServiceRefusesTheRemoval() {
            final var service = new FakeSecretService();
            service.refuseOnly("anthropic");

            assertThatThrownBy(() -> tier(service).erase(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class);
        }

        @Test
        void clearingIsNotAFailureWhenTheWholeServiceIsUnusableHere() {
            final var service = new FakeSecretService();
            service.refuseEverything();

            tier(service).erase(ANTHROPIC);
        }
    }

    private static LinuxSecretServiceTier tier(final LinuxSecretService service) {
        return new LinuxSecretServiceTier(service);
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


    // Stands in for the Secret Service, holding entries by name the way it does. A missing entry
    // answers empty on a read, false on an existence check and passes silently on a delete. That
    // is what the real service's returns amount to once its own interface has interpreted them.
    private static final class FakeSecretService implements LinuxSecretService {

        private final Map<String, String> entries = new HashMap<>();
        private final Map<String, String> labels = new HashMap<>();
        private final Set<String> namesRead = new HashSet<>();
        private final Set<String> namesSearched = new HashSet<>();
        private final Set<String> refusedNames = new HashSet<>();
        private final Set<String> unopenable = new HashSet<>();
        private boolean refusing;

        @Override
        public Optional<String> read(final String name) {
            this.refuseIfBroken();
            this.refuseIfNamed(name);
            this.namesRead.add(name);
            if (this.unopenable.contains(name)) {
                return Optional.empty();
            }
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
        public void write(final String name, final String label, final String secret) {
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
            this.entries.put(name, secret);
        }

        // An entry the service admits to holding and will not hand over. That is what a locked
        // collection looks like once libsecret has given up on unlocking it. The search still
        // matches it, while the read produces no value and no error.
        private void storeUnopenable(final String name) {
            this.entries.put(name, "sk-synthetic-unreachable");
            this.unopenable.add(name);
        }

        private void refuseEverything() {
            this.refusing = true;
        }

        // A service that works but fails one entry, which is what a broken install looks like. It
        // is the case the availability probe has to tell apart from a service answering nothing.
        private void refuseOnly(final String name) {
            this.refusedNames.add(name);
        }

        private void refuseIfBroken() {
            if (this.refusing) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "this secret service refuses every call");
            }
        }

        private void refuseIfNamed(final String name) {
            if (this.refusedNames.contains(name)) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "this secret service cannot answer for " + name);
            }
        }
    }
}
