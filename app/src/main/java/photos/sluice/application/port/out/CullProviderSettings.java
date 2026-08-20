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

    private static final CullProviderSettings UNSET = new CullProviderSettings(null, null, null);

    /**
     * The settings of a provider nobody has configured: every field unset, so every consumer falls
     * back to its own default. What a lookup answers when a provider has no block of its own.
     *
     * @return {@link CullProviderSettings} settings with no field set
     */
    public static CullProviderSettings unset() {
        return UNSET;
    }
}
