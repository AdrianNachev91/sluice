package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import photos.sluice.application.port.out.SecretStoreException;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The only proof the binding works, and it needs a real Secret Service on the session bus. Every
// assumption in that class is about what a native function does with the bytes handed to it, and
// nothing but the function itself can answer it. A struct field at the wrong offset compiles. So
// does a variadic call started at the wrong argument, and an error read from the wrong place. All
// three pass a test written against a double.
//
// The OS guard names what the class can run on. It deliberately does not guard on whether a Secret
// Service answers. Whether one can be reached headlessly is the unknown this class exists to
// answer, and an assumption on it would skip the machine being asked. A Linux machine with no
// Secret Service fails these tests rather than skipping them.
//
// Entries land in the real keyring of whatever machine runs this. They are named apart from
// anything the app itself can store, and removed again afterwards. A provider id is lower case by
// the rule validating one, so a capitalised name is unreachable rather than merely unlikely. The
// schema scopes every lookup to entries this app stored.
//
// Every case carries a timeout, on its own thread so the clock can actually fire. A libsecret
// synchronous call is capable of never returning. An internal precondition failure abandons the
// operation without completing it, and the calling thread waits for an answer that is not coming.
// Nothing in a native downcall can be interrupted, so the timeout abandons the thread rather than
// unwinding it. That still ends the suite with a named failure instead of a runner held until the
// job's own limit.
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LibsecretServiceTest {

    private static final String NAME_PREFIX = "SluiceFixture:";
    private static final String NAME = NAME_PREFIX + "round-trip";
    private static final String NEVER_STORED = NAME_PREFIX + "never-stored";
    private static final String LABEL = "Sluice test fixture";

    // Binding here rather than through the selector is deliberate. The selector turns a refusal
    // into an absent tier, which would quietly reduce this whole class to a no-op if the binding
    // were ever built wrong. Calling the binding directly means that fails the build instead.
    private final LinuxSecretService secrets = LibsecretService.open();

    @AfterEach
    void removeWhateverWasStored() {
        this.secrets.delete(NAME);
    }

    @Test
    void storesACredentialAndReadsTheSameValueBack() {
        this.secrets.write(NAME, LABEL, "sk-synthetic-0001");

        assertThat(this.secrets.read(NAME)).contains("sk-synthetic-0001");
    }

    // The value crosses the bus as bytes and lives here as text, so the encode and the decode have
    // to agree. A credential outside the ASCII range is what tells them apart. One that stays
    // inside it would survive a mismatch in the low byte of every character.
    @Test
    void storesACredentialCarryingCharactersOutsideAscii() {
        this.secrets.write(NAME, LABEL, "sk-synthetic-éüß-0001");

        assertThat(this.secrets.read(NAME)).contains("sk-synthetic-éüß-0001");
    }

    @Test
    void replacesAnEntryTheServiceAlreadyHeld() {
        this.secrets.write(NAME, LABEL, "sk-synthetic-0001");
        this.secrets.write(NAME, LABEL, "sk-synthetic-0002");

        assertThat(this.secrets.read(NAME)).contains("sk-synthetic-0002");
    }

    // A missing entry must arrive as an answer rather than a fault. Getting it wrong turns every
    // read on a fresh install into a failure.
    @Test
    void answersWithNothingForAnEntryTheServiceDoesNotHold() {
        assertThat(this.secrets.read(NEVER_STORED)).isEmpty();
    }

    // The existence check is its own native call with its own descriptor, so the round-trip above
    // proves nothing about it. It is also the call availability probes and status labels lean on.
    @Test
    void reportsWhetherAnEntryExistsWithoutFailing() {
        assertThat(this.secrets.holds(NEVER_STORED)).isFalse();

        this.secrets.write(NAME, LABEL, "sk-synthetic-0001");

        assertThat(this.secrets.holds(NAME)).isTrue();
    }

    @Test
    void removesAStoredEntry() {
        this.secrets.write(NAME, LABEL, "sk-synthetic-0001");

        this.secrets.delete(NAME);

        assertThat(this.secrets.read(NAME)).isEmpty();
    }

    // The service reports "nothing removed" for a missing entry rather than an error, and the
    // binding must read that as the ordinary outcome. The store above clears every writable tier
    // on any removal, which makes this the ordinary case rather than an edge one.
    @Test
    void removingAnEntryTheServiceDoesNotHoldIsNotAFailure() {
        this.secrets.delete(NEVER_STORED);
    }

    // A store into a collection that does not exist is the refusal a working service gives on
    // demand. The reads and removals search every collection and name none, so the same service
    // refuses nothing else here. The tier field is asserted because a surface branches on it, and
    // this is the one place the real service's refusal path can be watched carrying it.
    //
    // The underscores in this name are load-bearing, and the object-path shape is not. A name
    // reaches the service inside an object path, whose elements may hold only letters, digits and
    // underscores. A hyphen makes that path invalid, the library asserts, and the call never
    // returns at all. That wedged a CI runner until the run was cancelled by hand, which is why
    // this class carries a timeout.
    //
    // A path is used rather than an alias only because this one has to name a collection that
    // cannot exist. An alias made of legal characters would work equally well as a provocation.
    @Test
    void reportsARefusalGivenToAStore() {
        final LinuxSecretService noSuchCollection = LibsecretService.open(
                "/org/freedesktop/secrets/collection/sluice_fixture_no_such_collection");

        assertThatThrownBy(() -> noSuchCollection.write(NAME, LABEL, "sk-synthetic-0001"))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining(NAME)
                .satisfies(thrown -> assertThat(((SecretStoreException) thrown).tier())
                        .isEqualTo(SecretStoreException.Tier.KEYRING));
    }
}
