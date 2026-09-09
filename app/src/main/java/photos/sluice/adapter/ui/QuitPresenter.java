package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides what closing the window does while something is still running.
 *
 * <p>Closing with nothing running is answered by not asking at all. A run merely waiting for shards
 * is one of those. The job that prepared it has ended, and what is left is a watcher polling a
 * folder, which the wind-down retires with nothing lost. So the question is only ever put where a
 * job is moving files or spending money right now.
 *
 * <p>What the reader is asked is deliberately not what the wind-down then does. The question is
 * whether to stop the run, and only an answer of yes reaches the job at all. That is why the wait
 * lives in a second dialog rather than in the same one.
 */
@Component
@Profile("!cli")
public class QuitPresenter {

    private static final Logger log = LoggerFactory.getLogger(QuitPresenter.class);

    private static final String HEADING = "Quit while something is running?";
    private static final String STOP_AND_QUIT = "Stop and quit";
    private static final String KEEP_RUNNING = "Keep running";
    private static final String WAITING_HEADING = "Quitting";
    private static final String FORCE_QUIT = "Force quit now";

    // What a stopped run leaves behind, in the terms the progress area already answers it in. A
    // reader pressing the window's close button has that area in front of them.
    private static final String WHAT_IS_DONE_STAYS_DONE =
            " Quitting stops it, and what it has already done stays done.";

    private static final String WAITING_ON_A_SHEET =
            "Finishing the current sheet. That can take up to about a minute. \"Force quit now\" "
                    + "gives up on it, and sifting that sheet again later spends from your provider "
                    + "account balance a second time.";

    // "Picked up again" rather than moved again, because this line covers an import as well, and an
    // import that copies moves nothing. The reader chose copy or move before it started. Being told
    // the other one here reads as the app having taken the route they did not pick.
    //
    // It promises nothing about what is left on disk. A force quit exits without waiting for the
    // transfer to notice, so the part file the store would have deleted can outlive the process. It
    // is inert, since nothing downstream reads a part file as a photo, but it is there.
    private static final String WAITING_ON_A_FILE =
            "Finishing the current file. \"Force quit now\" gives up on it instead, and it is "
                    + "picked up again next time.";

    // A job the dashboard never started. None of them re-runs itself, and none passes a stop
    // signal into the store, so neither of the two lines above is true of one. What holds for any
    // job at all is that it stops.
    private static final String WAITING_ON_SOMETHING_ELSE =
            "Finishing what was already started. \"Force quit now\" leaves without waiting for it.";

    // How long a close waits before it decides there is anything to ask about. A run seconds from
    // finishing would otherwise put a question up that the reader answers for nothing. Long enough
    // for a job on its last file to land. Short enough to stay under the five seconds Windows waits
    // before calling a window not responding.
    static final Duration CLOSING_BEAT = Duration.ofMillis(2_500);

    // How often that wait re-asks, at most. Nothing announces a job ending, so this polls, and the
    // interval is what a reader would feel as a delay past the job's own end. Capped at a quarter
    // of the beat as well, or a wait shorter than one step would ask once and give up.
    private static final Duration LONGEST_BEAT_STEP = Duration.ofMillis(50);

    private final Pipeline pipeline;
    private final StartupSequence startup;
    private final RunLauncherPresenter launcher;
    private final Duration closingBeat;

    /**
     * Creates the presenter over the facade it asks about running work and the sequence that winds
     * the app down.
     *
     * @param pipeline {@link Pipeline} says whether anything is running, and carries the escalation
     * @param startup {@link StartupSequence} winds the app down and gives the working root back
     * @param launcher {@link RunLauncherPresenter} says what the run on the dashboard was started as
     */
    @Autowired
    public QuitPresenter(final Pipeline pipeline, final StartupSequence startup,
                         final RunLauncherPresenter launcher) {
        this(pipeline, startup, launcher, CLOSING_BEAT);
    }

    /**
     * Test seam: the same presenter with a shorter wait before the question goes up.
     *
     * @param pipeline {@link Pipeline} says whether anything is running, and carries the escalation
     * @param startup {@link StartupSequence} winds the app down and gives the working root back
     * @param launcher {@link RunLauncherPresenter} says what the run on the dashboard was started as
     * @param closingBeat {@link Duration} how long a close waits for a run to end on its own
     */
    QuitPresenter(final Pipeline pipeline, final StartupSequence startup,
                  final RunLauncherPresenter launcher, final Duration closingBeat) {
        this.pipeline = pipeline;
        this.startup = startup;
        this.launcher = launcher;
        this.closingBeat = closingBeat;
    }

    /**
     * What to ask before closing, or null where closing needs no asking.
     *
     * @return {@link QuitView} the wording of both dialogs, or null to close straight away
     */
    public @Nullable QuitView quitDialog() {
        if (this.endsWithinTheBeat()) {
            return null;
        }
        // Keeping leads, because quitting throws away a sheet already paid for and keeping costs
        // nothing.
        return new QuitView(HEADING, this.whatIsRunning() + WHAT_IS_DONE_STAYS_DONE, STOP_AND_QUIT,
                KEEP_RUNNING, false, WAITING_HEADING, this.waitingLine(), FORCE_QUIT);
    }


    /**
     * Stops the run and waits for it, then gives the working root back if it stopped in time.
     *
     * <p>Blocks for as long as the wait allows, so a caller runs it away from the thread that
     * paints. The dialog describing it has to keep drawing, and its own control has to stay
     * pressable.
     */
    public void stopAndWait() {
        this.windDownWithin(StartupSequence.ATTENDED_DRAIN_WAIT);
    }

    /**
     * Gives up on the file in flight and winds down without waiting for anything.
     *
     * <p>The working root stays claimed, which strands nothing. The process exits next and the
     * kernel drops the claim with it, exactly as it would after a crash.
     */
    public void forceQuit() {
        this.pipeline.abandonTheFileInFlight();
        this.windDownWithin(Duration.ZERO);
    }

    /**
     * Winds down, and returns whatever happens.
     *
     * <p>A reader who asked to quit gets to quit. Handing the working root back can fail on a claim
     * file another process is holding. Letting that escape would leave the caller waiting on a
     * wind-down that never answers. On this path the caller is a dialog that refuses to be
     * dismissed, so the app would sit there with no way out but the task manager.
     *
     * <p>What a failure costs is the claim staying held, which the kernel drops as the process ends.
     * That is the same outcome the wind-down reports when a job outruns its wait.
     *
     * @param wait {@link Duration} how long to give a running job to stop
     */
    private void windDownWithin(final Duration wait) {
        try {
            this.startup.windDownWithin(wait);
        } catch (final RuntimeException e) {
            log.warn("Winding down did not finish cleanly; quitting anyway", e);
        }
    }

    /**
     * What the question names as still going.
     *
     * @return {@link String} the opening sentence of the question
     */
    private String whatIsRunning() {
        final RunMode running = this.launcher.runningMode();
        return running == null ? "Something is still running."
                : running.verb() + " is still going.";
    }

    /**
     * What the second dialog says while the job stops.
     *
     * <p>A sift gets its own, because the wait and the cost are both different. What it is waiting
     * on is a model answering, which no stop can shorten, and the answer it gives up on has already
     * been paid for.
     *
     * <p>A job with no mode gets a third, saying only that it stops.
     *
     * @return {@link String} the line the waiting dialog carries
     */
    private String waitingLine() {
        final RunMode running = this.launcher.runningMode();
        if (running == null) {
            return WAITING_ON_SOMETHING_ELSE;
        }
        return running == RunMode.SIFT ? WAITING_ON_A_SHEET : WAITING_ON_A_FILE;
    }

    /**
     * Whether nothing is running, giving a run already under way a moment to end first.
     *
     * <p>Blocks the caller for up to the beat this presenter was built with, and the window sits
     * there while it does. That is what closing looks like anyway. A reader who presses the close
     * button on a run about to end then sees it close, rather than a question about stopping it.
     *
     * <p>Returns as soon as the run ends, so the wait is only as long as the run needs.
     *
     * @return boolean true where nothing is running by the end of the wait
     */
    private boolean endsWithinTheBeat() {
        final Instant deadline = Instant.now().plus(this.closingBeat);
        final Duration step = min(LONGEST_BEAT_STEP, this.closingBeat.dividedBy(4));
        while (this.pipeline.isBusy()) {
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            try {
                Thread.sleep(step);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * The shorter of two durations.
     *
     * @param one {@link Duration} the first
     * @param other {@link Duration} the second
     * @return {@link Duration} whichever is shorter
     */
    private static Duration min(final Duration one, final Duration other) {
        return one.compareTo(other) <= 0 ? one : other;
    }
}
