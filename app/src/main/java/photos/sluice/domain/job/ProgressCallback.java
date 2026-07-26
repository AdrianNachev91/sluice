package photos.sluice.domain.job;

// One already-sized unit of work finished (a file sorted, a montage built, a shard applied).
// total is fixed across every call in a given run, but only known once the caller has computed it.
// Carrying it on every tick, rather than announcing it separately up front, removes the need to
// know the total before the work it describes has even started.
@FunctionalInterface
public interface ProgressCallback {

    ProgressCallback NO_OP = (_, _) -> { };

    /**
     * Reports one unit of work finished.
     *
     * @param current int units completed so far
     * @param total int total units in this run
     */
    void tick(int current, int total);
}
