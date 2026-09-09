package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the dashboard draws while a run works, chosen from a {@link RunProgressPresenter} and
 * carrying only display-ready values. The view reads fields off this and decides nothing about what
 * they mean.
 *
 * @param heading {@link String} what is running, named after the work the user chose
 * @param scope {@link String} what this run covers, written out
 * @param autoStartedNote what the screen says about a run nobody pressed for, or null where the
 *     reader started it themselves
 * @param phases a {@link List} of {@link PhaseBar} one bar per phase reported, oldest first
 * @param waiting {@link String} what to say before the first phase arrives, or null once one has
 * @param cancelLabel {@link String} what the cancel button says
 * @param cancelPressable boolean whether it can be pressed
 * @param cancelling what the screen says about a cancellation already asked for, or null while
 *     none has been
 * @param reservedBars int how many bars' worth of room to hold from the first frame, so nothing
 *     below them moves as each phase arrives
 */
public record RunProgressView(String heading, String scope, @Nullable String autoStartedNote,
                              List<PhaseBar> phases,
                              @Nullable String waiting, String cancelLabel, boolean cancelPressable,
                              @Nullable String cancelling, int reservedBars) {

    /**
     * Defensively copies the mutable collection component.
     *
     * @param heading {@link String} what is running
     * @param scope {@link String} what this run covers
     * @param autoStartedNote what the screen says about a run nobody pressed for, or null
     * @param phases a {@link List} of {@link PhaseBar} one bar per phase reported
     * @param waiting {@link String} what to say before the first phase arrives, or null
     * @param cancelLabel {@link String} what the cancel button says
     * @param cancelPressable boolean whether it can be pressed
     * @param cancelling what the screen says about a cancellation already asked for, or null
     * @param reservedBars int how many bars' worth of room to hold
     */
    public RunProgressView {
        phases = List.copyOf(phases);
    }

    /**
     * One phase of the running job, as a bar.
     *
     * <p>{@code fraction} is only meaningful where {@code measured} is true.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what the phase is called
     * @param counts what it has done of what it has to do, or null where it cannot say
     * @param fraction double how far along it is, from 0 to 1
     * @param measured boolean whether this phase knows how much work it has
     * @param started boolean whether the job has reached it, false for one still ahead
     * @param finished boolean whether it has ended
     * @param cutShort boolean whether it gave up part way rather than working through to its end
     * @param completedAllWork boolean whether it can be said to have done all its work. False for a
     *     phase on a run the reader stopped, even one that reported no giving up of its own
     */
    public record PhaseBar(String id, String label, @Nullable String counts, double fraction,
                           boolean measured, boolean started, boolean finished, boolean cutShort,
                           boolean completedAllWork) {

        /**
         * A bar claiming neither of the two endings.
         *
         * @param id {@link String} the control's id
         * @param label {@link String} what the phase is called
         * @param counts what it has done of what it has to do, or null where it cannot say
         * @param fraction double how far along it is, from 0 to 1
         * @param measured boolean whether this phase knows how much work it has
         * @param started boolean whether the job has reached it
         * @param finished boolean whether it has ended
         */
        public PhaseBar(final String id, final String label, final @Nullable String counts,
                        final double fraction, final boolean measured, final boolean started,
                        final boolean finished) {
            this(id, label, counts, fraction, measured, started, finished, false, false);
        }
    }
}
