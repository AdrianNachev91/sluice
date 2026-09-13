package photos.sluice.application.port.in;

/**
 * What a sift run is expected to consume, worked out before any call is made.
 *
 * <p>It says which half it knows, because the two halves are knowable to very different degrees.
 * The input half can be counted exactly and for free against the request that would be sent. The
 * output half cannot be counted at all before it is generated, so it is projected from what runs on
 * this install have actually cost.
 *
 * <p>An expectation rather than a worst case. The room for a run that goes badly sits in the
 * ceiling's multiple.
 *
 * @param inputTokens how many input tokens the run is expected to consume
 * @param outputTokens how many output tokens the run is expected to consume
 * @param exactInput whether the input figure was counted against the real request, rather than
 *        falling back to the shipped seed
 * @param historicOutput whether the output figure came from runs on this install, rather than from
 *        the shipped seed
 * @param historyUnreadable why there is no history, where there is none. True says the record of
 *        past runs exists and could not be read, false says nothing has been recorded yet
 */
public record SpendEstimate(long inputTokens, long outputTokens, boolean exactInput, boolean historicOutput,
                            boolean historyUnreadable) {

    /**
     * The two halves added together, which is what the ceiling's multiple applies to.
     *
     * @return long the total tokens the run is expected to consume
     */
    public long totalTokens() {
        return this.inputTokens + this.outputTokens;
    }
}
