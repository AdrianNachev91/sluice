package photos.sluice.application.port.out;

import java.util.Set;

/**
 * Thrown when the configured vision provider names no provider this build has registered.
 *
 * <p>It is a config mistake the user can go and correct, not a failure this app has no reading for.
 */
public final class UnrecognisedProviderException extends IllegalStateException {

    private final transient String provider;
    private final transient Set<String> registered;

    /**
     * Creates the exception, naming the value that named nothing and what is registered instead.
     *
     * @param provider {@link String} the configured value that matched no provider
     * @param registered a {@link Set} of {@link String} every provider id this build has
     */
    public UnrecognisedProviderException(final String provider, final Set<String> registered) {
        super("No vision provider registered under the id '" + provider + "'. Registered: " + registered);
        this.provider = provider;
        this.registered = Set.copyOf(registered);
    }

    /**
     * The configured value that matched no provider.
     *
     * @return {@link String} the value
     */
    public String provider() {
        return this.provider;
    }

    /**
     * Every provider id this build has registered.
     *
     * @return a {@link Set} of {@link String} the registered ids
     */
    public Set<String> registered() {
        return this.registered;
    }
}
