package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

// The tiers are fakes rather than the real ones on purpose. This class decides which tier each
// operation reaches, and a real file tier would answer that question with disk state instead.
class TieredSecretStoreTest {

    private static final SecretId ANTHROPIC = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    @Nested
    class Read {

        @Test
        void environmentWinsOverEveryStoredTier() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, "from-keyring");
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier("from-environment"), keyring, file);

            assertThat(store.secret(ANTHROPIC)).contains("from-environment");
            assertThat(store.status(ANTHROPIC))
                    .isEqualTo(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"));
        }

        @Test
        void theKeyringAnswersAheadOfTheFileWhenNoEnvironmentVariableIsSet() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, "from-keyring");
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            assertThat(store.secret(ANTHROPIC)).contains("from-keyring");
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InKeyring());
        }

        // Declared precedence decides the order, not the order the tiers were handed over.
        @Test
        void aLowerPrecedenceTierHandedOverFirstStillLoses() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, "from-keyring");
            final SecretStore store = tieredSecretStore(environmentTier(null), file, keyring);

            assertThat(store.secret(ANTHROPIC)).contains("from-keyring");
        }

        @Test
        void theFileAnswersWhenNothingAboveItHoldsOne() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, null);
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            assertThat(store.secret(ANTHROPIC)).contains("from-file");
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InFile());
        }

        @Test
        void nothingStoredAnywhereReportsAbsentAndReturnsNoSecret() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), file);

            assertThat(store.secret(ANTHROPIC)).isEmpty();
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.Absent());
        }

        // A tier that cannot say whether it holds a credential is a broken install. Absorbing that
        // into an absent answer would send the user to re-enter a key that may already be stored.
        @Test
        void aTierThatCannotAnswerFailsBothTheValueAndTheStatus() {
            final SecretStore store = tieredSecretStore(environmentTier(null), unreadableTier());

            assertThatThrownBy(() -> store.secret(ANTHROPIC)).isInstanceOf(SecretStoreException.class);
            assertThatThrownBy(() -> store.status(ANTHROPIC)).isInstanceOf(SecretStoreException.class);
        }

        // The short-circuit rests on the stream never pulling a tier the first one already
        // answered for. A broken lower tier is the only way to prove it was not reached.
        @Test
        void anAnsweringEnvironmentIsNeverFollowedByALowerTierThatWouldFail() {
            final SecretStore store =
                    tieredSecretStore(environmentTier("from-environment"), unreadableTier());

            assertThat(store.secret(ANTHROPIC)).contains("from-environment");
            assertThat(store.status(ANTHROPIC))
                    .isEqualTo(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"));
        }

        // A machine's own credential store can ask the user to unlock it before handing anything
        // over. Drawing a settings label must not be what raises that prompt, so the status route
        // asks whether a tier holds a credential without ever retrieving one.
        @Test
        void reportingTheTierNeverRetrievesTheCredential() {
            final var keyring = new RetrievalRefusingTier();
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring);

            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InKeyring());
        }
    }

    @Nested
    class Save {

        @Test
        void writesToTheHighestPrecedenceAvailableTier() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, null);
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            store.save(ANTHROPIC, "fresh");

            assertThat(keyring.written).containsExactly("fresh");
            assertThat(file.written).isEmpty();
        }

        @Test
        void fallsToTheNextTierWhenTheStrongestIsUnavailableHere() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, false, null);
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            store.save(ANTHROPIC, "fresh");

            assertThat(keyring.written).isEmpty();
            assertThat(file.written).containsExactly("fresh");
        }

        // The environment is never a save target, so a set variable must not absorb the write. It
        // does shadow the value on the way back out, which is the surprise the status reports.
        @Test
        void aSetEnvironmentVariableStillTakesTheWriteToAStoredTierAndKeepsWinningTheRead() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier("from-environment"), file);

            store.save(ANTHROPIC, "fresh");

            assertThat(file.written).containsExactly("fresh");
            assertThat(store.secret(ANTHROPIC)).contains("from-environment");
            assertThat(store.status(ANTHROPIC))
                    .isEqualTo(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"));
        }

        // A key pasted out of a browser or a terminal carries whatever whitespace came with it, and
        // the provider counts that as part of the key.
        @Test
        void storesTheCredentialStrippedOfSurroundingWhitespace() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), file);

            store.save(ANTHROPIC, "  sk-synthetic-0001\n");

            assertThat(file.written).containsExactly("sk-synthetic-0001");
        }

        @Test
        void refusesABlankCredentialBeforeAnyTierIsTouched() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), file);

            assertThatThrownBy(() -> store.save(ANTHROPIC, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anthropic");
            assertThat(file.written).isEmpty();
        }

        @Test
        void failsLoudWhenNoTierOnThisMachineCanStoreOne() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, false, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring);

            assertThatThrownBy(() -> store.save(ANTHROPIC, "fresh"))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    // No single tier failed here, and a surface wording the failure must not name
                    // one. The tier field is what it branches on, so the field is the claim.
                    .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                            .isEqualTo(SecretStoreException.Tier.STORE));
        }
    }

    @Nested
    class Remove {

        // Clearing only the tier that answered would expose the one underneath it, which reads as
        // the removal having silently failed.
        @Test
        void clearsEveryWritableTierAndNotOnlyTheOneThatAnswered() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, true, "from-keyring");
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            store.remove(ANTHROPIC);

            assertThat(keyring.erased).isTrue();
            assertThat(file.erased).isTrue();
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.Absent());
        }

        // An unavailable tier can still hold a value written before the machine changed, so it is
        // cleared regardless of whether a save would pick it.
        @Test
        void clearsATierThatCouldNotHaveTakenTheSave() {
            final var keyring = writableTier(SecretTierKind.KEYRING, 100, false, "from-keyring");
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            store.remove(ANTHROPIC);

            assertThat(keyring.erased).isTrue();
        }

        @Test
        void leavesACredentialNamedByAnEnvironmentVariableWorking() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier("from-environment"), file);

            store.remove(ANTHROPIC);

            assertThat(file.erased).isTrue();
            assertThat(store.secret(ANTHROPIC)).contains("from-environment");
        }

        // The tier below the failing one is the one still holding a credential. Stopping at the
        // first refusal therefore leaves behind exactly what the caller asked to be rid of.
        @Test
        void aTierRefusingToClearDoesNotStopTheTiersBelowItFromBeingCleared() {
            final var keyring = new EraseRefusingTier();
            final var file = writableTier(SecretTierKind.FILE, 0, true, "from-file");
            final SecretStore store = tieredSecretStore(environmentTier(null), keyring, file);

            assertThatThrownBy(() -> store.remove(ANTHROPIC))
                    .isInstanceOf(SecretStoreException.class)
                    .hasMessageContaining("anthropic")
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1))
                    // The refusing tier's own failure rides along suppressed. The wrapper spans
                    // tiers, so it reports the store's composition rather than the tier that threw.
                    .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                            .isEqualTo(SecretStoreException.Tier.STORE));
            assertThat(file.erased).isTrue();
        }

        // Two refusals rather than one, so the reported failure has to carry each of them rather
        // than the first or the last.
        @Test
        void everyTierThatRefusedIsCarriedByTheOneReportedFailure() {
            final SecretStore store = tieredSecretStore(environmentTier(null),
                    new EraseRefusingTier(), new EraseRefusingTier());

            assertThatThrownBy(() -> store.remove(ANTHROPIC))
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(2));
        }

        @Test
        void removingWhenNothingIsStoredIsNotAnError() {
            final var file = writableTier(SecretTierKind.FILE, 0, true, null);
            final SecretStore store = tieredSecretStore(environmentTier(null), file);

            store.remove(ANTHROPIC);

            assertThat(file.erased).isTrue();
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.Absent());
        }
    }

    // Real tiers here rather than fakes, unlike the rest of this file. What these prove is which
    // tiers get registered and in what order, and a fake would be the answer rather than the check.
    //
    // The OS name is named rather than read from the machine, so these cases mean the same thing on
    // every runner. A platform with no credential-store binding is the configuration that leaves
    // the file tier answering, which is what all but the last of them are about.
    @Nested
    class ForMachine {

        private static final String NO_KEYRING = "FreeBSD";

        @Test
        void storesThroughTheFileTierInTheDirectoryItWasHanded(@TempDir final Path secrets) {
            final SecretStore store = TieredSecretStore.forMachine(_ -> null, NO_KEYRING, secrets);

            store.save(ANTHROPIC, "sk-synthetic-0001");

            assertThat(secrets.resolve("anthropic.key")).exists();
            assertThat(store.secret(ANTHROPIC)).contains("sk-synthetic-0001");
            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InFile());
        }

        @Test
        void putsTheEnvironmentAheadOfTheStoredFile(@TempDir final Path secrets) {
            final SecretStore store = TieredSecretStore.forMachine(
                    Map.of("ANTHROPIC_API_KEY", "from-environment")::get, NO_KEYRING, secrets);
            store.save(ANTHROPIC, "sk-synthetic-0001");

            assertThat(store.secret(ANTHROPIC)).contains("from-environment");
            assertThat(store.status(ANTHROPIC))
                    .isEqualTo(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"));
        }

        // A machine whose platform offers no credential store is still a machine that can keep a
        // credential. The registration has to add the file tier unconditionally for that to hold.
        @Test
        void registersAWritableTierEvenWhereThePlatformOffersNoCredentialStore(
                @TempDir final Path secrets) {
            final SecretStore store = TieredSecretStore.forMachine(_ -> null, NO_KEYRING, secrets);

            store.save(ANTHROPIC, "sk-synthetic-0001");

            assertThat(store.status(ANTHROPIC)).isEqualTo(new SecretStatus.InFile());
        }

        // Where the platform does offer one, the save has to reach it rather than the file. This is
        // the one case in this class whose answer depends on the machine underneath it. So it runs
        // only on the platforms whose credential store has a binding.
        //
        // On Windows a binding implies a working store. On Linux it does not. A machine can carry
        // libsecret with no Secret Service on the bus. The save then lands in the file tier and
        // fails the assertion below. That is deliberate rather than overlooked, and it matches the
        // stance the binding's own test takes. A Linux machine expected to carry a keyring and not
        // carrying one should go red rather than quietly skip.
        //
        // It writes into the real credential store of whoever runs it, and then clears what it
        // wrote. Going through the tier means the entry name is built from a provider id, and those
        // are lower case by the rule that validates one. The capitalised name the binding's own test
        // hides behind is therefore unavailable here. Nothing structurally reserves this name: a
        // plugin may supply its own provider id, which SecretId contemplates by design.
        //
        // So it refuses to touch an occupied name rather than trusting the name to be free. A
        // machine already holding something there skips instead, which loses a test on a machine
        // nobody has rather than destroying a credential on one somebody does. That is not the
        // skipped-configuration blind spot: what is skipped is foreign data, not a code path.
        @Test
        @EnabledOnOs({OS.WINDOWS, OS.LINUX})
        void savesThroughTheCredentialStoreWherePlatformOffersOne(@TempDir final Path secrets) {
            final var fixture = new SecretId("sluice-fixture-tier-routing", "SLUICE_FIXTURE_TIER_ROUTING");
            final SecretStore store = TieredSecretStore.forMachine(
                    _ -> null, System.getProperty("os.name"), secrets);
            assumeThat(store.status(fixture))
                    .as("refusing to overwrite a credential this machine already holds")
                    .isEqualTo(new SecretStatus.Absent());

            store.save(fixture, "sk-synthetic-0001");
            try {
                assertThat(store.status(fixture)).isEqualTo(new SecretStatus.InKeyring());
                assertThat(store.secret(fixture)).contains("sk-synthetic-0001");
                assertThat(secrets.resolve("sluice-fixture-tier-routing.key")).doesNotExist();
            } finally {
                store.remove(fixture);
            }
        }
    }

    private static SecretStore tieredSecretStore(final SecretTier environment,
            final WritableSecretTier... writable) {
        return new TieredSecretStore(environment, List.of(writable));
    }

    private static SecretTier environmentTier(final @Nullable String value) {
        return new SecretTier() {
            @Override
            public Optional<String> read(final SecretId id) {
                return Optional.ofNullable(value);
            }

            @Override
            public boolean holds(final SecretId id) {
                return value != null;
            }

            @Override
            public SecretStatus statusWhenAnswering(final SecretId id) {
                return new SecretStatus.InEnvironment(id.environmentVariable());
            }
        };
    }

    private static FakeWritableTier writableTier(final SecretTierKind kind, final int precedence,
            final boolean available, final @Nullable String held) {
        return new FakeWritableTier(kind, precedence, available, held);
    }

    private static WritableSecretTier unreadableTier() {
        return new BrokenTier() {
            @Override
            public Optional<String> read(final SecretId id) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "the credential store on this machine cannot be read");
            }

            @Override
            public boolean holds(final SecretId id) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "the credential store on this machine cannot be read");
            }
        };
    }

    private enum SecretTierKind {
        KEYRING, FILE
    }

    private static final class FakeWritableTier implements WritableSecretTier {

        private final SecretTierKind kind;
        private final int precedence;
        private final boolean available;
        private final List<String> written = new ArrayList<>();
        private @Nullable String held;
        private boolean erased;

        private FakeWritableTier(final SecretTierKind kind, final int precedence,
                final boolean available, final @Nullable String held) {
            this.kind = kind;
            this.precedence = precedence;
            this.available = available;
            this.held = held;
        }

        @Override
        public Optional<String> read(final SecretId id) {
            return Optional.ofNullable(this.held);
        }

        @Override
        public boolean holds(final SecretId id) {
            return this.held != null;
        }

        @Override
        public SecretStatus statusWhenAnswering(final SecretId id) {
            return this.kind == SecretTierKind.KEYRING
                    ? new SecretStatus.InKeyring()
                    : new SecretStatus.InFile();
        }

        @Override
        public boolean available() {
            return this.available;
        }

        @Override
        public int precedence() {
            return this.precedence;
        }

        @Override
        public void write(final SecretId id, final String secret) {
            this.written.add(secret);
            this.held = secret;
        }

        @Override
        public void erase(final SecretId id) {
            this.erased = true;
            this.held = null;
        }
    }

    // The base for the tiers that exist to fail one particular way. Each subclass overrides only
    // the method its test is about, so the failure under test is the only thing unusual about it.
    private abstract static class BrokenTier implements WritableSecretTier {

        @Override
        public Optional<String> read(final SecretId id) {
            return Optional.empty();
        }

        @Override
        public boolean holds(final SecretId id) {
            return false;
        }

        @Override
        public SecretStatus statusWhenAnswering(final SecretId id) {
            return new SecretStatus.InKeyring();
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int precedence() {
            return 100;
        }

        @Override
        public void write(final SecretId id, final String secret) {
        }

        @Override
        public void erase(final SecretId id) {
        }
    }

    // Answers that it holds a credential and refuses to produce one, which is what a locked
    // credential store looks like to anything that only needs a label.
    private static final class RetrievalRefusingTier extends BrokenTier {

        @Override
        public Optional<String> read(final SecretId id) {
            throw new AssertionError("the status route must not retrieve the credential");
        }

        @Override
        public boolean holds(final SecretId id) {
            return true;
        }
    }

    private static final class EraseRefusingTier extends BrokenTier {

        @Override
        public void erase(final SecretId id) {
            throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                    "this credential store refused to clear the credential");
        }
    }
}
