package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * What one sift run consumed, measured in tokens and attributed to the provider and model that
 * consumed them.
 *
 * <p>Tokens rather than currency, deliberately. A price would be a per-provider obligation, so every
 * provider added later would have to ship a pricing model as well as a sieve. It would also make
 * any vendor's rate change a release trigger here. The counts below are first-party and per-request;
 * only the conversion to money is optional.
 *
 * @param inputTokens how many input tokens the run consumed
 * @param outputTokens how many output tokens the run consumed, reasoning included where the model
 *        bills it that way
 * @param providerId {@link String} the provider that ran, as it describes itself
 * @param modelId {@link String} the exact model id that ran, or null for a provider that calls no
 *        model of its own
 */
public record TokenSpend(long inputTokens, long outputTokens, String providerId, @Nullable String modelId) {

    /**
     * A spend of nothing, for a run that reached no model. Two cases: a provider whose judgements
     * arrive as ready-made shards, and a run that ended before anything was dispatched.
     *
     * @param providerId {@link String} the provider that was configured for the run
     * @return {@link TokenSpend} a zero spend attributed to that provider
     */
    public static TokenSpend none(final String providerId) {
        return new TokenSpend(0, 0, providerId, null);
    }
}
