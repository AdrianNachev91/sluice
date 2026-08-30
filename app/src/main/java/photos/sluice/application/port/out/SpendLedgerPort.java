package photos.sluice.application.port.out;

import java.nio.file.Path;
import java.util.List;

/**
 * The effect boundary application services use to record what each cull run consumed, and to read
 * back the runs already recorded.
 *
 * <p>Append-only, one line per run, never replaced. The rate the estimate projects from is derived
 * from these lines rather than stored in place of them.
 *
 * <p>{@link #read} fails loud, and a caller degrades. A line this cannot parse may still be the
 * only copy of real history, so it is refused rather than skipped past. What that refusal must not
 * do is stop a sift: the caller answers that it has no history and the run proceeds.
 */
public interface SpendLedgerPort {

    /**
     * Every run recorded so far, oldest first.
     *
     * @return a {@link List} of {@link SpendLedgerEntry} the recorded runs, empty when nothing has
     *         been recorded yet
     */
    List<SpendLedgerEntry> read();

    /**
     * Appends one run to the ledger.
     *
     * @param entry {@link SpendLedgerEntry} the run to record
     */
    void append(SpendLedgerEntry entry);

    /**
     * Moves the ledger to destination, leaving nothing in its place, so the next read starts from
     * an empty one.
     *
     * <p>Moved rather than deleted. A ledger nothing can parse is still the only record of what
     * past runs cost, so a caller picks somewhere it will be found again.
     *
     * @param destination {@link Path} where the ledger is moved to, its parents created as needed
     * @return boolean true when there was a ledger to move
     */
    boolean setAside(Path destination);
}
