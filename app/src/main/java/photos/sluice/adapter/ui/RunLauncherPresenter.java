package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Runs the dashboard: starts the work a user asks for, and says which of its three faces is up.
 *
 * <p>Each face decides its own contents. {@link RunSetupPresenter} draws the launcher,
 * {@link RunProgressPresenter} draws a job while it works, and {@link RunResults} turns a finished
 * job into its card. What is kept here is the job itself, because that is the one thing all three
 * faces describe and none of them owns.
 *
 * <p>One presenter bean stands for the dashboard, and its faces are constructed here. So the screen
 * has one thing to ask which face is up, and the faces are wired together in one place.
 */
@Component
@Profile("!cli")
public class RunLauncherPresenter {

    private static final Logger log = LoggerFactory.getLogger(RunLauncherPresenter.class);

    private final Pipeline pipeline;
    private final RunSetupPresenter setup;
    private final RunProgressPresenter progress;

    // Volatile throughout, because each is written on one thread and read on another. A screen
    // writes its own hooks as it is built, and a job reporting how it ended reads them. The running
    // flag is written by both of those, and read whenever somebody types.
    private volatile boolean running;
    private volatile @Nullable Runnable repaint;
    private volatile @Nullable Runnable recount;

    // The job now running, held so that Cancel has something to ask. Null between runs, which is
    // what a Cancel arriving after one ended reads to decide it has nothing to do.
    private volatile @Nullable JobHandle<?> inFlight;
    // The card for the run that ended, and the only thing that keeps the launcher off the screen
    // once nothing is running. Cleared by the press that dismisses it.
    private volatile @Nullable RunResultView ended;
    private volatile boolean cancelRequested;
    // What the running or just-ended job was started as, and what it covers. The mode buttons and
    // the field can both move while a job works, so neither can be asked afterwards what it was
    // started with.
    private volatile RunMode ranAs = RunMode.SORT;
    private volatile String scopeOfTheRun = "";

    /**
     * Creates the presenter over the facade it starts work through, and the port a running job
     * reports itself to.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param progress {@link FxProgressPort} what a running job has reported so far
     */
    public RunLauncherPresenter(final Pipeline pipeline, final FxProgressPort progress) {
        this.pipeline = pipeline;
        this.progress = new RunProgressPresenter(progress);
        this.setup = new RunSetupPresenter(pipeline, () -> this.running, this::repaint);
    }

    /**
     * The launcher's own face, which the screen asks what to draw and tells about every press.
     *
     * @return {@link RunSetupPresenter} what the launcher shows and what a press would start
     */
    public RunSetupPresenter setup() {
        return this.setup;
    }

    /**
     * Which of the dashboard's three faces is up.
     *
     * <p>Read in the order the ending writes them, so the running flag is asked first. A job that
     * has just dropped it has already stored what it produced.
     *
     * @return {@link RunStage} the face to draw
     */
    public RunStage stage() {
        if (this.running) {
            return new RunStage.Running(
                    this.progress.view(this.ranAs, this.scopeOfTheRun, this.cancelRequested));
        }
        final RunResultView done = this.ended;
        return done == null ? new RunStage.Setup() : new RunStage.Finished(done);
    }

    /**
     * Starts the work the launcher is set up for.
     */
    public void start() {
        final RunScope scope = this.setup.scope();
        if (!this.setup.canStart(scope)) {
            this.setup.reasonNothingStarted();
            return;
        }
        // Read once, and everything below works from it. The progress area and the result card
        // both name the work that was started, and the mode can move under a job in flight.
        final RunMode ran = this.setup.chosenMode();
        this.begin(ran, RunScope.describe(ran, scope), () -> this.submit(ran, scope));
    }

    /**
     * Continues a stopped run from where it left off.
     *
     * <p>Reached from the result card of a run its own spending limit stopped. Resuming skips every
     * sheet already holding a decision, so nothing already paid for is judged twice.
     *
     * @param prepDir {@link Path} the stopped run's own directory, as the card handed it back
     */
    public void continueRun(final Path prepDir) {
        if (this.running) {
            return;
        }
        this.begin(RunMode.SIFT, this.scopeOfTheRun, () -> this.pipeline.resume(prepDir, false));
    }

    /**
     * Puts the launcher back, dropping the report of the run that ended.
     *
     * <p>The counts behind the launcher were read again the moment that run ended. So what comes
     * back describes the folders as they now stand, not as the run found them.
     */
    public void dismissResult() {
        this.ended = null;
    }

    /**
     * Asks the running job to stop.
     *
     * <p>Cooperative rather than an interruption: a stage already in flight finishes first. On a
     * sift that is one call to a model, which is what the screen warns can take about a minute.
     *
     * <p>Asking twice changes nothing, and the button goes dead on the first press, so the second
     * would have to come from somewhere other than this screen.
     */
    public void cancel() {
        final JobHandle<?> handle = this.inFlight;
        if (handle == null) {
            return;
        }
        this.cancelRequested = true;
        handle.requestCancellation();
    }

    /**
     * Says which screen a finished run should draw itself on.
     *
     * <p>Held rather than taken per press. A run outlives the screen that started it: nothing
     * stops somebody opening Settings while it works, and the launcher they come back to is a new
     * one. A press that carried its own screen along would repaint the discarded one, leaving the
     * visible launcher waiting on a run that had already ended.
     *
     * @param repaint {@link Runnable} draws the launcher again, on whichever screen is up now.
     *     Called on the thread the job finished on.
     */
    public void setRepaint(final Runnable repaint) {
        this.repaint = repaint;
    }

    /**
     * Says how a screen redraws itself while a job is reporting progress.
     *
     * <p>Apart from {@link #setRepaint} because of the thread each arrives on. A job reports its
     * ending from whatever thread it ran on, so that one has to be marshalled by its caller. The
     * port has already marshalled this one, and has already folded a burst of events into a single
     * draw. Handing it on to be marshalled again would undo that folding.
     *
     * @param redraw {@link Runnable} draws the dashboard again. Called on the application thread.
     */
    public void setProgressRepaint(final Runnable redraw) {
        this.progress.setRepaint(redraw);
    }

    /**
     * Says how a screen goes back to the folders for fresh counts.
     *
     * <p>Apart from {@link #setRepaint} because the two cannot be the same action. A read begins by
     * asking the screen to draw itself. An action that also went back to the folders would start a
     * second read from inside the first.
     *
     * @param recount {@link Runnable} reads both trees again, off the thread that paints
     */
    public void setRecount(final Runnable recount) {
        this.recount = recount;
    }

    /**
     * Starts a job and takes the screen into its running state.
     *
     * <p>The one place a job's whole life is wired up, so a first press and a continue cannot end
     * up reporting themselves two different ways.
     *
     * <p>The refusals caught here are the ones the facade raises rather than the ones the launcher
     * can see coming. A job already running is the ordinary one. A screen that disabled its own
     * button still meets it, because the button was drawn before the other job started.
     *
     * @param ran {@link RunMode} the mode to report this job as
     * @param scope {@link String} what this job covers, written out for the progress area
     * @param submit a {@link Supplier} of {@link JobHandle} hands the work to the facade
     */
    private void begin(final RunMode ran, final String scope, final Supplier<JobHandle<?>> submit) {
        this.setup.report(null);
        try {
            final JobHandle<?> handle = submit.get();
            // Nothing above this line has changed what the screen shows, and that is the point. A
            // refusal leaves the card of the run that ended standing. On a run stopped at its
            // spending limit, that card holds the only offer to continue it. Cleared beforehand, a
            // refused press would strand the reader on a launcher with no way back to sheets they
            // have already paid for.
            this.ranAs = ran;
            this.scopeOfTheRun = scope;
            this.ended = null;
            this.cancelRequested = false;
            // The port holds whatever the last job reported until somebody says a new one has
            // begun. It is told about phases and never about jobs, so this is the only place that
            // boundary is known.
            this.progress.forgetPhases();
            this.inFlight = handle;
            this.running = true;
            handle.onComplete().whenComplete((outcome, failure) -> this.ends(ran, outcome, failure));
        } catch (final RuntimeException e) {
            // Everything the facade refuses outright arrives here, before any job exists. A job
            // already running, an app on its way out, a folder root gone bad since this screen was
            // drawn. Each carries a sentence written for the person reading it.
            log.info("Refused to start {}", ran, e);
            this.setup.report(new Message(RunRefusals.plainly(e), true));
        }
    }

    /**
     * Takes the screen out of its running state and onto the result card.
     *
     * <p>The card is built and stored before the running flag drops, and that order is
     * load-bearing. A reader between the two would otherwise find no job running and no result to
     * show, and be handed the launcher for one frame.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome what the job produced, null where it threw
     * @param failure {@link Throwable} what it threw, null where it did not
     */
    private void ends(final RunMode ran, final @Nullable Object outcome,
                      final @Nullable Throwable failure) {
        if (failure != null) {
            // JobRunner catches Throwable and completes the future without writing anything, so
            // this is the only record the failure gets.
            log.warn("{} failed", ran, failure);
        }
        this.inFlight = null;
        this.ended = failure == null
                ? RunResults.of(ran, outcome)
                : RunResults.failed(ran, RunRefusals.plainly(RunRefusals.rootOf(failure)));
        this.running = false;
        this.repaint();
        this.recount();
    }

    /**
     * Hands the chosen work to the facade.
     *
     * @param ran {@link RunMode} the mode being started
     * @param scope {@link RunScope} what the field and mode come to
     * @return a {@link JobHandle} of the job's own result
     */
    private JobHandle<?> submit(final RunMode ran, final RunScope scope) {
        return switch (ran) {
            case SORT -> this.pipeline.sort(RunScope.asSort(scope));
            case CURATE -> this.pipeline.curate(RunScope.asSort(scope));
            case SIFT -> this.pipeline.cull(RunScope.asCull(scope));
            case MOVE_TO_LIBRARY -> this.pipeline.commit(RunScope.asCommit(scope));
            case RESCUE -> throw new IllegalStateException("Rescue cannot be started from here yet");
        };
    }

    /**
     * Draws the launcher again, where a screen has said how.
     */
    private void repaint() {
        final Runnable draw = this.repaint;
        if (draw != null) {
            draw.run();
        }
    }

    /**
     * Reads the folders again, where a screen has said how.
     */
    private void recount() {
        final Runnable read = this.recount;
        if (read != null) {
            read.run();
        }
    }
}
