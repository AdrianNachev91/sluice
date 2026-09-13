package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.domain.sift.MontageConfig;

import java.util.List;
import java.util.Set;

/**
 * What a montage costs, as this install has actually seen it.
 *
 * <p>Two numbers rather than one, because only one of them can be measured live. An exact count of
 * a single request is free to obtain, so what the input half is missing is how often a montage takes
 * a second attempt. The output half cannot be counted before it is generated at all, so it is
 * carried whole.
 *
 * <p>Keyed by model and by both montage settings when it is derived, since a rate measured at one
 * grid does not transfer to another. Tile size moves image tokens per photo with its square. Tiles
 * per row leaves those flat and instead spreads the system prompt, paid once a montage, over more
 * photos. A user who changes either simply has no history for the new shape and falls back to the
 * seed.
 *
 * @param callsPerMontage how many calls a montage takes on average, between one and the most a
 *        montage is allowed
 * @param outputTokensPerMontage how many output tokens a montage produces on average
 * @param fromHistory whether these came from this install's own runs rather than the shipped seed
 */
record SpendRate(double callsPerMontage, long outputTokensPerMontage, boolean fromHistory) {

    private static final Logger log = LoggerFactory.getLogger(SpendRate.class);

    // Measured 2026-08-22 over ten real 25-photo sheets at the shipped 5x5/224px grid and rule set,
    // on claude-sonnet-5: 12 calls and 43,000 output tokens. A fresh install has no history for any
    // model, so one model's figures are the only seed available. Output in particular runs higher on
    // a model that reasons more, and the first run of its own replaces this.
    //
    // The call figure is 12 over 10 sheets, so it already prices in the two that needed a corrective
    // retry. Anything that adds a reason to refuse a shard adds retries, and moves this. Whoever
    // does that re-measures both figures here, since only a run with the new refusal in force can
    // say what it costs.
    private static final double SEED_CALLS_PER_MONTAGE = 1.2;
    private static final long SEED_OUTPUT_TOKENS_PER_MONTAGE = 4_300;

    // A montage is one attempt plus at most one correction.
    static final int MAX_CALLS_PER_MONTAGE = 2;

    // The most output one call returns. This is the cap the app itself asks for, so it is a property
    // of the request rather than of any vendor. A provider that ignored it would need this revisited.
    private static final long MAX_RESPONSE_TOKENS = 16_384;

    // The two together bound what one montage can produce. A line above it is corrupt rather than
    // expensive, and leaving it in would raise the estimate. A multiple over a raised estimate stops
    // firing, so one bad line would disarm the guard this rate feeds. The check is against a run's
    // average, since a run records totals and never its individual montages.
    private static final long MAX_CREDIBLE_OUTPUT_TOKENS_PER_MONTAGE = MAX_CALLS_PER_MONTAGE * MAX_RESPONSE_TOKENS;

    // The two endings where the vision pass ran to completion, so what the run consumed is what that
    // much work costs. Every other ending is a partial run, and a partial run's average is not a
    // rate. The ceiling-stopped ending is why this matters rather than being tidiness. Without it,
    // the run a runaway was stopped on teaches the next run's ceiling that a runaway is normal. On a
    // fresh install that one line is the whole history.
    private static final Set<RunEnding> COMPLETED = Set.of(RunEnding.APPLIED, RunEnding.BLOCKED);

    /**
     * The shipped rate, for an install with no run of its own to go on yet.
     *
     * @return {@link SpendRate} the seed rate
     */
    static SpendRate seed() {
        return new SpendRate(SEED_CALLS_PER_MONTAGE, SEED_OUTPUT_TOKENS_PER_MONTAGE, false);
    }

    /**
     * The rate this install's own completed runs imply for one model at one montage grid, or the
     * seed when none of them apply.
     *
     * @param entries a {@link List} of {@link SpendLedgerEntry} every run recorded so far
     * @param modelId {@link String} the model a run would use, or null for a provider with none
     * @param grid {@link MontageConfig} the montage grid a run would use
     * @return {@link SpendRate} the derived rate, or the seed
     */
    static SpendRate from(final List<SpendLedgerEntry> entries, final @Nullable String modelId,
                          final MontageConfig grid) {
        final List<SpendLedgerEntry> applicable = entries.stream()
                .filter(entry -> COMPLETED.contains(entry.ending()))
                .filter(entry -> modelId != null && modelId.equals(entry.modelId()))
                .filter(entry -> entry.tileSize() == grid.tileSize() && entry.tilesPerRow() == grid.tilesPerRow())
                .filter(entry -> entry.montagesSifted() > 0)
                .toList();
        final List<SpendLedgerEntry> matching = applicable.stream().filter(SpendRate::credible).toList();
        if (matching.isEmpty()) {
            // Said out loud only when runs that should have answered were refused as impossible.
            // Every other empty result is a state this class documents as ordinary: no runs yet, a
            // changed grid, or nothing but partial runs. Those resolve on the next completed run,
            // while a line describing a run nothing could produce stays and silently seeds forever.
            if (!applicable.isEmpty()) {
                log.info("Every recorded run for {} at {}x{} has counts that do not describe a real run, "
                        + "so this estimate uses the shipped seed", modelId, grid.tileSize(), grid.tilesPerRow());
            }
            return seed();
        }
        final long montages = matching.stream().mapToLong(SpendLedgerEntry::montagesSifted).sum();
        final long calls = matching.stream().mapToLong(SpendLedgerEntry::apiCalls).sum();
        final long output = matching.stream().mapToLong(SpendLedgerEntry::outputTokens).sum();
        return new SpendRate((double) calls / montages, output / montages, true);
    }

    /**
     * Whether a recorded run could have happened as recorded.
     *
     * <p>A completed run judged every montage it dispatched for, and a montage takes one call or
     * two. So its calls sit between its judged montages and twice them, and its output cannot
     * exceed the response cap on every one of those calls. A line outside either bound describes a
     * run nothing can produce.
     *
     * @param entry {@link SpendLedgerEntry} the recorded run to judge
     * @return boolean true when the run's own counts are consistent with each other
     */
    private static boolean credible(final SpendLedgerEntry entry) {
        final long montages = entry.montagesSifted();
        return entry.apiCalls() >= montages
                && entry.apiCalls() <= montages * MAX_CALLS_PER_MONTAGE
                && entry.outputTokens() <= montages * MAX_CREDIBLE_OUTPUT_TOKENS_PER_MONTAGE;
    }
}
