package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * Settings for a vision provider that calls a model API from inside the app. That covers which
 * model to request, an optional non-default endpoint, whether the model may reason before
 * answering, and how often to retry a failed transport call.
 *
 * <p>Every field is nullable because a user on the external-agent provider never sets any of them.
 * A provider that cannot work without a field (the model id, say) checks it at cull time and fails
 * loud with the property name. The optional knobs fall back to provider defaults when null. The
 * app still starts for everyone else.
 */
public record CullProviderSettings(@Nullable String model, @Nullable String endpoint,
                                   @Nullable Boolean thinking, @Nullable Integer maxRetries) {
}
