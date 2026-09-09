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
    private static final String STOP = "Stop";
    // The button itself reports, rather than greying and leaving a line below to say what happened
    // to the press. A dead button still reading Stop is a press that looks like it missed.
    private static final String STOPPING = "Stopping...";
    private static final String STOP_NOW = "Stop now";
    private static final String STOPPING_NOW = "Stopping now...";
    private static final String WHAT_A_SECOND_PRESS_DOES =
            " The current file is finishing first. \"Stop now\" gives up on it instead.";
    private static final String GIVING_UP_ON_THE_FILE =
            " Giving up on the current file. Nothing half-written is left behind.";
    // What survives is the question a stopped run raises, and each mode answers it differently.
    private static final String CANCELLING_A_MOVE = "What reached your Library stays there.";
    private static final String CANCELLING_A_RESCUE = "What reached Sorted stays there.";
    private static final String CANCELLING_A_SORT = "What was sorted stays where it is.";
    // Named in the copy rather than left to a spinner. A model that has been asked a question
    // answers in its own time, and a screen that only spun would look stuck for that whole minute.
    private static final String CANCELLING_A_SIFT = "Finishing the current sheet, which can take up "
            + "to about a minute. Nothing further will be started.";
    private static final String CANCELLING_A_COPY = "What arrived stays in your Inbox.";
    private static final String CANCELLING_A_MOVE_IN = "Some files already moved to your Inbox.";
    private static final String CONTINUED_ON_ITS_OWN =
            "Your agent judged every sheet, so this sift continued on its own.";

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
     * @param abandoning boolean whether the file in flight has been given up on as well
     * @param importing {@link ImportKind} null for anything but an import
     * @param startedItself boolean whether this run began with nobody pressing anything
     * @return {@link RunProgressView} every value that area puts on the page
     */
    RunProgressView view(final RunMode ran, final String scope, final boolean cancelling,
                         final boolean abandoning, final @Nullable ImportKind importing,
                         final boolean startedItself) {
        final List<PhaseBar> bars = this.progress.phases().stream()
                .map(phase -> bar(phase, cancelling))
                .toList();
        return new RunProgressView(ran.label() + " progress", scope,
                startedItself ? CONTINUED_ON_ITS_OWN : null, bars,
                bars.isEmpty() ? STARTING : null, stopLabel(ran, cancelling, abandoning),
                !cancelling || offersToGiveUpOnTheFile(ran, abandoning),
                cancelling ? cancellingLine(ran, abandoning, this.midFile(), importing) : null, ran.phases());
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
     * What the stop button says, which is also what pressing it would do next.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param cancelling boolean whether a stop has already been asked for
     * @param abandoning boolean whether the file in flight has been given up on as well
     * @return {@link String} the label
     */
    private static String stopLabel(final RunMode ran, final boolean cancelling, final boolean abandoning) {
        if (!cancelling) {
            return STOP;
        }
        if (offersToGiveUpOnTheFile(ran, abandoning)) {
            return STOP_NOW;
        }
        return abandoning ? STOPPING_NOW : STOPPING;
    }

    /**
     * Whether a second press has somewhere to land.
     *
     * <p>A run moving files is asked to stop between one file and the next. A stop landing inside a
     * large one therefore waits out the rest of it, and that wait is what the second press ends.
     *
     * <p>A sift is the mode with no second press, and its long wait is why. What it waits on is a
     * model answering a question already asked and already paid for. There is no half-written file
     * to throw away, so a control here would do nothing at all.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param abandoning boolean whether the file in flight has been given up on already
     * @return boolean true where the escalation is still worth offering
     */
    private static boolean offersToGiveUpOnTheFile(final RunMode ran, final boolean abandoning) {
        return !abandoning && ran != RunMode.SIFT;
    }

    /**
     * Whether a file is part way across right now.
     *
     * <p>Only a transfer reports a fraction within a file, and only one file moves at a time. So a
     * phase carrying one is the phase writing, and no phase carrying one means nothing is.
     *
     * @return boolean true where a file is part way across
     */
    private boolean midFile() {
        return this.progress.phases().stream().anyMatch(phase -> phase.partDone() > 0);
    }

    /**
     * One reported phase as a bar.
     *
     * <p>The counts stay whole and the fill does not. A count of files cannot step until the file
     * in flight lands, so on one large file over a slow connection the fill is all that moves.
     *
     * @param phase {@link ProgressPhase} what the job reported about it
     * @param stopped boolean whether the reader has asked this run to stop
     * @return {@link PhaseBar} the bar
     */
    private static PhaseBar bar(final ProgressPhase phase, final boolean stopped) {
        final boolean counted = phase.started() && phase.total() > 0;
        // A count that reached its total is the phase's own proof, which a stopped run does not
        // take away. Without one there is nothing to weigh against the stop. A stage carrying its
        // stop in what it returns arrives here looking exactly like one that worked through.
        final boolean provedItself = counted && phase.current() >= phase.total();
        final boolean wentThrough = phase.finished() && !phase.cutShort() && (!stopped || provedItself);
        // A phase that ends having found nothing to do is bracketed like any other and never
        // ticks. Counted is false there, so without this it would animate for the rest of the run.
        final boolean measured = counted || phase.finished();
        return new PhaseBar("run-phase-" + phase.label().toLowerCase(Locale.UK).replace(' ', '-'),
                phase.label(),
                counted ? counts(phase) : null,
                fill(phase, counted, wentThrough),
                measured, phase.started(), phase.finished(), phase.cutShort(), wentThrough);
    }

    /**
     * How far along one phase's bar is drawn.
     *
     * <p>A phase that ended having counted something keeps that count, whatever ended it. Sifting
     * gives up part way whenever a run pauses for an agent. A full bar over a run that judged only
     * some of its sheets would say the opposite of what the card underneath says.
     *
     * <p>An uncounted ending has no fraction to draw either way. Full where the work went through,
     * and empty otherwise, since a full bar there would say it did.
     *
     * @param phase {@link ProgressPhase} what the job reported about it
     * @param counted boolean whether the phase both started and named a total
     * @param wentThrough boolean whether it can be said to have done all its work
     * @return double the fraction filled
     */
    private static double fill(final ProgressPhase phase, final boolean counted,
                               final boolean wentThrough) {
        if (counted) {
            return (phase.current() + phase.partDone()) / phase.total();
        }
        return wentThrough ? 1 : 0;
    }

    /**
     * The counts beside a bar, and how far through the current file where one is part way across.
     *
     * <p>The fill carries that fraction too, but only as `1/total` of the bar's width. Past a few
     * dozen files it is too small to see. That is where a run held up on one large file looks
     * stopped, since the counts cannot move either until it lands. Said in words, it reads the same
     * at any total.
     *
     * @param phase {@link ProgressPhase} what the job reported about it
     * @return {@link String} the counts line
     */
    private static String counts(final ProgressPhase phase) {
        final String whole = RunWords.grouped(phase.current()) + " of " + RunWords.grouped(phase.total());
        return phase.partDone() > 0
                ? whole + ", current file " + Math.round(phase.partDone() * 100) + "%"
                : whole;
    }

    /**
     * What the screen says while a cancellation is being honoured.
     *
     * <p>Each mode answers what survives, because that is the question a stop raises and each one
     * answers it differently. A sift adds how long the stop itself takes. Its stages are calls to a
     * model, and the one in flight has to come back before anything reads the request. Every other
     * mode checks between files, so it stops as fast as a reader can see.
     *
     * <p>A mode that moves files adds what the button beside it would do on a second press, but
     * only while a file is actually part way across. A stop landing inside one large file otherwise
     * reads as a button that missed.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param abandoning boolean whether the file in flight has been given up on as well
     * @param midFile boolean whether a file is part way across right now
     * @param importing {@link ImportKind} null for anything but an import
     * @return {@link String} the line to show
     */
    private static String cancellingLine(final RunMode ran, final boolean abandoning,
                                         final boolean midFile, final @Nullable ImportKind importing) {
        return reassuranceLine(ran, importing) + escalation(ran, abandoning, midFile);
    }

    /**
     * What the line adds about the file being written right now.
     *
     * <p>Says nothing about a current file unless one is part way across. A sort reports two phases
     * that read and hash before the one that writes. A stop landing in those has no file in flight
     * for the sentence to be about.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param abandoning boolean whether the file in flight has been given up on already
     * @param midFile boolean whether a file is part way across right now
     * @return {@link String} the sentences to append, empty where none belong
     */
    private static String escalation(final RunMode ran, final boolean abandoning, final boolean midFile) {
        if (ran == RunMode.SIFT) {
            return "";
        }
        if (abandoning) {
            return GIVING_UP_ON_THE_FILE;
        }
        return midFile ? WHAT_A_SECOND_PRESS_DOES : "";
    }

    /**
     * What a stopped run leaves behind, answered per mode.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param importing {@link ImportKind} null for anything but an import
     * @return {@link String} the reassurance the line opens with
     */
    private static String reassuranceLine(final RunMode ran, final @Nullable ImportKind importing) {
        return switch (ran) {
            case SIFT -> CANCELLING_A_SIFT;
            case MOVE_TO_LIBRARY -> CANCELLING_A_MOVE;
            case RESCUE -> CANCELLING_A_RESCUE;
            // The copy's line for an import whose kind never arrived, being the one of the two that
            // claims nothing about the folder the photos came from.
            case IMPORT -> importing == ImportKind.MOVE ? CANCELLING_A_MOVE_IN : CANCELLING_A_COPY;
            case SORT -> CANCELLING_A_SORT;
        };
    }
}
