package photos.sluice.application.port.out;

/**
 * What one call to a provider will consume on the way in, asked before any call is made.
 *
 * <p>Only the input half. Output cannot be counted before it is generated, and on a model where
 * reasoning depth is chosen per request it is not predictable at all.
 *
 * <p>Spending nothing and being unable to say are separate answers. Collapsing them to a zero would
 * disarm the spend ceiling for the second, and nothing would say so.
 */
public sealed interface SpendForecast {

    /**
     * The provider counted the request it would send.
     *
     * @param inputTokensPerCall how many input tokens one call carries
     */
    record Counted(long inputTokensPerCall) implements SpendForecast {
    }

    /**
     * The provider calls no model, so a run through it consumes nothing.
     */
    record NoSpend() implements SpendForecast {
    }

    /**
     * The provider spends, and could not say how much this time.
     *
     * @param reason {@link String} what stopped it answering, for a log line
     */
    record Unknown(String reason) implements SpendForecast {
    }
}
