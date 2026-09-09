package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * Settings for one vision provider that calls a model API from inside the app. That covers which
 * model to request, an optional non-default endpoint, and how often to retry a failed transport
 * call.
 *
 * <p>Every field is nullable because a user on the external-agent provider never sets any of them.
 * A provider that cannot work without a field (the model id, say) checks it at cull time and fails
 * loud with the property name. The optional knobs fall back to provider defaults when null. The
 * app still starts for everyone else.
 *
 * @param model {@link String} the model id to request, or null when unset
 * @param endpoint {@link String} a service address other than the provider's own, or null for the
 *     provider's default
 * @param maxRetries {@link Integer} how many times a failed transport call is tried again, or null
 *     for the provider's default. Set by hand in the config file; the Settings screen never asks
 *     for it, since a good count is a transport question a user has no basis to judge, not a
 *     product one. Kept as a seam an advanced surface could still bind to, rather than reworked
 *     into one.
 */
public record CullProviderSettings(@Nullable String model, @Nullable String endpoint,
                                   @Nullable Integer maxRetries) {

    // Both are free text a user types, and neither has a natural end, so both get one. A model id is
    // a short token: the longest any provider offers today is around twenty-five characters. An
    // endpoint is a URL, held to the length every browser and proxy has treated as the practical
    // ceiling for years.
    private static final int MAX_MODEL = 100;
    private static final int MAX_ENDPOINT = 2048;

    private static final CullProviderSettings UNSET = new CullProviderSettings(null, null, null);

    /**
     * Holds the two typed fields to a length, so neither a screen nor a hand-written config file can
     * store one without end.
     *
     * @param model the model id to request, or null when unset
     * @param endpoint a non-default API endpoint, or null for the provider's own
     * @param maxRetries how many times a failed transport call is retried, or null for the default
     */
    public CullProviderSettings {
        refuseLongerThan(model, MAX_MODEL, "model id");
        refuseLongerThan(endpoint, MAX_ENDPOINT, "endpoint");
    }

    /**
     * The settings of a provider nobody has configured: every field unset, so every consumer falls
     * back to its own default. What a lookup answers when a provider has no block of its own.
     *
     * @return {@link CullProviderSettings} settings with no field set
     */
    public static CullProviderSettings unset() {
        return UNSET;
    }

    /**
     * The longest a model id may be.
     *
     * @return int the character ceiling
     */
    public static int maxModel() {
        return MAX_MODEL;
    }

    /**
     * The longest an endpoint may be.
     *
     * @return int the character ceiling
     */
    public static int maxEndpoint() {
        return MAX_ENDPOINT;
    }

    /**
     * Refuses a value past its ceiling, naming the field rather than the record.
     *
     * @param value the value to check, or null
     * @param ceiling int the most it may hold
     * @param field {@link String} what to call it in the message
     */
    private static void refuseLongerThan(final @Nullable String value, final int ceiling,
                                         final String field) {
        if (value != null && value.length() > ceiling) {
            throw new IllegalArgumentException("Provider " + field + " is longer than the " + ceiling
                    + " characters one may take: " + value.length());
        }
    }

}
