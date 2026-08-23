package photos.sluice.adapter.ui;

/**
 * One phase of a running job as the desktop reads it: what it is called, how far it has got, and
 * whether it has ended.
 *
 * <p>A phase that ends without ever ticking is finished with a total of zero. That is a real
 * outcome rather than a missing reading, and it is why {@code finished} is carried rather than
 * inferred from the counts. A sort with nothing to move and a sort still on its first file both
 * read 0 of 0 otherwise.
 *
 * @param label {@link String} the phase's name, as the engine that reported it wrote it
 * @param current int units done so far
 * @param total int units the phase said it had, zero until it first ticks
 * @param finished boolean whether the phase has ended
 */
public record ProgressPhase(String label, int current, int total, boolean finished) {
}
