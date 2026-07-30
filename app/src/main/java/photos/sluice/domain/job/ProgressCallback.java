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
}
