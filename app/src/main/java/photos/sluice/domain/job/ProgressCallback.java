package photos.sluice.domain.job;

/**
 * Reports progress one already-sized unit of work at a time: a file sorted, a montage built, a
 * shard applied.
 *
 * <p>{@code total} is fixed across every call in a given run. It is only known once the caller has
 * computed it.
 *
 * <p>Carrying {@code total} on every tick, rather than announcing it separately up front, removes
 * the need to know the total before the work has even started.
 */
@FunctionalInterface
public interface ProgressCallback {

    ProgressCallback NO_OP = (_, _) -> {
    };

    /**
     * Reports one unit of work finished.
     *
     * @param current int units completed so far
     * @param total int total units in this run
     */
    void tick(int current, int total);

    /**
     * Reports how far into the unit now being worked on the run has reached.
     *
     * <p>Says nothing unless an implementation takes it up. A caller written as a lambda promises
     * whole units and nothing else, and answering with silence is that promise kept. What is lost
     * by ignoring it is smoothness, never a count: the unit still reports itself through
     * {@link #tick} when it finishes.
     *
     * @param current int units completed before this one
     * @param total int total units in this run
     * @param partDone double how much of the current unit is done, from 0 to 1
     */
    default void partOf(final int current, final int total, final double partDone) {
    }
}
