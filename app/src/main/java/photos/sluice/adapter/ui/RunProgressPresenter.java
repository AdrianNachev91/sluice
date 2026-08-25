package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;
import photos.sluice.domain.imports.ImportKind;

import java.util.List;
import java.util.Locale;

/**
 * Decides what the dashboard shows while a run works.
 *
 * <p>Holds no identity for the run. What is running, what it covers and whether a stop has been
 * asked for all belong to {@link RunLauncherPresenter}, which owns the job's whole life. It hands
 * them in. What this holds is the port a running job reports its phases to.
 */
class RunProgressPresenter {

    private static final String STARTING = "Starting...";
    private static final String CANCEL = "Cancel";
    // The button itself reports, rather than greying and leaving a line below to say what happened
    // to the press. A dead button still reading Cancel is a press that looks like it missed.
    private static final String STOPPING = "Stopping...";
    // What survives is the question a cancelled run raises, and each mode answers it differently.
    private static final String CANCELLING_A_MOVE = "What reached your library stays there.";
    private static final String CANCELLING_A_SORT = "What was sorted stays where it is.";
    // Named in the copy rather than left to a spinner. A model that has been asked a question
    // answers in its own time, and a screen that only spun would look stuck for that whole minute.
    private static final String CANCELLING_A_SIFT = "Finishing the sheet it is already looking at, "
            + "which can take up to about a minute. Nothing further will be started.";
    private static final String CANCELLING_A_COPY = "What arrived stays in your Inbox.";
    private static final String CANCELLING_A_MOVE_IN = "Some files already moved to your Inbox.";

    private final FxProgressPort progress;

    /**
     * Creates the presenter over the port a running job reports itself to.
     *
     * @param progress {@link FxProgressPort} what a running job has reported so far
     */
    RunProgressPresenter(final FxProgressPort progress) {
        this.progress = progress;
    }

    /**
     * What the progress area draws for the job now running.
     *
     * @param ran {@link RunMode} the mode that job was started in
     * @param scope {@link String} what it covers, written out
     * @param cancelling boolean whether a stop has already been asked for
     * @param importing {@link ImportKind} null for anything but an import
     * @return {@link RunProgressView} every value that area puts on the page
     */
    RunProgressView view(final RunMode ran, final String scope, final boolean cancelling,
                         final @Nullable ImportKind importing) {
        final List<PhaseBar> bars = this.progress.phases().stream().map(RunProgressPresenter::bar).toList();
        return new RunProgressView(ran.label() + " progress", scope, bars,
                bars.isEmpty() ? STARTING : null, cancelling ? STOPPING : CANCEL, !cancelling,
                cancelling ? cancellingLine(ran, importing) : null, ran.phases());
    }

    /**
     * Drops whatever the last job reported, so a new one starts on an empty area.
     */
    void forgetPhases() {
        this.progress.forgetPhases();
    }

    /**
     * Says how a screen redraws itself while a job is reporting progress.
     *
     * @param redraw {@link Runnable} draws the dashboard again. Called on the application thread.
     */
    void setRepaint(final Runnable redraw) {
        this.progress.setRepaint(redraw);
    }

    /**
     * One reported phase as a bar.
     *
     * @param phase {@link ProgressPhase} what the job reported about it
     * @return {@link PhaseBar} the bar
     */
    private static PhaseBar bar(final ProgressPhase phase) {
        final boolean measured = phase.total() > 0;
        return new PhaseBar("run-phase-" + phase.label().toLowerCase(Locale.UK).replace(' ', '-'),
                phase.label(),
                measured ? RunWords.grouped(phase.current()) + " of " + RunWords.grouped(phase.total()) : null,
                measured ? (double) phase.current() / phase.total() : 0,
                measured, phase.finished());
    }

    /**
     * What the screen says while a cancellation is being honoured.
     *
     * <p>Each mode answers what survives, because that is the question a stop raises and each one
     * answers it differently. A sift adds how long the stop itself takes. Its stages are calls to a
     * model, and the one in flight has to come back before anything reads the request. Every other
     * mode checks between files, so it stops as fast as a reader can see.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param importing {@link ImportKind} null for anything but an import
     * @return {@link String} the line to show
     */
    private static String cancellingLine(final RunMode ran, final @Nullable ImportKind importing) {
        return switch (ran) {
            case SIFT, CURATE -> CANCELLING_A_SIFT;
            case MOVE_TO_LIBRARY -> CANCELLING_A_MOVE;
            // The copy's line for an import whose kind never arrived, being the one of the two that
            // claims nothing about the folder the photos came from.
            case IMPORT -> importing == ImportKind.MOVE ? CANCELLING_A_MOVE_IN : CANCELLING_A_COPY;
            // Rescue cannot be started yet, so nothing reaches this arm through it. Answered with
            // the sort's line because both move files out of a folder into another one.
            case SORT, RESCUE -> CANCELLING_A_SORT;
        };
    }
}
