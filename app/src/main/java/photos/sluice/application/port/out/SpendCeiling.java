package photos.sluice.application.port.out;

/**
 * The bound a sift run may not spend past, handed to the provider that does the spending.
 *
 * <p>Two arms, because they fail differently. {@code maxCalls} is a property of the run rather than
 * a setting. Prep writes the montage index before anything is sent, so the amount of work is finite
 * and known in advance. No legitimate run can exceed it, and what it catches is a loop that has
 * stopped following the index.
 *
 * <p>{@code tokenBudgetPerMontage} catches the other shape, where the call count is correct and
 * each call costs far more than it should. It is a multiple of what one montage was estimated to
 * cost, and a run is allowed that much for every montage it has actually attempted.
 *
 * <p>Per montage rather than per run. A bound on the whole run's total is only crossed once the run
 * has already spent several times what it was expected to cost. On a large scope that is most of
 * the money. Measured against the work done so far, the same multiple stops a runaway within a
 * montage or two whatever the scope's size.
 *
 * <p>{@code armAfterMontages} is what lets that multiple be tight. A sheet that answers badly and
 * needs its correction can cost three times what a montage is expected to. A bound that has to
 * clear that from the very first montage sits three times looser for the rest of the run. Waiting
 * for a handful of montages leaves a bounded amount unguarded and tightens everything after it.
 *
 * <p>Tokens rather than money throughout. A currency bound needs a price table, and a stale one
 * fails in the direction of spending more than the user allowed.
 *
 * @param maxCalls the most calls the run may make in total
 * @param tokenBudgetPerMontage the most tokens, input and output together, the run may consume for
 *        each montage it attempts
 * @param armAfterMontages how many montages the run must attempt before the token arm applies
 */
public record SpendCeiling(int maxCalls, long tokenBudgetPerMontage, int armAfterMontages) {

    /**
     * Validates that the two bounds are positive and the warm-up is not negative.
     *
     * @param maxCalls the most calls the run may make in total
     * @param tokenBudgetPerMontage the most tokens the run may consume per attempted montage
     * @param armAfterMontages how many montages the run must attempt before the token arm applies
     */
    public SpendCeiling {
        if (maxCalls <= 0 || tokenBudgetPerMontage <= 0 || armAfterMontages < 0) {
            throw new IllegalArgumentException(("maxCalls and tokenBudgetPerMontage must be positive and "
                    + "armAfterMontages must not be negative: maxCalls=%d, tokenBudgetPerMontage=%d, "
                    + "armAfterMontages=%d")
                    .formatted(maxCalls, tokenBudgetPerMontage, armAfterMontages));
        }
    }

    /**
     * Whether a run that has attempted this many montages, and consumed this many tokens doing it,
     * has passed the bound.
     *
     * @param montagesAttempted how many montages the run has dispatched a call for
     * @param tokensConsumed how many tokens it consumed doing that
     * @return boolean true when the run has spent past what that much work allows
     */
    public boolean tokensExhausted(final int montagesAttempted, final long tokensConsumed) {
        return montagesAttempted >= this.armAfterMontages
                && tokensConsumed > (long) montagesAttempted * this.tokenBudgetPerMontage;
    }
}
