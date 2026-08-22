package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One cull run, as the spend ledger records it. However it ended: a run that was cancelled,
 * stopped at its ceiling or abandoned was still billed for whatever it sent.
 *
 * @param endedAt {@link Instant} when the run ended
 * @param scope {@link String} the run's scope tag, the same string that names its prep directory
 * @param providerId {@link String} the provider that ran
 * @param modelId {@link String} the exact model id that ran, or null for a provider that calls no model
 * @param tileSize the pixel size of each montage tile
 * @param tilesPerRow how many tiles formed a montage row
 * @param montagesCulled how many montages the run obtained fresh judgement for
 * @param montagesSkipped how many montages the run got no judgement for
 * @param apiCalls how many calls the run made against the provider's model
 * @param inputTokens how many input tokens the run consumed
 * @param outputTokens how many output tokens the run consumed
 * @param ending {@link RunEnding} how the run ended
 */
public record SpendLedgerEntry(Instant endedAt, String scope, String providerId, @Nullable String modelId,
                               int tileSize, int tilesPerRow, int montagesCulled, int montagesSkipped,
                               int apiCalls, long inputTokens, long outputTokens, RunEnding ending) {

    /**
     * Refuses a count that cannot describe a run.
     *
     * <p>This is the boundary a hand-edited or half-written line has to cross, and refusing here is
     * what keeps the arithmetic downstream honest. A negative token count would otherwise make a
     * run's estimate negative and its ceiling impossible to build. That refusal would reach the user
     * as a sift that will not start.
     *
     * @param endedAt {@link Instant} when the run ended
     * @param scope {@link String} the run's scope tag
     * @param providerId {@link String} the provider that ran
     * @param modelId {@link String} the exact model id that ran, or null
     * @param tileSize the pixel size of each montage tile
     * @param tilesPerRow how many tiles formed a montage row
     * @param montagesCulled how many montages the run obtained fresh judgement for
     * @param montagesSkipped how many montages the run got no judgement for
     * @param apiCalls how many calls the run made
     * @param inputTokens how many input tokens the run consumed
     * @param outputTokens how many output tokens the run consumed
     * @param ending {@link RunEnding} how the run ended
     */
    public SpendLedgerEntry {
        if (tileSize <= 0 || tilesPerRow <= 0) {
            throw new IllegalArgumentException("a run's montage grid must be positive: tileSize=%d, tilesPerRow=%d"
                    .formatted(tileSize, tilesPerRow));
        }
        if (montagesCulled < 0 || montagesSkipped < 0 || apiCalls < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(("a run's counts cannot be negative: montagesCulled=%d, "
                    + "montagesSkipped=%d, apiCalls=%d, inputTokens=%d, outputTokens=%d")
                    .formatted(montagesCulled, montagesSkipped, apiCalls, inputTokens, outputTokens));
        }
    }
}
