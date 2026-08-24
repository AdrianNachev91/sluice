package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Cost;
import photos.sluice.adapter.ui.RunLauncherView.InboxCard;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;

import java.nio.file.Path;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Decides what the run launcher shows and starts the work a user asks for on it.
 *
 * <p>It holds what has been chosen so far: the mode, whatever is typed in the scope field, and the
 * counts last read off disk. The screen keeps the controls and asks here after every change. So
 * every rule about what a scope means lives in one place, which a test can reach without a window.
 *
 * <p>The counts are read by calling {@link #refreshCounts}, which walks two trees and blocks while
 * it does. The caller runs it off whatever thread paints.
 */
@Component
@Profile("!cli")
public class RunLauncherPresenter {

    private static final Logger log = LoggerFactory.getLogger(RunLauncherPresenter.class);

    private static final String SCOPE_LABEL = "Timeline for this run";

    // How long a folder read is given before the screen says it is reading. Under this, a reader
    // sees one state rather than three. Over it, they are waiting and want to know why.
    private static final long SETTLE_BEFORE_SAYING_SO = 200;

    // Asked wherever the run can spend, because a curate is the one run whose cost cannot be shown
    // before it starts. A provider that spends nothing leaves nothing to weigh. The box above the
    // button says so, where a dialog would only be in the way.
    private static final Confirmation CURATE_CONFIRM = new Confirmation(
            "Sort and sift the oldest year?",
            "Sluice sorts the oldest year in your Inbox, then sifts every photo it just sorted. "
                    + "Sifting is what spends money, and the size of the sift cannot be estimated "
                    + "until sorting has finished.",
            "Sort and sift", "Cancel");

    // Careful about whose money it is. Sluice calls no model for these providers, so it spends
    // nothing. An agent somebody runs themselves still bills them, and that is not ours to report.
    private static final String FREE_DETAIL = "Sluice only spends from your provider account "
            + "balance when it calls an agent for you. An agent you run yourself still costs "
            + "whatever you pay for it.";

    private static final String STARTING = "Starting...";
    private static final String CANCEL = "Cancel";
    // The button itself reports, rather than greying and leaving a line below to say what happened
    // to the press. A dead button still reading Cancel is a press that looks like it missed.
    private static final String STOPPING = "Stopping...";
    // What survives is the question a cancelled run raises, and each mode answers it differently.
    private static final String CANCELLING_A_MOVE = "What has already reached your library stays "
            + "there. Nothing further will be moved.";
    private static final String CANCELLING_A_SORT = "What has already been sorted stays where it "
            + "is. Nothing further will be moved.";
    // Named in the copy rather than left to a spinner. A model that has been asked a question
    // answers in its own time, and a screen that only spun would look stuck for that whole minute.
    private static final String CANCELLING_A_SIFT = "Finishing the sheet it is already looking at, "
            + "which can take up to about a minute. Nothing further will be started.";

    private static final String INBOX_COUNTING = "Counting what is waiting...";
    private static final String INBOX_EMPTY = "Nothing to sort.";
    private static final String INBOX_EMPTY_DETAIL =
            "Put photos in your Inbox folder and they will show up here.";
    // One card reports a failed read for both, so it names neither. Any of the three folder
    // settings can be what broke, and this card cannot tell which.
    private static final String INBOX_UNREADABLE = "Sluice could not read your folders. Check them "
            + "in Settings.";

    private static final String BUSY_ELSEWHERE = "Something else is running now, and Sluice works "
            + "on one thing at a time. Try again once it has finished.";

    private static final String STILL_READING = "Sluice is still reading your folders. Try again in "
            + "a moment.";

    private static final String NOTHING_STAGED =
            "Nothing is sorted yet. Sort your Inbox first, and the years will show up here.";

    // The last sentence is the load-bearing one: what a reader needs is not that the number is
    // right, but that something stops a run that outgrows it.
    private static final String DISCLAIMER = "An estimate, not a quote. It is an average of what "
            + "sifts like this one have cost, and a sift costs more when the model needs a second "
            + "attempt at a sheet. Sluice will stop and ask whether to continue if the sift goes "
            + "far past the estimate.";

    private static final String WITHOUT_HISTORY = "Nothing has finished a sift on this computer "
            + "yet, so this figure is Sluice's own starting guess rather than an average of your "
            + "own sifts. It starts showing your actual numbers after a sift or two.";

    private final Pipeline pipeline;
    private final FxProgressPort progress;

    // Volatile throughout. Every one of these is written off the thread that paints. The counts
    // come from the read behind the cards, the rest from a job reporting how it ended. The screen
    // reads them whenever somebody types, which is ordered against neither. Without it a reader can
    // see counting already false while the count is still null, and the Inbox card then reports a
    // failure that never happened.
    private volatile RunMode chosen = RunMode.SORT;
    private volatile String scopeText = "";
    private volatile @Nullable InboxTally inbox;
    private volatile @Nullable SortedTally sorted;
    private volatile boolean counting = true;
    private volatile boolean countsUnreadable;
    private volatile boolean running;
    private volatile @Nullable Message message;
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

    // The one field above that is not volatile, because it is the one no other thread touches. It
    // is written by a press and by a keystroke, and read while drawing, all on the thread that
    // paints. Volatile would not make its own toggle atomic anyway, and would suggest a second
    // writer that does not exist.
    private boolean monthsCollapsed;

    private final ReentrantLock reading = new ReentrantLock();

    // Written value first and key second, and read the other way round. Between them that is what
    // makes a reader finding its own count in the key certain of the answer beside it. Either half
    // alone gives nothing. The counts above are ordered on the same principle.
    private volatile @Nullable SpendEstimate lastEstimate;
    private volatile int lastEstimateCovered = -1;

    /**
     * Creates the presenter over the facade it reads counts from and starts work through, and the
     * port a running job reports itself to.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param progress {@link FxProgressPort} what a running job has reported so far
     */
    public RunLauncherPresenter(final Pipeline pipeline, final FxProgressPort progress) {
        this.pipeline = pipeline;
        this.progress = progress;
    }

    /**
     * What the launcher draws right now.
     *
     * @return {@link RunLauncherView} every value the screen puts on the page
     */
    public RunLauncherView view() {
        final Scope scope = this.scope();
        return new RunLauncherView(this.modes(), this.chosen.explained(), this.inboxCard(),
                this.yearChoices(), this.readsSorted(),
                this.nothingStagedLine(), SCOPE_LABEL, this.scopeText, this.chosen.scopeHint(),
                this.refusalOf(scope), this.cost(scope), this.chosen.started(),
                this.canStart(scope), this.message);
    }

    /**
     * Reads what is in the Inbox and what is staged in Sorted, from disk.
     *
     * <p>Blocks for as long as walking those two trees takes, which on a full Inbox is long enough
     * to be felt. Called off the thread that paints.
     *
     * <p>A refusal from the facade is kept rather than thrown on. The roots were usable when this
     * screen was drawn, so one that is not now went wrong while somebody was looking at it. The
     * cards say so. The start button stays live, because the refusal it raises names which folder
     * is at fault, and that is more than this screen knows.
     */
    public void refreshCounts() {
        // Serialised rather than allowed to overlap. Two things start a read: a screen being built
        // and a run ending. Left concurrent, whichever finished first would drop the flag while the
        // other was still walking. The cards would then pair an Inbox from one moment with years
        // from another. A waiting caller re-walks rather than skipping, since the read it would
        // have skipped may predate the run that asked for this one.
        this.reading.lock();
        // Everything between the lock and the unlock sits in the try, the repaint included. It
        // reaches the screen through the FX thread, which refuses the handover once the window is
        // gone. Thrown from outside the try, that leaves the lock held for the rest of the run.
        try {
            // A run that just finished spent against the ledger the estimate is averaged from.
            this.lastEstimateCovered = -1;
            // Raised on every read, not only the first. Between a run ending and this landing, the
            // cards hold the state from before that run. A Start pressed against them would scope a
            // job by counts the run has already changed.
            this.counting = true;
            // Drawn before the walk rather than after it, but not straight away. A walk over a full
            // Inbox is long enough to be felt. A screen still showing the live button it was drawn
            // with takes a press and does nothing. A walk over an empty one is over in
            // milliseconds, and a button going dead and live again inside that reads as a glitch.
            // So the dead state waits to see which kind of walk this is.
            this.deadButtonOnceThisIsSlow();
            this.inbox = this.pipeline.inboxTally();
            this.sorted = this.pipeline.sortedTally();
            this.countsUnreadable = false;
        } catch (final RuntimeException e) {
            log.warn("Could not count what is waiting and what is staged", e);
            // Both counts go, not only the one that failed. A card saying the Inbox cannot be read,
            // over a list of years read a minute ago, describes two different moments as one.
            this.inbox = null;
            this.sorted = null;
            this.countsUnreadable = true;
        } finally {
            this.counting = false;
            this.reading.unlock();
        }
    }

    /**
     * Puts the launcher on a mode.
     *
     * <p>Clears whatever the last run had to report, as all three of these do. A line about work
     * that has already ended is stale the moment somebody sets up the next one.
     *
     * @param mode {@link RunMode} the mode to work in
     */
    public void setMode(final RunMode mode) {
        this.chosen = mode;
        this.message = null;
    }

    /**
     * Puts the launcher on a scope, as text.
     *
     * <p>Text rather than a parsed scope, because what the field holds is what a user typed, and
     * half of it is not a scope yet. What it comes to is worked out on every read.
     *
     * @param text {@link String} the scope field's contents
     */
    public void setScope(final String text) {
        this.scopeText = text;
        // Typing is a fresh intent, so a folded year opens again. Typing a month while its year is
        // folded would otherwise narrow the run to a row nobody can see.
        this.monthsCollapsed = false;
        this.message = null;
    }

    /**
     * Takes a press on one year's row.
     *
     * <p>A press means one of two things and the screen is not the one to work out which. A year
     * the field does not name becomes the scope. The year it already names folds its months away,
     * or brings them back, and changes nothing else.
     *
     * <p>Folding leaves the scope text alone, so a month already narrowed to survives it and is
     * still marked when the months come back. Writing the year again would drop it.
     *
     * <p>The year goes into the scope text rather than being remembered beside it, so there is one
     * answer to "what is this run scoped to". A row that scoped a run without the field showing it
     * would leave two places to look and no way to tell which one the run used.
     *
     * @param year int the year whose row was pressed
     */
    public void pressYear(final int year) {
        if (this.typedYear() == year) {
            this.monthsCollapsed = !this.monthsCollapsed;
            return;
        }
        this.scopeText = String.valueOf(year);
        this.monthsCollapsed = false;
        this.message = null;
    }

    /**
     * Takes a press on one month's row.
     *
     * <p>Adds the month to those the run covers, or takes it back out where it is already there.
     * Months accumulate rather than replace one another, so picking three of them is three presses
     * rather than a typed list. Taking the last one out leaves the whole year, never nothing, since
     * a month row is only reachable under the year the field already names.
     *
     * <p>A year press switches and a month press accumulates. That is the scope's own shape rather
     * than an inconsistency. Every scope names one year and any number of its months.
     *
     * <p>Written back as a comma list rather than collapsed to a run, because the two spell the
     * same set and the field already reads both. A gap is left to be refused where the mode cannot
     * take one, which is the same answer typing that gap would get.
     *
     * @param year int the year holding it
     * @param month int the month whose row was pressed
     */
    public void pressMonth(final int year, final int month) {
        final List<Integer> covered = new ArrayList<>(this.monthsNamedFor(year));
        if (!covered.remove(Integer.valueOf(month))) {
            covered.add(month);
        }
        covered.sort(Comparator.naturalOrder());
        this.scopeText = covered.isEmpty() ? String.valueOf(year) : year + " " + joined(covered);
        this.message = null;
    }

    /**
     * The question to put before starting, where this run needs one asked.
     *
     * <p>Curate does wherever the configured provider can spend. It is the one mode that spends
     * without showing a figure first. A sort decides which photos land under which year, so nothing
     * can be sized until it has run. Every other spending run either shows what it will cost or
     * costs nothing.
     *
     * <p>On a provider that spends nothing the question is not merely redundant, it is false: its
     * own words say that sifting spends money. What replaces it is a statement rather than a
     * question, in the cost box above the button, since nothing needs weighing before a press.
     *
     * <p>The bare move to the library is the other. Every other mode either names its own scope or
     * takes the oldest year, and both are small enough to be undone by hand. Moving everything
     * staged is the one press that reaches the whole library in one go, so it says out loud what it
     * is about to move.
     *
     * @return {@link Confirmation} what to ask, or null where nothing needs asking
     */
    public @Nullable Confirmation confirmationNeeded() {
        if (this.chosen == RunMode.CURATE) {
            return this.pipeline.configuredProviderSpends() ? CURATE_CONFIRM : null;
        }
        // Nothing to ask where the counts are not in. Start stays live so the facade can name the
        // folder at fault, and this question names years and a file count it has neither of.
        if (this.chosen != RunMode.MOVE_TO_LIBRARY || !this.countsAreIn()
                || !(this.scope() instanceof Scope.Everything)) {
            return null;
        }
        final List<YearRow> staged = this.stagedYears();
        final int files = staged.stream().mapToInt(YearRow::total).sum();
        return new Confirmation("Move everything to your library?",
                "This moves " + counted(files, "file", "files") + " from "
                        + listed(staged.stream().map(row -> String.valueOf(row.year())).toList())
                        + " into your library folder.",
                "Move to library", "Cancel");
    }

    /**
     * Starts the work the launcher is set up for.
     *
     * <p>The refusals caught here are the ones the facade raises rather than the ones this screen
     * can see coming. A job already running is the ordinary one. A screen that disabled its own
     * button still meets it, because the button was drawn before the other job started.
     */
    public void start() {
        final Scope scope = this.scope();
        if (!this.canStart(scope)) {
            // Every other reason is already on the screen: a refusal under the field, a dead
            // button, a mode with nothing behind it. Another job starting is the one a reader
            // cannot see, and a confirm is long enough to answer for one to start underneath it.
            if (this.pipeline.isBusy()) {
                this.message = new Message(BUSY_ELSEWHERE, true);
            } else if (this.counting) {
                // The button is live for the first moments of a read, so this press is one the
                // screen invited. Saying nothing would make the press look like it missed.
                this.message = new Message(STILL_READING, true);
            }
            return;
        }
        // Read once, and everything below works from it. The progress area and the result card
        // both name the work that was started, and the mode can move under a job in flight.
        final RunMode ran = this.chosen;
        this.begin(ran, describe(ran, scope), () -> this.submit(ran, scope));
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
        this.begin(RunMode.SIFT, this.scopeOfTheRun,
                () -> this.pipeline.resume(prepDir, false));
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
     * Which of the dashboard's three faces is up.
     *
     * <p>Read in the order the ending writes them, so the running flag is asked first. A job that
     * has just dropped it has already stored what it produced.
     *
     * @return {@link RunStage} the face to draw
     */
    public RunStage stage() {
        if (this.running) {
            return new RunStage.Running(this.progressView());
        }
        final RunResultView done = this.ended;
        return done == null ? new RunStage.Setup() : new RunStage.Finished(done);
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
     * @param ran {@link RunMode} the mode to report this job as
     * @param scope {@link String} what this job covers, written out for the progress area
     * @param submit a {@link Supplier} of {@link JobHandle} hands the work to the facade
     */
    private void begin(final RunMode ran, final String scope, final Supplier<JobHandle<?>> submit) {
        this.message = null;
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
            this.message = new Message(plainly(e), true);
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
                : RunResults.failed(ran, plainly(rootOf(failure)));
        this.running = false;
        this.repaint();
        this.recount();
    }

    /**
     * What the progress area draws for the job now running.
     *
     * @return {@link RunProgressView} every value that area puts on the page
     */
    private RunProgressView progressView() {
        final List<PhaseBar> bars = this.progress.phases().stream().map(RunLauncherPresenter::bar).toList();
        return new RunProgressView(this.ranAs.label() + " progress", this.scopeOfTheRun,
                bars, bars.isEmpty() ? STARTING : null, this.cancelRequested ? STOPPING : CANCEL,
                !this.cancelRequested, this.cancelRequested ? this.cancellingLine() : null,
                this.ranAs.phases());
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
                measured ? grouped(phase.current()) + " of " + grouped(phase.total()) : null,
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
     * @return {@link String} the line to show
     */
    private String cancellingLine() {
        return switch (this.ranAs) {
            case SIFT, CURATE -> CANCELLING_A_SIFT;
            case MOVE_TO_LIBRARY -> CANCELLING_A_MOVE;
            // Rescue cannot be started yet, so nothing reaches this arm through it. Answered with
            // the sort's line because both move files out of a folder into another one.
            case SORT, RESCUE -> CANCELLING_A_SORT;
        };
    }

    /**
     * What a run covers, written out for a screen that cannot show the field that named it.
     *
     * @param ran {@link RunMode} the mode being started
     * @param scope {@link Scope} what the field and mode come to
     * @return {@link String} what this run covers
     */
    private static String describe(final RunMode ran, final Scope scope) {
        return switch (scope) {
            case Scope.OfYear(final int year, final List<Integer> months) -> months.isEmpty()
                    ? String.valueOf(year)
                    : year + ", " + namedMonths(months);
            case Scope.OldestYear _ -> "the oldest year in your Inbox";
            case Scope.Everything _ -> "everything in Sorted";
            // Neither can reach a started job: the button is dead over both. Answered anyway, since
            // a switch over a sealed set that throws for two of five is one added case away from
            // throwing on a screen.
            case Scope.Refused _, Scope.Nothing _ -> ran.verb();
        };
    }

    /**
     * A run of months as a sentence names them.
     *
     * @param months a {@link List} of {@link Integer} the months, in order
     * @return {@link String} the months written out
     */
    private static String namedMonths(final List<Integer> months) {
        return listed(months.stream()
                .map(month -> Month.of(month).getDisplayName(TextStyle.FULL, Locale.UK))
                .toList());
    }

    /**
     * A failure's own cause where it has one, since what a job threw is usually a wrapper.
     *
     * @param failure {@link Throwable} what the job's promise completed with
     * @return {@link Throwable} the one carrying the sentence worth showing
     */
    private static Throwable rootOf(final Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
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

    /**
     * The buttons across the top, in the order they are drawn.
     *
     * @return a {@link List} of {@link ModeChoice} one per mode
     */
    private List<ModeChoice> modes() {
        return List.of(
                this.modeChoice(RunMode.SORT, "run-mode-sort"),
                this.modeChoice(RunMode.SIFT, "run-mode-sift"),
                this.modeChoice(RunMode.MOVE_TO_LIBRARY, "run-mode-move"),
                this.modeChoice(RunMode.CURATE, "run-mode-curate"),
                this.modeChoice(RunMode.RESCUE, "run-mode-rescue"));
    }

    /**
     * One mode's button.
     *
     * @param mode {@link RunMode} which mode it starts
     * @param id {@link String} the control's id
     * @return {@link ModeChoice} the button
     */
    private ModeChoice modeChoice(final RunMode mode, final String id) {
        return new ModeChoice(mode, id, mode.label(), mode == this.chosen, !this.running);
    }

    /**
     * What the Inbox card says.
     *
     * <p>Only a read with nothing behind it yet says it is counting. A read refreshing numbers
     * already on the card leaves them there. Blanking a figure somebody is reading, every time a
     * run ends, is worse than a figure a second out of date.
     *
     * @return {@link InboxCard} the card's lines and whether it is still counting
     */
    private InboxCard inboxCard() {
        final InboxTally waiting = this.inbox;
        if (this.counting && waiting == null && !this.countsUnreadable) {
            return new InboxCard(INBOX_COUNTING, null);
        }
        if (this.countsUnreadable || waiting == null) {
            return new InboxCard(INBOX_UNREADABLE, null);
        }
        if (waiting.files() == 0) {
            return new InboxCard(INBOX_EMPTY, INBOX_EMPTY_DETAIL);
        }
        return new InboxCard(counted(waiting.files(), "photo or video", "photos and videos"),
                sized(waiting.bytes()));
    }

    /**
     * What the Sorted card says in place of rows, where it has none to show.
     *
     * <p>Silent while the read that would answer has failed, and while none has finished. An empty
     * list means the walk never got far enough in either case, not that a folder is empty. Saying
     * nothing is staged would claim something nobody could look at. Worse, the advice attached to
     * it is to run the very thing that just failed. The Inbox card reports the failure for both.
     *
     * <p>A read merely in flight keeps the last answer, the way the Inbox card keeps its figures.
     * The two cards describe the same moment and must not take opposite views of it.
     *
     * @return {@link String} the line, or null where the rows themselves answer
     */
    private @Nullable String nothingStagedLine() {
        if (this.countsUnreadable || this.sorted == null || !this.yearChoices().isEmpty()) {
            return null;
        }
        return NOTHING_STAGED;
    }

    /**
     * The Sorted rows, newest year first.
     *
     * @return a {@link List} of {@link YearChoice} one per year holding anything
     */
    private List<YearChoice> yearChoices() {
        // Marked from the year that was typed, not the scope it resolves to. Clicking a row writes
        // that year into the field. A mode that then refuses it would put the row straight back
        // out, as though the click had missed.
        //
        // Nothing marked at all where the run reads the Inbox. These are Sorted years, holding
        // Sorted counts, and a sort works on files the Inbox holds. A marked row would say this one
        // describes the run, and the number beside it counts a different folder.
        final Typed typed = parse(this.scopeText);
        final int selected = this.readsSorted() ? this.typedYear() : 0;
        final List<Integer> narrowed = typed instanceof Typed.OfYear(int _, final List<Integer> months)
                ? months
                : List.of();
        return this.stagedYears().stream()
                .map(row -> new YearChoice(row.year(), "run-year-" + row.year(),
                        String.valueOf(row.year()), held(row), row.year() == selected,
                        row.year() == selected && !this.monthsCollapsed,
                        monthChoices(row, row.year() == selected ? narrowed : List.of())))
                .toList();
    }

    /**
     * The month rows under one year.
     *
     * <p>Only the months that hold something. A month nothing was filed under is a row that would
     * refuse the very scope clicking it writes.
     *
     * <p>Videos counted alongside the photos, in the same words the year row above uses. A move
     * takes both, so a row naming photos alone would understate what clicking it does. A month
     * holding only video would have no row at all, while the year above it counted that video. A
     * sift narrowed to such a month finds nothing to look at, which its own empty-scope refusal
     * says.
     *
     * @param row {@link YearRow} the year to break down
     * @param narrowed a {@link List} of {@link Integer} the months the scope names, empty for none
     * @return a {@link List} of {@link MonthChoice} one per month holding anything
     */
    private static List<MonthChoice> monthChoices(final YearRow row, final List<Integer> narrowed) {
        return row.months().stream()
                .filter(month -> month.photos() > 0 || month.videos() > 0)
                .sorted(Comparator.comparingInt(MonthRow::month))
                .map(month -> new MonthChoice(month.month(),
                        "run-month-" + row.year() + "-" + month.month(),
                        Month.of(month.month()).getDisplayName(TextStyle.FULL, Locale.UK),
                        held(month.photos(), month.videos()),
                        narrowed.contains(month.month())))
                .toList();
    }

    /**
     * What one year's row says it holds.
     *
     * @param row {@link YearRow} the year's counts
     * @return {@link String} the counts written out
     */
    private static String held(final YearRow row) {
        return held(row.photos(), row.videos());
    }

    /**
     * What a year or one of its months says it holds.
     *
     * @param photos int the photos in it
     * @param videos int the videos in it
     * @return {@link String} the counts written out
     */
    private static String held(final int photos, final int videos) {
        if (videos == 0) {
            return counted(photos, "photo", "photos");
        }
        if (photos == 0) {
            return counted(videos, "video", "videos");
        }
        return counted(photos, "photo", "photos") + " and " + counted(videos, "video", "videos");
    }

    /**
     * What is staged, or nothing while the walk that would say is still going.
     *
     * @return a {@link List} of {@link YearRow} the staged years, newest first
     */
    private List<YearRow> stagedYears() {
        final SortedTally staged = this.sorted;
        return staged == null ? List.of() : staged.years();
    }

    /**
     * What the screen says about money for the chosen mode and scope.
     *
     * <p>Only the two modes that reach a vision provider say anything. A sort and a move to the
     * library spend nothing whatever the provider is, so a line beside either would be answering a
     * question nobody asked.
     *
     * <p>Those two always say something. Where the provider spends nothing the box says so and
     * carries the disclaimer, since what a user's own agent costs them is not Sluice's to know.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return {@link Cost} what to say about money, or null where this mode never spends
     */
    private @Nullable Cost cost(final Scope scope) {
        if (!this.reachesAProvider()) {
            return null;
        }
        if (!this.pipeline.configuredProviderSpends()) {
            return new Cost.Free(this.chosen.verb() + " costs you nothing through Sluice.",
                    FREE_DETAIL);
        }
        return this.figureFor(scope);
    }

    /**
     * Whether the chosen mode has a vision provider look at anything.
     *
     * @return boolean true for the two modes that sift
     */
    private boolean reachesAProvider() {
        return this.chosen == RunMode.SIFT || this.chosen == RunMode.CURATE;
    }

    /**
     * The expected cost of sifting the chosen scope, where one can be worked out.
     *
     * <p>A curate carries no figure, and the reason is that nothing can compute one. It sorts
     * first, and which photos that sort files under which year is what the dating pass decides.
     * Sizing it on the whole Inbox would be the wrong scope, by whatever multiple the sort narrows
     * by. On a question about money, a wrong number is worse than none. The hint under the field
     * says as much, and the confirm before a curate says it again.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return {@link Cost.Estimate} the figure and what it is worth, or null where none can be given
     */
    private Cost.@Nullable Estimate figureFor(final Scope scope) {
        if (!startable(scope) || this.chosen != RunMode.SIFT) {
            return null;
        }
        final int photos = this.photosIn(scope);
        if (photos == 0) {
            return null;
        }
        final SpendEstimate expected = this.expectedFor(photos);
        // A spending provider forecasting nothing is a state nothing produces today. A figure of
        // zero tokens beside a money disclaimer would be the wrong thing to draw for it.
        if (expected.totalTokens() == 0) {
            return null;
        }
        return new Cost.Estimate("About " + rounded(expected.totalTokens()) + " tokens", DISCLAIMER,
                expected.historicOutput() ? null : WITHOUT_HISTORY);
    }

    /**
     * What a sift over this many photos is expected to consume, asked once per count.
     *
     * <p>The facade reads the spend ledger on every call, and this screen asks on every keystroke.
     * Typing a year asks the same question several times over, since the count only moves when the
     * scope resolves to a different set of photos.
     *
     * <p>Held only until the next read of the folders. A finished run adds to the ledger, and
     * {@link #refreshCounts} is what runs after one.
     *
     * @param photos int how many photos the scope covers
     * @return {@link SpendEstimate} what the facade says about that many
     */
    private SpendEstimate expectedFor(final int photos) {
        // Key first, then the value, against a writer that does it the other way round. Reading the
        // value first would let a stale one be handed back beside a key that had already moved on.
        final int covered = this.lastEstimateCovered;
        final SpendEstimate held = this.lastEstimate;
        if (covered == photos && held != null) {
            return held;
        }
        final SpendEstimate asked = this.pipeline.estimateFor(photos);
        this.lastEstimate = asked;
        this.lastEstimateCovered = photos;
        return asked;
    }

    /**
     * How many staged photos a scope covers.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return int the photos in range, zero where the scope names a year holding none
     */
    private int photosIn(final Scope scope) {
        if (!(scope instanceof Scope.OfYear(final int year, final List<Integer> months))) {
            return 0;
        }
        return this.stagedYears().stream()
                .filter(row -> row.year() == year)
                .mapToInt(row -> months.isEmpty() ? row.photos() : row.photosIn(months))
                .sum();
    }

    /**
     * Whether the start button is live.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return boolean true when there is something to start
     */
    private boolean canStart(final Scope scope) {
        return !this.running && !this.counting && this.chosen != RunMode.RESCUE
                && startable(scope) && !this.pipeline.isBusy();
    }

    /**
     * Puts the counting state on the screen, but only if the read is still going by then.
     *
     * <p>Nothing cancels this. It asks whether a read is still running when it wakes, and one that
     * has finished leaves it with nothing to draw. A second read started in the meantime is the
     * same answer for a different walk, which is the state the screen should be in anyway.
     */
    private void deadButtonOnceThisIsSlow() {
        CompletableFuture.runAsync(
                () -> {
                    if (this.counting) {
                        this.repaint();
                    }
                },
                CompletableFuture.delayedExecutor(SETTLE_BEFORE_SAYING_SO, TimeUnit.MILLISECONDS));
    }

    /**
     * Whether a scope names work at all.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return boolean true where there is something for a run to take
     */
    private static boolean startable(final Scope scope) {
        return switch (scope) {
            case Scope.OldestYear _, Scope.Everything _, Scope.OfYear _ -> true;
            case Scope.Refused _, Scope.Nothing _ -> false;
        };
    }

    /**
     * Hands the chosen work to the facade.
     *
     * @param ran {@link RunMode} the mode being started
     * @param scope {@link Scope} what the field and mode come to
     * @return a {@link JobHandle} of the job's own result
     */
    private JobHandle<?> submit(final RunMode ran, final Scope scope) {
        return switch (ran) {
            case SORT -> this.pipeline.sort(sortScope(scope));
            case CURATE -> this.pipeline.curate(sortScope(scope));
            case SIFT -> this.pipeline.cull(cullScope(scope));
            case MOVE_TO_LIBRARY -> this.pipeline.commit(commitScope(scope));
            case RESCUE -> throw new IllegalStateException("Rescue cannot be started from here yet");
        };
    }

    /**
     * What the field and the chosen mode come to together.
     *
     * @return {@link Scope} the parsed scope, or a refusal saying what is wrong with it
     */
    private Scope scope() {
        // A run over the Inbox takes the oldest year in it, whatever the field holds. Nothing on
        // this screen could tell a reader which years the Inbox has. Finding that out means reading
        // a date off every file in it, which is the work a sort does. So the field is left out of
        // it rather than asking for a year nobody can check.
        if (this.readsInbox()) {
            return this.blankScope();
        }
        final Typed typed = parse(this.scopeText);
        return switch (typed) {
            case Typed.Refused(final String why) -> new Scope.Refused(why);
            case Typed.Blank _ -> this.blankScope();
            case Typed.OfYear(final int year, final List<Integer> months) -> this.yearScope(year, months);
        };
    }

    /**
     * What an empty scope field means for the chosen mode.
     *
     * @return {@link Scope} the scope it stands for, or a refusal where it stands for none
     */
    private Scope blankScope() {
        return switch (this.chosen) {
            // The oldest year of an empty Inbox is no year at all, so the run would be over nothing.
            case SORT, CURATE -> this.inboxIsEmpty() ? new Scope.Nothing() : new Scope.OldestYear();
            // Everything means everything staged, and on an install with nothing staged that is a
            // run over no files behind a confirm naming none of them. The Sorted card says so, in
            // the same words a line here would use and in a tone that does not read as a fault.
            // Everything is the one press that reaches the whole library at once, and the confirm
            // in front of it names the years and the file count. A read that failed leaves neither
            // knowable, so the press is withheld rather than offered without its question. A typed
            // year still goes through: it is bounded, and the facade names the folder at fault.
            case MOVE_TO_LIBRARY -> this.countsAreIn() && !this.stagedYears().isEmpty()
                    ? new Scope.Everything()
                    : new Scope.Nothing();
            // A sift has no oldest-year to fall back on, deliberately. It reads Sorted, where
            // every year is equally ready and none of them is the one next in line. So a blank
            // field is where a sift starts rather than something gone wrong, and the hint under it
            // already says to pick a year.
            case SIFT, RESCUE -> new Scope.Nothing();
        };
    }

    /**
     * What a year, and possibly some months, mean for the chosen mode.
     *
     * @param year int the year typed
     * @param months a {@link List} of {@link Integer} the months typed, empty for the whole year
     * @return {@link Scope} the scope it stands for, or a refusal where the mode cannot take it
     */
    private Scope yearScope(final int year, final List<Integer> months) {
        if (this.chosen == RunMode.RESCUE) {
            return new Scope.Nothing();
        }
        if (this.readsSorted() && this.countsAreIn()
                && this.stagedYears().stream().noneMatch(row -> row.year() == year)) {
            return new Scope.Refused("Nothing is sorted for " + year + ".");
        }
        if (this.chosen == RunMode.SIFT) {
            final Scope sift = new Scope.OfYear(year, months);
            // A year can hold videos alone, and months can be named that hold nothing. Both leave a
            // sift with no photo to look at, and neither is caught by the year check above. Said
            // here rather than left to an absent cost line, which a free provider draws too.
            return this.countsAreIn() && this.photosIn(sift) == 0
                    ? new Scope.Refused(months.isEmpty()
                            ? "No photos are sorted for " + year + ", so there is nothing to sift."
                            : "No photos are sorted for the chosen months of " + year
                                    + ", so there is nothing to sift.")
                    : sift;
        }
        // Moving to the library narrows by a run of months rather than a set of them. The type it
        // builds cannot hold a gap. A gapped list is widened to the run that spans it where that
        // takes nothing extra, and refused where it would.
        if (!months.isEmpty() && !contiguous(months)) {
            final List<Integer> blocking = this.gapHolds(year, months);
            if (blocking != null && blocking.isEmpty()) {
                return new Scope.OfYear(year, months);
            }
            // The way out is worded for a click as much as for a keystroke. Three presses on the
            // rows reach this state without the field being touched, and an answer that only says
            // what to type names nothing the user did.
            return new Scope.Refused(this.chosen.verb()
                    + " narrows to a run of months, not a list. Reading "
                    + joined(months) + " as " + months.getFirst() + "-" + months.getLast()
                    + " would take " + wouldAlsoTake(blocking) + ". Choose months that run "
                    + "together, like 6-8, or none at all for the whole year.");
        }
        return new Scope.OfYear(year, months);
    }

    /**
     * Whether the chosen mode works on what is staged in Sorted rather than on the Inbox.
     *
     * @return boolean true for the two modes that read Sorted
     */
    private boolean readsSorted() {
        return this.chosen == RunMode.SIFT || this.chosen == RunMode.MOVE_TO_LIBRARY;
    }

    /**
     * The months the field names under one year, empty where it names that year alone or another.
     *
     * @param year int the year to read months for
     * @return a {@link List} of {@link Integer} the months covered, in the order the field spells
     */
    private List<Integer> monthsNamedFor(final int year) {
        return parse(this.scopeText) instanceof Typed.OfYear(final int named, final List<Integer> months)
                && named == year
                ? months
                : List.of();
    }

    /**
     * The year the field names, or zero where it names none.
     *
     * @return int the typed year
     */
    private int typedYear() {
        return parse(this.scopeText) instanceof Typed.OfYear(final int year, List<Integer> _) ? year : 0;
    }

    /**
     * Whether the chosen mode takes its work from the Inbox.
     *
     * @return boolean true for the two modes that read the Inbox
     */
    private boolean readsInbox() {
        return this.chosen == RunMode.SORT || this.chosen == RunMode.CURATE;
    }

    /**
     * Whether a finished read found the Inbox holding nothing.
     *
     * <p>False while the count is still going, and false where it could not be read. Refusing on
     * either would be refusing over an answer nobody has yet.
     *
     * @return boolean true only where the Inbox is known to be empty
     */
    private boolean inboxIsEmpty() {
        final InboxTally waiting = this.inbox;
        return this.countsAreIn() && waiting != null && waiting.files() == 0;
    }

    /**
     * Whether a finished read has actually answered.
     *
     * <p>An empty list of years means three different things: nothing is staged, the walk has not
     * got there yet, and the walk failed. Only the first is a fact about somebody's folders, and
     * every refusal built on that list has to know which one it is holding.
     *
     * @return boolean true only where a completed read succeeded
     */
    private boolean countsAreIn() {
        return !this.counting && !this.countsUnreadable;
    }

    /**
     * What is wrong with the scope, where anything is.
     *
     * <p>A mode with nothing behind it yet says so in its hint and nowhere else. Its refusal is the
     * same sentence, and showing both puts one statement on the screen twice. The second copy would
     * carry the colour that means the user typed something wrong. Nothing they typed is wrong.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return {@link String} the refusal, or null while nothing is wrong
     */
    private @Nullable String refusalOf(final Scope scope) {
        if (this.chosen == RunMode.RESCUE) {
            return null;
        }
        return scope instanceof Scope.Refused(final String why) ? why : null;
    }

    /**
     * Reads the scope field's text, without knowing which mode will take it.
     *
     * @param text {@link String} the field's text
     * @return {@link Typed} the year and months in it, or why it could not be read
     */
    private static Typed parse(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new Typed.Blank();
        }
        final String[] parts = trimmed.split("\\s+", 2);
        if (!parts[0].matches("\\d{4}")) {
            return new Typed.Refused("A scope starts with a four-digit year, like 2019.");
        }
        final int year = Integer.parseInt(parts[0]);
        return parts.length == 1 ? new Typed.OfYear(year, List.of()) : months(year, parts[1]);
    }

    /**
     * Reads the month part of a scope.
     *
     * @param year int the year already read
     * @param text {@link String} everything after the year
     * @return {@link Typed} the year and its months, or why they could not be read
     */
    private static Typed months(final int year, final String text) {
        final List<Integer> months = new ArrayList<>();
        for (final String part : text.split(",")) {
            final String piece = part.trim();
            final Typed refusal = refusedFormat(piece);
            if (refusal != null) {
                return refusal;
            }
            months.addAll(monthsIn(piece));
        }
        return new Typed.OfYear(year, months.stream().distinct().sorted().toList());
    }

    /**
     * What is wrong with one comma-separated piece of the month part, where anything is.
     *
     * <p>A piece is one month or a run between two. This only judges it, and {@link #monthsIn}
     * only reads it. Kept apart because one method doing both would add months as a side effect of
     * being asked about refusals. Nothing named after the answer it returns should also be
     * changing something.
     *
     * @param part {@link String} the piece to judge
     * @return {@link Typed} the refusal, or null where the piece is a legal one
     */
    private static @Nullable Typed refusedFormat(final String part) {
        final String[] ends = part.split("-", 2);
        final OptionalInt first = monthIn(ends[0]);
        final OptionalInt last = ends.length == 1 ? first : monthIn(ends[1]);
        if (first.isEmpty() || last.isEmpty()) {
            return new Typed.Refused("Months are numbers from 1 to 12, like 6 or 6-8.");
        }
        if (last.getAsInt() < first.getAsInt()) {
            return new Typed.Refused("A run of months goes from the earlier one to the later, like 6-8.");
        }
        return null;
    }

    /**
     * The months one comma-separated piece covers, which is one month or a whole run of them.
     *
     * <p>Reads a piece {@link #refusedFormat} has already passed, so both ends are known to be
     * months and known to be the right way round. Asked about anything else it fails loudly rather
     * than answering an empty run, since an empty answer would read as a piece covering no months.
     *
     * @param part {@link String} the piece to read
     * @return a {@link List} of {@link Integer} every month it covers, in order
     */
    private static List<Integer> monthsIn(final String part) {
        final String[] ends = part.split("-", 2);
        final int first = monthIn(ends[0]).orElseThrow(RunLauncherPresenter::notAMonth);
        final int last = ends.length == 1 ? first
                : monthIn(ends[1]).orElseThrow(RunLauncherPresenter::notAMonth);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    /**
     * What to throw when a piece reaches {@link #monthsIn} without being a legal one.
     *
     * @return {@link IllegalStateException} for whoever finds this in a log
     */
    private static IllegalStateException notAMonth() {
        return new IllegalStateException("months were read from an unchecked scope piece");
    }

    /**
     * One month read from text.
     *
     * @param text {@link String} the text to read
     * @return {@link OptionalInt} the month, empty where the text is not one
     */
    private static OptionalInt monthIn(final String text) {
        final String trimmed = text.trim();
        if (!trimmed.matches("\\d{1,2}")) {
            return OptionalInt.empty();
        }
        final int month = Integer.parseInt(trimmed);
        return month >= 1 && month <= 12 ? OptionalInt.of(month) : OptionalInt.empty();
    }

    /**
     * Whether a sorted, deduplicated month list is a run with no gap in it.
     *
     * @param months a {@link List} of {@link Integer} the months, sorted and deduplicated
     * @return boolean true when they run end to end
     */
    private static boolean contiguous(final List<Integer> months) {
        return months.getLast() - months.getFirst() + 1 == months.size();
    }

    /**
     * The months a gapped list skips over that hold something, so spanning them would take more
     * than was asked for.
     *
     * <p>An empty answer is what lets every month a year offers be chosen at once. A year holding
     * June, July and November has a gap by the calendar and none in what is filed. A run from June
     * to November reaches exactly the three rows that were pressed.
     *
     * <p>Unanswerable until the counts are in, since they are what says whether a skipped month
     * holds anything. Unanswered is not the same as empty, and the refusal that follows then says
     * only that months would be taken rather than naming ones nobody has counted.
     *
     * @param year int the year the months belong to
     * @param months a {@link List} of {@link Integer} the months chosen, sorted and deduplicated
     * @return a {@link List} of {@link Integer} what the gap holds, or null where that cannot be said
     */
    private @Nullable List<Integer> gapHolds(final int year, final List<Integer> months) {
        if (!this.countsAreIn()) {
            return null;
        }
        final List<Integer> filed = this.stagedYears().stream()
                .filter(row -> row.year() == year)
                .flatMap(row -> row.months().stream())
                .filter(month -> month.photos() > 0 || month.videos() > 0)
                .map(MonthRow::month)
                .toList();
        return IntStream.rangeClosed(months.getFirst(), months.getLast())
                .boxed()
                .filter(month -> filed.contains(month) && !months.contains(month))
                .toList();
    }

    /**
     * The months a widened run would take on top of the ones chosen, as a sentence names them.
     *
     * @param blocking a {@link List} of {@link Integer} the months in the gap that hold something,
     *     or null where the counts cannot say which they are
     * @return {@link String} what the run would take, to sit inside the refusal
     */
    private static String wouldAlsoTake(final @Nullable List<Integer> blocking) {
        if (blocking == null) {
            return "months you did not ask for";
        }
        return listed(blocking.stream()
                .map(month -> Month.of(month).getDisplayName(TextStyle.FULL, Locale.UK))
                .toList()) + " too";
    }

    /**
     * The sort scope a parsed scope stands for.
     *
     * @param scope {@link Scope} the parsed scope
     * @return {@link SortScope} what the engine is asked for
     */
    private static SortScope sortScope(final Scope scope) {
        return switch (scope) {
            case Scope.OfYear(final int year, final List<Integer> months) ->
                    new SortScope.Year(year, range(months));
            case Scope.OldestYear _, Scope.Everything _ -> new SortScope.OldestYear();
            // Oldest-year is the widest thing a sort can be asked for, so a scope that names no
            // work must not land in it. Curate reaches here too, and its second half spends.
            case Scope.Refused _, Scope.Nothing _ ->
                    throw new IllegalStateException("A sort was started from a scope naming no work");
        };
    }

    /**
     * The cull scope a parsed scope stands for.
     *
     * @param scope {@link Scope} the parsed scope
     * @return {@link CullScope} what the engine is asked for
     */
    private static CullScope cullScope(final Scope scope) {
        if (scope instanceof Scope.OfYear(final int year, final List<Integer> months)) {
            return new CullScope.Year(year, months.isEmpty() ? null : months);
        }
        throw new IllegalStateException("A sift is only ever started against a year");
    }

    /**
     * The commit scope a parsed scope stands for.
     *
     * @param scope {@link Scope} the parsed scope
     * @return {@link CommitScope} what the engine is asked for
     */
    private static CommitScope commitScope(final Scope scope) {
        return switch (scope) {
            case Scope.OfYear(final int year, final List<Integer> months) ->
                    new CommitScope.Year(year, range(months));
            case Scope.Everything _, Scope.OldestYear _ -> new CommitScope.All();
            // Everything else here widens to the whole library, so a scope naming no work must not
            // fall into it.
            case Scope.Refused _, Scope.Nothing _ ->
                    throw new IllegalStateException("A move to the library was started from a scope naming no work");
        };
    }

    /**
     * A run of months as the range type both sort and commit narrow by.
     *
     * @param months a {@link List} of {@link Integer} the months, already proved to be a run
     * @return {@link MonthRange} the range, or null for a whole year
     */
    private static @Nullable MonthRange range(final List<Integer> months) {
        return months.isEmpty() ? null : new MonthRange(months.getFirst(), months.getLast());
    }

    /**
     * A failure as a sentence, falling back to the type where it carries no message.
     *
     * <p>Most of what reaches here was written for the person reading it, and those messages are
     * better than anything this screen could compose. What has none would otherwise render as a
     * blank, so the type's own name stands in as something to search for.
     *
     * @param failure {@link Throwable} what went wrong
     * @return {@link String} the sentence to show
     */
    /**
     * What to say about a timeline a sift is already sitting on.
     *
     * <p>The exception's own message names the prep dir and the raw state, which is what a log
     * needs. A reader needs to know their earlier sift is still there, and why this one stopped.
     *
     * <p>It names no way out, because today there is none to name. The screen that lists unfinished
     * sifts and offers to continue or discard one arrives with the runs list. Saying so here would
     * be a remedy pointing at nothing.
     *
     * @param occupied {@link Pipeline.ScopeOccupiedException} the refusal, carrying the run
     * @return {@link String} the sentence to show
     */
    private static String occupiedBy(final Pipeline.ScopeOccupiedException occupied) {
        return "You already have a sift of " + occupied.occupant().scope() + " that has not finished. "
                + "Sluice will not start another for the same timeline while that one is there.";
    }

    private static String plainly(final Throwable failure) {
        return switch (failure) {
            // These two are refusals this app writes for the person meeting them, and each says
            // what to do about itself.
            case final JobInProgressException refused -> messageOf(refused);
            case final ShuttingDownException closing -> messageOf(closing);
            // This one carries a message built for a log, down to the configuration key that is
            // wrong. Which folder is at fault is the part a reader needs, in the words the rest of
            // this app calls that folder by.
            case final PathsMisconfiguredException misconfigured -> foldersAtFault(misconfigured);
            // A curate that already moved files before being refused. Said first, because what it
            // did is the part a reader cannot see and would otherwise go looking for.
            case final Pipeline.CurateConflictException conflict -> "Your photos were sorted, and then "
                    + "sifting stopped: " + occupiedBy(conflict) + " The sorting stands.";
            case final Pipeline.ScopeOccupiedException occupied -> occupiedBy(occupied);
            case final Pipeline.ScopeUnreadableException unreadable -> "Sluice could not read "
                    + unreadable.prepDir() + ", so it cannot tell whether a sift is already running "
                    + "for that timeline. Try again once whatever is holding that folder has let go.";
            case final Pipeline.RunOutsideWorkingRootException outside -> "That sift is at "
                    + outside.prepDir() + ", which is not inside the folders Sluice is set up with "
                    + "now. Point your working folder back at the one holding it, or discard the sift.";
            // Nothing here was written for a reader, so the words are this screen's and the
            // technical text rides along verbatim. Quoting it is what makes the bug report worth
            // filing, and this screen is the only place the user can copy it from.
            default -> "Sluice could not do that, and has no plain words for why. "
                    + "Report this as a bug in Sluice, quoting this: " + failure;
        };
    }

    /**
     * Which folders one violation is about.
     *
     * <p>An overlap is the one kind that is about two of them, and neither is at fault on its own.
     * The pair are named together, and the sentence they land in says only to go and look.
     *
     * @param violation {@link PathViolation} what was found wrong
     * @return a {@link List} of {@link PathRole} the folders it names
     */
    private static List<PathRole> rolesIn(final PathViolation violation) {
        return switch (violation) {
            case PathViolation.NotConfigured(final PathRole role) -> List.of(role);
            case PathViolation.NotAPath(final PathRole role, String _) -> List.of(role);
            case PathViolation.NotADirectory(final PathRole role, Path _) -> List.of(role);
            case PathViolation.Unreadable(final PathRole role, Path _) -> List.of(role);
            case PathViolation.Overlap(final PathRole first, final PathRole second) ->
                    List.of(first, second);
        };
    }

    /**
     * A refusal's own sentence, falling back to its type where it carries none.
     *
     * @param refusal {@link RuntimeException} a refusal written for the person meeting it
     * @return {@link String} the sentence to show
     */
    private static String messageOf(final RuntimeException refusal) {
        final String said = refusal.getMessage();
        return said == null || said.isBlank()
                ? "Sluice stopped, and said nothing about why."
                : said;
    }

    /**
     * Which folders a refused run found unusable, named the way the rest of this app names them.
     *
     * <p>The exception's own message is built for a log and carries the configuration key rather
     * than the folder. A reader has never seen that key and cannot act on it.
     *
     * @param misconfigured {@link PathsMisconfiguredException} what the facade refused with
     * @return {@link String} the sentence to show
     */
    private static String foldersAtFault(final PathsMisconfiguredException misconfigured) {
        final List<String> folders = misconfigured.violations().stream()
                .flatMap(violation -> rolesIn(violation).stream())
                .distinct()
                .sorted()
                .map(PathRoleLabels::of)
                .toList();
        return folders.isEmpty()
                ? "Sluice cannot work with your folder settings. Check them in Settings."
                : "Sluice cannot use your " + listed(folders) + " any more. "
                        + (folders.size() == 1 ? "Check it in Settings." : "Check them in Settings.");
    }

    /**
     * A count with its noun, singular or plural to match.
     *
     * @param count int how many
     * @param one {@link String} the noun for one
     * @param many {@link String} the noun for anything else
     * @return {@link String} the count and its noun
     */
    private static String counted(final int count, final String one, final String many) {
        return grouped(count) + " " + (count == 1 ? one : many);
    }

    /**
     * A number with thousands separated, the way somebody reading it would write it.
     *
     * @param value long the number
     * @return {@link String} the number written out
     */
    private static String grouped(final long value) {
        return String.format(Locale.UK, "%,d", value);
    }

    /**
     * A token count at the precision the estimate actually has.
     *
     * <p>Rounded to two figures. The arithmetic behind it is an average over past runs, multiplied
     * by a montage count. A figure written out to the last token would claim a precision no part of
     * it holds.
     *
     * @param tokens long the estimated tokens
     * @return {@link String} the figure to show
     */
    private static String rounded(final long tokens) {
        final long scale = (long) Math.pow(10, Math.max(0, String.valueOf(tokens).length() - 2));
        return grouped(Math.round((double) tokens / scale) * scale);
    }

    /**
     * A size as somebody would say it.
     *
     * @param bytes long the size on disk
     * @return {@link String} the size written out
     */
    private static String sized(final long bytes) {
        final String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0
                ? grouped(bytes) + " bytes"
                : String.format(Locale.UK, "%.1f %s", size, units[unit]);
    }

    /**
     * Several things read as one phrase.
     *
     * @param names a {@link List} of {@link String} at least one thing
     * @return {@link String} the things joined the way a sentence joins them
     */
    private static String listed(final List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /**
     * A month list written out, in order and with each named once.
     *
     * <p>Not what was typed. A run is expanded into the months it covers and the whole list is
     * sorted, so {@code 11,6,8} comes back as {@code 6,8,11}. What the refusal quotes is therefore
     * the set the text came to rather than the text itself, which is the thing being refused.
     *
     * @param months a {@link List} of {@link Integer} the months
     * @return {@link String} the months joined by commas
     */
    private static String joined(final List<Integer> months) {
        return months.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    /**
     * A question a user answers before work starts.
     *
     * @param heading {@link String} what the question is about
     * @param question {@link String} the question itself, naming what is about to happen
     * @param goAhead {@link String} what the button that goes ahead says
     * @param cancel {@link String} what the button that backs out says
     */
    public record Confirmation(String heading, String question, String goAhead, String cancel) {
    }

    /**
     * What the scope field's text amounts to before a mode has been applied to it.
     */
    private sealed interface Typed {

        /** Nothing has been typed. */
        record Blank() implements Typed {
        }

        /**
         * A year, and the months narrowed to within it.
         *
         * @param year int the year
         * @param months a {@link List} of {@link Integer} the months, sorted and deduplicated,
         *     empty for the whole year
         */
        record OfYear(int year, List<Integer> months) implements Typed {
        }

        /**
         * The text could not be read as a scope.
         *
         * @param why {@link String} what is wrong with it
         */
        record Refused(String why) implements Typed {
        }
    }

    /**
     * What the field and the chosen mode come to together.
     */
    private sealed interface Scope {

        /** Whichever year is oldest in the Inbox, picked during the run. */
        record OldestYear() implements Scope {
        }

        /** Everything staged, with no year filter at all. */
        record Everything() implements Scope {
        }

        /**
         * One year, and the months narrowed to within it.
         *
         * @param year int the year
         * @param months a {@link List} of {@link Integer} the months, empty for the whole year
         */
        record OfYear(int year, List<Integer> months) implements Scope {
        }

        /**
         * This mode cannot take what the field says.
         *
         * @param why {@link String} what is wrong with it
         */
        record Refused(String why) implements Scope {
        }

        /**
         * There is nothing for this mode to work on, and the cards above have already said so.
         *
         * <p>Apart from {@link Refused} because it carries no sentence. An empty Inbox on a new
         * install is the ordinary state of one, not a fault. A line under the field would put it in
         * the tone a screen keeps for something being wrong.
         */
        record Nothing() implements Scope {
        }
    }
}
