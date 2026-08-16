package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;

import java.util.Optional;
import java.util.function.Function;

/**
 * Reads a credential from an environment variable, the explicit override that outranks anything
 * stored on the machine.
 *
 * <p>The environment arrives as an injected lookup rather than as a static call, which is what makes
 * the override testable at all. A lookup rather than a map, because the two are not the same thing
 * on Windows. The map {@code System.getenv()} returns is case-sensitive, while the platform resolves
 * a variable by name without regard to case. Deferring to the platform's own resolver is what keeps
 * a key set as {@code Anthropic_Api_Key} working there, and keeps it ignored where an environment
 * really is case-sensitive.
 *
 * <p>A variable set to blank counts as unset. Someone clearing a variable in a shell without
 * unsetting it means to turn the override off, not to hand the app an empty credential.
 *
 * <p>What a read answers is the variable's value stripped of surrounding whitespace. A value pasted
 * into a shell profile carries whatever came with it, and a provider counts that as part of the key.
 */
class EnvironmentSecretTier implements SecretTier {

    private final Function<String, @Nullable String> environment;

    /**
     * Creates the tier over the given environment lookup.
     *
     * @param environment a {@link Function} resolving a variable name to its value, answering null
     *         when the environment does not name it
     */
    EnvironmentSecretTier(final Function<String, @Nullable String> environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> read(final SecretId id) {
        return Optional.ofNullable(this.environment.apply(id.environmentVariable()))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    @Override
    public boolean holds(final SecretId id) {
        return this.read(id).isPresent();
    }

    @Override
    public SecretStatus.Location location(final SecretId id) {
        return new SecretStatus.InEnvironment(id.environmentVariable());
    }
}
