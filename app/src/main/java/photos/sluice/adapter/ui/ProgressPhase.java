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
 * <p>{@code partDone} describes the unit after {@code current}, which is the one being worked on.
 * It is the only reading that moves while a single large file is written. It drops back to zero as
 * that file lands and the count steps.
 *
 * <p>{@code started} is false for a phase the job has announced but not reached.
 *
 * @param label {@link String} the phase's name, as the engine that reported it wrote it
 * @param current int units done so far
 * @param total int units the phase said it had, zero until it first ticks
 * @param started boolean whether the job has reached this phase
 * @param finished boolean whether the phase has ended
 * @param partDone double how much of the unit now being worked on is done, from 0 to 1
 */
public record ProgressPhase(String label, int current, int total, boolean started, boolean finished,
                            double partDone) {
}
