package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunLauncherView.StartAction;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.imports.ImportKind;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs the dashboard: starts the work a user asks for, and says which of its three faces is up.
 *
 * <p>Each face decides its own contents. {@link RunSetupPresenter} draws the launcher,
 * {@link RunProgressPresenter} draws a job while it works, and {@link RunResults} turns a finished
 * job into its card. What is kept here is the job itself, because that is the one thing all three
 * faces describe and none of them owns.
 *
 * <p>One presenter bean stands for the dashboard and its faces are constructed here, so the screen
 * has one thing to ask which face is up.
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
    private volatile @Nullable Runnable shellMark;
    private volatile @Nullable Runnable recount;
    private volatile @Nullable Runnable openRuns;
    private volatile @Nullable Runnable openReview;

    // The job now running, held so that Cancel has something to ask. Null between runs, which is
    // what a Cancel arriving after one ended reads to decide it has nothing to do.
    private volatile @Nullable JobHandle<?> inFlight;
    // The card for the run that ended, and the only thing that keeps the launcher off the screen
    // once nothing is running. Cleared by the press that dismisses it.
    private volatile @Nullable RunResultView endedCard;
    // What a refused press on that card has to report. Held apart from the card itself, which
    // RunResults builds out of what the run produced and nothing else.
    private volatile @Nullable Message cardMessage;
    private volatile boolean cancelRequested;
    private volatile boolean abandonRequested;
    // Whether the run on the dashboard is one nobody pressed for.
    private volatile boolean startedItself;
    // What the running or just-ended job was started as, and what it covers. The mode buttons and
    // the field can both move while a job works, so neither can be asked afterwards what it was
    // started with.
    private volatile RunMode startedMode = RunMode.SORT;
    private volatile String scopeOfTheRun = "";
    // What the run was narrowed to, where it was.
    private volatile @Nullable String narrowedScope;
    // What a cancelled import leaves behind differs between the two kinds, and the mode alone
    // cannot say which.
    private volatile @Nullable ImportKind importKind;

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
        // Last, so every field adopt() writes is assigned before a watcher can reach it.
        pipeline.onSiftAutoResumed(this::adopt);
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
            return new RunStage.Running(this.progress.view(this.startedMode, this.scopeOfTheRun,
                    this.cancelRequested, this.abandonRequested, this.importKind, this.startedItself));
        }
        final RunResultView done = this.endedCard;
        return done == null ? new RunStage.Setup() : new RunStage.Finished(done, this.cardMessage);
    }

    /**
     * Says how the screen goes to the runs list.
     *
     * <p>Held here rather than passed with each press, because the press that needs it is one the
     * launcher answers rather than one it starts. A timeframe whose unfinished sift cannot be
     * carried on has its way out on that screen, and nothing else on this one leads there.
     *
     * @param openRuns {@link Runnable} shows the runs screen
     */
    public void setOpenRuns(final Runnable openRuns) {
        this.openRuns = openRuns;
    }

    /**
     * Says how the screen goes to the review list.
     *
     * @param openReview {@link Runnable} shows the review screen
     */
    public void setOpenReview(final Runnable openReview) {
        this.openReview = openReview;
    }

    /**
     * Takes a press on the button under the scope field.
     *
     * <p>What that button does depends on what the chosen timeframe already holds, and the launcher
     * has worked that out before the press arrives. Three things can happen and only one of them
     * starts fresh work.
     *
     * @param action {@link StartAction} what the launcher said this press would do
     */
    public void press(final StartAction action) {
        switch (action) {
            case StartAction.StartFresh _ -> this.start();
            // Named from the field rather than from the last run this screen started, since reached
            // from the launcher there may have been no last run at all.
            case StartAction.ContinueRun(final Path prepDir) ->
                    this.resume(prepDir, RunScope.describe(RunMode.SIFT, this.setup.scope()));
            case StartAction.OpenRuns _ -> this.showRuns();
        }
    }

    /**
     * Shows the runs screen, from wherever on the launcher asked for it.
     *
     * <p>Does nothing while the shell has yet to say how, which is the window still being built.
     */
    public void showRuns() {
        final Runnable open = this.openRuns;
        if (open != null) {
            open.run();
        }
    }

    /**
     * Shows the review screen, which is where a rescue is started from.
     *
     * <p>Does nothing while the shell has yet to say how, which is the window still being built.
     */
    public void showReview() {
        final Runnable open = this.openReview;
        if (open != null) {
            open.run();
        }
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
        this.begin(ran, RunScope.describe(ran, scope), RunScope.narrowedTo(ran, scope), null,
                () -> this.submit(ran, scope));
    }

    /**
     * Brings photos into the Inbox, leaving the originals where they are.
     *
     * @param sources a {@link List} of {@link Path} the folders and files to bring in
     */
    public void startImportCopying(final List<Path> sources) {
        this.startImport(sources, ImportKind.COPY);
    }

    /**
     * Brings photos into the Inbox and removes each original once the copy in the Inbox has been
     * read back and hashed against it.
     *
     * <p>The screen offering the choice names which of its own two answers was taken, never what
     * that answer means. What it amounts to is this side's to say.
     *
     * @param sources a {@link List} of {@link Path} the folders and files to bring in
     */
    public void startImportMoving(final List<Path> sources) {
        this.startImport(sources, ImportKind.MOVE);
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
        this.resume(prepDir, this.scopeOfTheRun);
    }

    /**
     * Continues a run the reader picked off the runs screen.
     *
     * <p>The caller names the run, unlike {@link #continueRun}, which carries on the job this
     * screen itself last ran and already holds its name. A run chosen off a list may be one this
     * screen has never seen.
     *
     * @param prepDir {@link Path} the run's own directory
     * @param scope {@link String} what that run covers, in the words its own card used. The reader
     *     has just crossed from one screen to the other, so the two naming it differently would
     *     read as two different runs
     * @param waiveMissing boolean whether to go on without the sheets still owed
     * @return boolean false where a job already holds the slot and nothing was started
     */
    public boolean continueRunFromRuns(final Path prepDir, final String scope, final boolean waiveMissing) {
        if (this.running) {
            return false;
        }
        this.begin(RunMode.SIFT, scope, null, null, () -> this.pipeline.resume(prepDir, waiveMissing));
        return true;
    }

    /**
     * Moves what is left in one waiting folder back into Sorted, from the review screen's own row.
     *
     * <p>The caller names the folder and its root, because the launcher has no field that could.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder's name below that root
     * @param named {@link String} that folder as its own row named it. The reader has just crossed
     *     from one screen to the other, so the two naming it differently would read as two folders
     * @return boolean false where a job already holds the slot and nothing was started
     */
    public boolean rescueFromReview(final RescueRoot root, final String folder, final String named) {
        if (this.running) {
            return false;
        }
        this.begin(RunMode.RESCUE, named, null, null, () -> this.pipeline.rescue(root, folder));
        return true;
    }

    /**
     * Sifts the timeframe a sort filled, from that sort's own result card.
     *
     * <p>The dialog is the caller's to put, because only a screen can open one. What it may not do
     * is decide whether one is owed.
     *
     * @param offer {@link CardAction.SiftNow} what the card offered, carrying the timeframe and
     *     what this run put in it
     * @param ask a {@link Predicate} of {@link RunSetupPresenter.Confirmation} puts the question and
     *     answers true where the reader agreed
     */
    public void siftNow(final CardAction.SiftNow offer,
                        final Predicate<RunSetupPresenter.Confirmation> ask) {
        if (this.running) {
            return;
        }
        switch (this.setup.siftNowNeeds(offer.year(), offer.justSorted())) {
            case RunSetupPresenter.SiftNow.Refuse(final Message reason) -> this.cardMessage = reason;
            case RunSetupPresenter.SiftNow.Ask(final var question) -> {
                if (ask.test(question)) {
                    this.startSift(offer.year());
                }
            }
        }
    }

    /**
     * Puts the launcher back, dropping the report of the run that ended.
     *
     * <p>The counts behind the launcher were read again the moment that run ended, so what comes
     * back describes the folders as they now stand.
     */
    public void dismissResult() {
        this.endedCard = null;
        this.cardMessage = null;
        this.setup.clearRefusedScope();
        this.markShell();
    }

    /**
     * Asks the running job to stop, and on a second press gives up on the file in flight.
     *
     * <p>The first press is cooperative rather than an interruption: a stage already in flight
     * finishes first. On a sift that is one call to a model, which is what the screen warns can take
     * about a minute. On anything moving files it is the rest of the file being written. Over a slow
     * connection that is long enough to read as a button that missed.
     *
     * <p>The second press is what ends that wait. It throws away the part already written rather
     * than finishing it, so the file stays where it was and nothing half-written is left behind.
     *
     * <p>Pressing again after that changes nothing, there being nothing further to escalate to.
     */
    public void cancel() {
        final JobHandle<?> handle = this.inFlight;
        if (handle == null) {
            return;
        }
        if (this.cancelRequested) {
            this.abandonRequested = true;
            handle.requestAbandon();
            return;
        }
        this.cancelRequested = true;
        handle.requestCancellation();
    }

    /**
     * What the run now on the dashboard was started as, or null where the dashboard has no run.
     *
     * <p>Null does not mean nothing is running. Other screens run jobs of their own, and a caller
     * asking what is running has to allow for that.
     *
     * @return {@link RunMode} the mode of the run in flight, or null
     */
    public @Nullable RunMode runningMode() {
        return this.running ? this.startedMode : null;
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
     * What the sidebar's Dashboard entry should be carrying.
     *
     * <p>Read in the same order {@link #stage} reads its own state, so the two never disagree about
     * which face is up.
     *
     * @return {@link DashboardMark} the mark, or NONE where nothing is happening there
     */
    public DashboardMark dashboardMark() {
        if (this.running) {
            return DashboardMark.RUNNING;
        }
        return this.endedCard == null ? DashboardMark.NONE : DashboardMark.FINISHED;
    }

    /**
     * Says how the shell redraws the mark on its Dashboard entry.
     *
     * <p>Called on whichever thread moved the job, so the shell marshals it.
     *
     * @param mark {@link Runnable} redraws the sidebar's mark
     */
    public void setShellMark(final Runnable mark) {
        this.shellMark = mark;
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
     * Hands a whole timeframe to the facade as a sift.
     *
     * @param year int the timeframe to sift
     */
    private void startSift(final int year) {
        final RunScope scope = new RunScope.OfYear(year, List.of());
        this.begin(RunMode.SIFT, RunScope.describe(RunMode.SIFT, scope), null, null,
                () -> this.pipeline.cull(RunScope.asCull(scope)));
    }

    /**
     * Continues a stopped run, reported as covering what the caller says it covers.
     *
     * @param prepDir {@link Path} the stopped run's own directory
     * @param scope {@link String} what that run covers, written out for the progress area
     */
    private void resume(final Path prepDir, final String scope) {
        if (this.running) {
            return;
        }
        this.begin(RunMode.SIFT, scope, null, null, () -> this.pipeline.resume(prepDir, false));
    }

    /**
     * Takes the screen into its running state over a sift that started without a press.
     *
     * <p>A run the reader's own agent triggered is theirs to watch and theirs to stop. So the
     * dashboard holds it exactly as it holds one they pressed for.
     *
     * <p>Unlike {@link #begin}, this does not clear what the last run reported to the progress
     * area. That clearing covers the gap between a press and the job's own first word. Here the job
     * is already going and may already have spoken, so a clear could wipe the plan it had just
     * announced.
     *
     * @param scope {@link String} what this sift covers, as its own run is named
     * @param job a {@link JobHandle} of {@link CullJobOutcome} the sift now running
     */
    private void adopt(final String scope, final JobHandle<CullJobOutcome> job) {
        this.report(null);
        this.startedMode = RunMode.SIFT;
        this.scopeOfTheRun = scope;
        this.narrowedScope = null;
        this.importKind = null;
        this.endedCard = null;
        this.cancelRequested = false;
        this.abandonRequested = false;
        this.startedItself = true;
        this.inFlight = job;
        this.running = true;
        this.repaint();
        job.onComplete().whenComplete((outcome, failure) -> this.ends(RunMode.SIFT, job, outcome, failure));
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
     * @param narrowedTo {@link String} what it was narrowed to, or null where it takes what it finds
     * @param kind {@link ImportKind} null for every run that is not an import
     * @param submit a {@link Supplier} of {@link JobHandle} hands the work to the facade
     */
    private void begin(final RunMode ran, final String scope, final @Nullable String narrowedTo,
                       final @Nullable ImportKind kind, final Supplier<JobHandle<?>> submit) {
        this.report(null);
        // Ahead of the submit, and that order is the whole of it. The job's first act is to
        // announce its own phases, from its own thread. Dropped after the submit, this would race
        // that announcement and could wipe the plan the run had just made.
        this.progress.forgetPhases();
        try {
            final JobHandle<?> handle = submit.get();
            // Nothing above this line has changed what the screen shows, and that is the point. A
            // refusal leaves the card of the run that ended standing. On a run stopped at its
            // spending limit, that card holds the only offer to continue it. Cleared beforehand, a
            // refused press would strand the reader on a launcher with no way back to sheets they
            // have already paid for.
            this.startedMode = ran;
            this.scopeOfTheRun = scope;
            this.narrowedScope = narrowedTo;
            this.importKind = kind;
            this.endedCard = null;
            this.cancelRequested = false;
            this.abandonRequested = false;
            this.startedItself = false;
            this.inFlight = handle;
            this.running = true;
            this.markShell();
            handle.onComplete().whenComplete((outcome, failure) -> this.ends(ran, handle, outcome, failure));
        } catch (final RuntimeException e) {
            // Everything the facade refuses outright arrives here, before any job exists. A job
            // already running, an app on its way out, a folder root gone bad since this screen was
            // drawn. Each carries a sentence written for the person reading it.
            log.info("Refused to start {}", ran, e);
            this.report(RunRefusals.refuseMessage(e));
        }
    }

    /**
     * Puts a refusal on whichever face the reader is looking at.
     *
     * <p>A card still standing is the face they pressed from, since it covers the launcher entirely.
     *
     * <p>Clearing goes to both faces whatever is up. A press that starts something can take the
     * card off the screen. So the face that held the last refusal is not always the one still
     * showing when the next one is drawn.
     *
     * @param message {@link Message} what to report, or null to clear both faces
     */
    private void report(final @Nullable Message message) {
        if (message == null) {
            this.setup.report(null);
            this.cardMessage = null;
        } else if (this.endedCard == null) {
            this.setup.report(message);
        } else {
            this.cardMessage = message;
        }
    }

    /**
     * Takes the screen out of its running state and onto the result card.
     *
     * <p>The card is built and stored before the running flag drops, and that order is
     * load-bearing. A reader between the two would otherwise find no job running and no result to
     * show, and be handed the launcher for one frame.
     *
     * <p>A job whose slot has already been taken by another leaves the screen alone. The runner
     * frees the slot a moment before it hands an outcome back, so another job can be admitted
     * inside that gap. Without this the older run's card would go up over one that had only just
     * started.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param job a {@link JobHandle} of any result the job reporting itself
     * @param outcome what the job produced, null where it threw
     * @param failure {@link Throwable} what it threw, null where it did not
     */
    private void ends(final RunMode ran, final JobHandle<?> job, final @Nullable Object outcome,
                      final @Nullable Throwable failure) {
        if (this.inFlight != job) {
            return;
        }
        if (failure != null) {
            // JobRunner catches Throwable and completes the future without writing anything, so
            // this is the only record the failure gets.
            log.warn("{} failed", ran, failure);
        }
        this.inFlight = null;
        this.endedCard = failure == null
                ? RunResults.of(ran, outcome, this.narrowedScope)
                : cardFor(ran, JobHandle.failureIn(failure));
        this.running = false;
        this.repaint();
        this.recount();
    }

    /**
     * The card for a job that threw.
     *
     * <p>A sift the provider gave up on is read first, and it is the one throw here that is not a
     * refusal. It reached the provider and spent from the reader's balance, so it leaves a run on
     * disk and counts to show. Everything else is worded as a refusal.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param failure {@link Throwable} what it threw, already out of any completion wrapper
     * @return {@link RunResultView} the card
     */
    private static RunResultView cardFor(final RunMode ran, final Throwable failure) {
        if (failure instanceof final CullException incomplete) {
            return RunResults.incompleteResult(ran, incomplete);
        }
        return RunResults.failedResult(ran, RunRefusals.refusalOf(failure));
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
            case SIFT -> this.pipeline.cull(RunScope.asCull(scope));
            case MOVE_TO_LIBRARY -> this.pipeline.commit(RunScope.asCommit(scope));
            case RESCUE -> throw new IllegalStateException("A rescue is not started from a scope");
            case IMPORT -> throw new IllegalStateException("An import is not started from a scope");
        };
    }

    /**
     * Starts an import of either kind.
     *
     * <p>Started from the Inbox card or from a drop, so what it covers is the folders that were
     * chosen rather than what the scope field says.
     *
     * @param sources a {@link List} of {@link Path} the folders and files to bring in
     * @param kind {@link ImportKind} whether the originals stay where they are
     */
    private void startImport(final List<Path> sources, final ImportKind kind) {
        if (this.running) {
            return;
        }
        this.begin(RunMode.IMPORT, describe(sources), null, kind,
                () -> this.pipeline.importFrom(sources, kind));
    }

    /**
     * What an import covers, written out for a screen that cannot show what was chosen.
     *
     * @param sources a {@link List} of {@link Path} the folders and files chosen
     * @return {@link String} what this import covers
     */
    private static String describe(final List<Path> sources) {
        return RunWords.listed(sources.stream().map(RunWords::named).toList());
    }

    /**
     * Draws the launcher again, where a screen has said how.
     *
     * <p>The shell's mark goes with it. Every one of the three faces the launcher can be showing
     * is a different mark, so nothing changes one without changing the other.
     */
    private void repaint() {
        final Runnable draw = this.repaint;
        if (draw != null) {
            draw.run();
        }
        this.markShell();
    }

    /**
     * Puts the sidebar's mark back in step with what the dashboard is doing.
     */
    private void markShell() {
        final Runnable mark = this.shellMark;
        if (mark != null) {
            mark.run();
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
