package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.application.port.out.SpendLedgerPort;
import photos.sluice.domain.sift.MontageConfig;

import java.util.List;

/**
 * Works out what a prepared run is expected to consume, before any call is made.
 *
 * <p>It combines the one thing a provider can count exactly with the one thing only history can
 * answer. A provider counts what a single request carries on the way in. How often a montage needs
 * a second attempt, and how much a montage generates on the way out, come from the runs this
 * install has already recorded.
 */
final class SpendEstimator {

    private static final Logger log = LoggerFactory.getLogger(SpendEstimator.class);

    // The exact count of one shipped request, taken 2026-08-22 on the current prompt and rule set.
    // Only reached when a provider that does spend could not count this run's own request, which
    // leaves an approximate ceiling as the alternative to none at all.
    private static final long SEED_INPUT_TOKENS_PER_CALL = 5_790;

    // How far past its own expectation a run may spend before it stops and asks.
    //
    // Sized against the two ways a run legitimately costs more than expected. Every montage needing
    // its one corrective retry, rather than the one in five the seed assumes, comes to about 2.2x.
    // A single montage that answers badly and then answers expensively comes to about 3.2x on its
    // own. The warm-up below dilutes that one rather than this multiple absorbing it.
    //
    // It also absorbs the seed being one model's figures used for another. That is a fresh install's
    // first run on a model that reasons more than the seeded one. Only the output half is exposed,
    // since the input half is counted against the model actually configured. Three times a seeded
    // montage leaves about 26,800 output tokens before the arm fires, against a seed of 4,300. A
    // model would have to reason near the response cap on nearly every montage to reach that. After
    // one run the rate is the install's own, keyed by model.
    private static final int CEILING_MULTIPLE = 3;

    // Montages attempted before the token arm applies, so that one bad sheet early cannot end a
    // healthy run. Inside that window the call arm is the only bound in force. So the worst case is
    // ten calls rather than five montages' expected cost: a few hundred thousand tokens, not the
    // 56,000 a normal run reaches there.
    private static final int MONTAGES_BEFORE_ARMING = 5;

    private final SpendLedgerPort ledger;

    /**
     * Creates an estimator over the ledger it projects the output half from.
     *
     * @param ledger {@link SpendLedgerPort} the record of what past runs consumed
     */
    SpendEstimator(final SpendLedgerPort ledger) {
        this.ledger = ledger;
    }

    /**
     * Estimates what sifting prep will consume.
     *
     * @param montages how many montages the run will dispatch for
     * @param forecast {@link SpendForecast} what the provider said one call would carry
     * @param modelId {@link String} the model the run would use, or null for a provider with none
     * @param grid {@link MontageConfig} the montage grid the run was prepared at
     * @return {@link SpendEstimate} what the run is expected to consume
     */
    SpendEstimate estimate(final int montages, final SpendForecast forecast, final @Nullable String modelId,
                           final MontageConfig grid) {
        return switch (forecast) {
            case final SpendForecast.NoSpend ignored -> nothingToSpend();
            case SpendForecast.Counted(final long counted) -> this.estimateFrom(montages, counted, true, modelId, grid);
            case SpendForecast.Unknown(final String reason) -> {
                log.info("Estimating spend from the shipped seed: the provider could not count a request ({})",
                        reason);
                yield this.estimateFrom(montages, SEED_INPUT_TOKENS_PER_CALL, false, modelId, grid);
            }
        };
    }

    /**
     * Estimates what a scope would consume before anything has been prepared for it.
     *
     * <p>The input half cannot be counted here, and the difference from {@link #estimate} is why.
     * A provider counts a request, and a request carries a montage image, and no montage exists
     * until prep has run. So this is the seeded figure, which is the same answer a provider that
     * could not count would produce. It goes through the seed directly rather than through
     * {@link SpendForecast.Unknown}, whose log line would report a provider failing to answer a
     * question nothing asked it.
     *
     * <p>Montages are derived from the photos in scope, which is the count before unreviewable
     * ones are dropped. A photo that cannot render a judgeable tile never reaches a sheet, so the
     * real run has this many montages or fewer. The figure therefore leans high, which is the
     * direction a number about somebody's money should lean.
     *
     * @param photos how many photos the scope holds
     * @param spends boolean whether the configured provider calls a model at all
     * @param modelId {@link String} the model the run would use, or null for a provider with none
     * @param grid {@link MontageConfig} the montage grid the run would be prepared at
     * @return {@link SpendEstimate} what the run is expected to consume
     */
    SpendEstimate estimateBeforePreparing(final int photos, final boolean spends,
                                          final @Nullable String modelId, final MontageConfig grid) {
        if (!spends) {
            return nothingToSpend();
        }
        final int perMontage = grid.tilesPerRow() * grid.tilesPerRow();
        final int montages = Math.ceilDiv(photos, perMontage);
        return this.estimateFrom(montages, SEED_INPUT_TOKENS_PER_CALL, false, modelId, grid);
    }

    /**
     * Scales one call's input across the run and adds the output half the ledger projects.
     *
     * @param montages how many montages the run will dispatch for
     * @param inputPerCall how many input tokens one call carries
     * @param exactInput whether that figure was counted rather than taken from the seed
     * @param modelId {@link String} the model the run would use, or null for a provider with none
     * @param grid {@link MontageConfig} the montage grid the run was prepared at
     * @return {@link SpendEstimate} what the run is expected to consume
     */
    private SpendEstimate estimateFrom(final int montages, final long inputPerCall, final boolean exactInput,
                                       final @Nullable String modelId, final MontageConfig grid) {
        final History history = this.history();
        final SpendRate rate = SpendRate.from(history.entries(), modelId, grid);
        return new SpendEstimate(
                Math.round(montages * inputPerCall * rate.callsPerMontage()),
                montages * rate.outputTokensPerMontage(),
                exactInput,
                rate.fromHistory(),
                history.unreadable());
    }

    /**
     * The ceiling a run of this size may not spend past, or null when it can spend nothing.
     *
     * <p>The token arm is a multiple of what one montage was estimated to cost, charged against the
     * montages the run has actually attempted. Dividing the estimate by the same count it was built
     * over is what reduces it to one montage, so the two must agree. They do for the one caller, and
     * a wider estimate divided by a narrower count would loosen the budget in proportion.
     *
     * <p>The call arm is sized on the whole scope instead, with one correction allowed for each
     * montage in it. Its only property is that a correct run cannot reach it.
     *
     * @param montagesInScope how many montages the prep directory holds
     * @param montagesToDispatchFor how many of those the run expects to pay for, which must be the
     *        same count the estimate was built over
     * @param estimate {@link SpendEstimate} what that run is expected to consume
     * @return {@link SpendCeiling} the run's ceiling, or null when nothing will be spent
     */
    @Nullable SpendCeiling ceilingFor(final int montagesInScope, final int montagesToDispatchFor,
                                      final SpendEstimate estimate) {
        if (montagesInScope <= 0 || montagesToDispatchFor <= 0) {
            return null;
        }
        final long tokenBudgetPerMontage =
                CEILING_MULTIPLE * estimate.totalTokens() / montagesToDispatchFor;
        if (tokenBudgetPerMontage <= 0) {
            return null;
        }
        return new SpendCeiling(montagesInScope * SpendRate.MAX_CALLS_PER_MONTAGE, tokenBudgetPerMontage,
                MONTAGES_BEFORE_ARMING);
    }

    /**
     * Every run recorded so far, and whether the record could be read at all.
     *
     * <p>The ledger fails loud at its own boundary, and this is where that stops. A record of past
     * spend nobody can parse costs the estimate its projected half, which the estimate is built to
     * say out loud. It must not cost the user their sift.
     *
     * <p>Why it could not be read travels with the answer rather than only reaching the log.
     *
     * @return {@link History} the recorded runs, and whether reading them failed
     */
    private History history() {
        try {
            return new History(this.ledger.read(), false);
        } catch (final RuntimeException e) {
            log.warn("Could not read the spend ledger, so this run is estimated without history: {}",
                    e.toString());
            return new History(List.of(), true);
        }
    }

    /**
     * The estimate for a run that calls no model at all.
     *
     * @return {@link SpendEstimate} an exact zero
     */
    private static SpendEstimate nothingToSpend() {
        return new SpendEstimate(0, 0, true, false, false);
    }

    /**
     * What one read of the spend ledger came to.
     *
     * @param entries a {@link List} of {@link SpendLedgerEntry} the runs it held, empty if it could not be read
     * @param unreadable boolean whether the read failed, as opposed to finding nothing recorded
     */
    private record History(List<SpendLedgerEntry> entries, boolean unreadable) {}
}
