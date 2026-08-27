package photos.sluice.adapter.cli;

import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;

import java.util.List;
import java.util.Optional;

// A machine holding no credential anywhere, for the tests that reach a store only because
// something on the way to them needs one.
//
// The two writing methods refuse rather than doing nothing. A test that reached one would be
// exercising a path no command on this surface has, and would pass while proving that.
record NoSecrets(List<SecretHolding> holdings) implements SecretStore {

    NoSecrets() {
        this(List.of());
    }

    @Override
    public Optional<String> secret(final SecretId id) {
        return Optional.empty();
    }

    @Override
    public SecretStatus status(final SecretId id) {
        return new SecretStatus.Absent();
    }

    @Override
    public List<SecretHolding> holdings(final SecretId id) {
        return this.holdings;
    }

    @Override
    public Optional<SecretStatus.StoredLocation> whereASaveWouldStoreIt() {
        return Optional.empty();
    }

    @Override
    public void save(final SecretId id, final String secret) {
        throw new UnsupportedOperationException("no command on this surface stores a credential");
    }

    @Override
    public void remove(final SecretId id) {
        throw new UnsupportedOperationException("no command on this surface clears a credential");
    }
}
