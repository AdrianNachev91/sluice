package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Estimate;
import photos.sluice.adapter.ui.RunLauncherView.InboxCard;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.job.WaitingCullJob;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

    // The one string here that is a placeholder rather than an answer. It names the missing screen
    // rather than an empty result, so that a Review folder holding files is never called empty.
    private static final String RESCUE_NOT_READY = "Rescue arrives with the Review screen.";

    // How long a folder read is given before the screen says it is reading. Under this, a reader
    // sees one state rather than three. Over it, they are waiting and want to know why.
    private static final long SETTLE_BEFORE_SAYING_SO = 200;

    // Asked every time, because a curate is the one run whose cost cannot be shown before it starts.
    private static final Confirmation CURATE_CONFIRM = new Confirmation(
            "Sort and sift the oldest year?",
            "Sluice sorts the oldest year in your Inbox, then sifts every photo it just sorted. "
                    + "Sifting is what spends money, and the size of the sift cannot be estimated "
                    + "until sorting has finished.",
            "Sort and sift", "Cancel");

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
     * Creates the presenter over the facade it reads counts from and starts work through.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     */
    public RunLauncherPresenter(final Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * What the launcher draws right now.
     *
     * @return {@link RunLauncherView} every value the screen puts on the page
     */
    public RunLauncherView view() {
        final Scope scope = this.scope();
        return new RunLauncherView(this.modes(), explained(this.chosen), this.inboxCard(),
                this.yearChoices(), this.readsSorted(),
                this.nothingStagedLine(), SCOPE_LABEL, this.scopeText, hintFor(this.chosen),
                this.refusalOf(scope), this.estimate(scope), started(this.chosen), this.canStart(scope),
                this.message);
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
            // A run that just finished spent against the ledger the estimate is averaged from, and
            // this is what runs after one.
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
     * <p>Curate always does. It is the one mode that spends without showing a figure first. A sort
     * decides which photos land under which year, so nothing can be sized until it has run. Every
     * other spending run either shows what it will cost or costs nothing.
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
            return CURATE_CONFIRM;
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
        this.running = true;
        this.message = null;
        // Read once, and everything below works from it. The outcome line names the work that
        // ended, and the mode can move under a job in flight.
        final RunMode ran = this.chosen;
        try {
            this.submit(ran, scope).whenComplete((outcome, failure) -> {
                this.running = false;
                if (failure != null) {
                    // The line this failure renders as sends the reader to the log for the real
                    // error. JobRunner catches Throwable and completes the future without writing
                    // anything, so an engine that threw without logging first would leave that
                    // promise pointing at nothing.
                    log.warn("{} failed", ran, failure);
                }
                this.message = failure == null
                        ? new Message(endedLine(ran, outcome), false)
                        : new Message(stoppedLine(ran, failure), true);
                this.repaint();
                this.recount();
            });
        } catch (final RuntimeException e) {
            // Everything the facade refuses outright arrives here, before any job exists. A job
            // already running, an app on its way out, a folder root gone bad since this screen was
            // drawn. Each carries a sentence written for the person reading it.
            log.info("Refused to start {}", ran, e);
            this.running = false;
            this.message = new Message(plainly(e), true);
        }
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
                this.modeChoice(RunMode.SORT, "run-mode-sort", "Sort"),
                this.modeChoice(RunMode.SIFT, "run-mode-sift", "Sift"),
                this.modeChoice(RunMode.MOVE_TO_LIBRARY, "run-mode-move", "Move to library"),
                this.modeChoice(RunMode.CURATE, "run-mode-curate", "Curate"),
                this.modeChoice(RunMode.RESCUE, "run-mode-rescue", "Rescue"));
    }

    /**
     * One mode's button.
     *
     * @param mode {@link RunMode} which mode it starts
     * @param id {@link String} the control's id
     * @param label {@link String} what it says
     * @return {@link ModeChoice} the button
     */
    private ModeChoice modeChoice(final RunMode mode, final String id, final String label) {
        return new ModeChoice(mode, id, label, mode == this.chosen, !this.running);
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
     * What sifting the chosen scope would cost, where it would cost anything.
     *
     * <p>Only the two modes that reach a vision provider have one. A sort and a move to the library
     * spend nothing whatever the provider is, so a figure beside either would be answering a
     * question nobody asked.
     *
     * @param scope {@link Scope} what the field and mode come to
     * @return {@link Estimate} the figure and what it is worth, or null where nothing is spent
     */
    private @Nullable Estimate estimate(final Scope scope) {
        if (!startable(scope)) {
            return null;
        }
        // A curate carries no figure, and the reason is that nothing can compute one. It sorts
        // first, and which photos that sort files under which year is what the dating pass decides.
        // Sizing it on the whole Inbox would be the wrong scope, by whatever multiple the sort
        // narrows by. On a question about money, a wrong number is worse than none.
        final int photos = switch (this.chosen) {
            case SIFT -> this.photosIn(scope);
            case SORT, CURATE, MOVE_TO_LIBRARY, RESCUE -> 0;
        };
        if (photos == 0) {
            return null;
        }
        final SpendEstimate expected = this.expectedFor(photos);
        if (expected.totalTokens() == 0) {
            return null;
        }
        return new Estimate("About " + rounded(expected.totalTokens()) + " tokens", DISCLAIMER,
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
     * <p>Answered by a switch rather than by asking what it is not. A scope added later has to say
     * which side it falls on before this compiles again.
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
     * @return a {@link CompletionStage} of the job's own result
     */
    private CompletionStage<?> submit(final RunMode ran, final Scope scope) {
        return switch (ran) {
            case SORT -> this.pipeline.sort(sortScope(scope)).onComplete();
            case CURATE -> this.pipeline.curate(sortScope(scope)).onComplete();
            case SIFT -> this.pipeline.cull(cullScope(scope)).onComplete();
            case MOVE_TO_LIBRARY -> this.pipeline.commit(commitScope(scope)).onComplete();
            case RESCUE -> throw new IllegalStateException("Rescue cannot be started from here yet");
        };
    }

    /**
     * What the screen says once the work has ended without failing.
     *
     * <p>Ending is not the same as finishing, and a sift is where the two come apart. Its ordinary
     * outcome on the shipped provider is a pause: the montages are built and somebody's own agent
     * still has to judge them. That completes the job without a failure. A line keyed off the
     * absence of one would tell a user their sift was done while it was waiting on them.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome what the job produced, which for a sift or a curate says how it ended
     * @return {@link String} the line to show
     */
    private static String endedLine(final RunMode ran, final @Nullable Object outcome) {
        return switch (outcome) {
            case final CullJobOutcome sift -> siftEndedLine(ran, sift);
            // A curate that never reached its sift stage sorted and stopped, which is a finish.
            case final CurateOutcome curated when curated.cullOutcome() != null ->
                    siftEndedLine(ran, curated.cullOutcome());
            case null, default -> verb(ran) + " finished.";
        };
    }

    /**
     * What the screen says about a sift that has stopped running.
     *
     * <p>Three of the four ways one ends leave work behind, and none of them is a failure. What to
     * do about each is the runs screen's to say. This says only which of the four happened, so
     * nothing here claims a run is done when it is not.
     *
     * @param ran {@link RunMode} the mode that ended
     * @param outcome {@link CullJobOutcome} how the sift ended
     * @return {@link String} the line to show
     */
    private static String siftEndedLine(final RunMode ran, final CullJobOutcome outcome) {
        return switch (outcome) {
            case CullJobOutcome.Applied _ -> verb(ran) + " finished.";
            case CullJobOutcome.Waiting(WaitingCullJob _, final WaitingReason reason, CullReport _, Path _) ->
                    waitingLine(reason);
            case CullJobOutcome.Blocked _ -> verb(ran) + " stopped and needs a look.";
            case CullJobOutcome.Cancelled _ -> verb(ran) + " was cancelled.";
        };
    }

    /**
     * What the screen says about a sift that paused.
     *
     * @param reason {@link WaitingReason} why it paused
     * @return {@link String} the line to show
     */
    private static String waitingLine(final WaitingReason reason) {
        return switch (reason) {
            case SHARDS_OUTSTANDING -> "The photos are ready to be sifted, and Sluice is waiting "
                    + "for your agent's decisions on them.";
            case CEILING_REACHED -> "Sifting stopped because it went far past what it was expected "
                    + "to cost. Nothing more will be spent until you say so.";
            case CANCELLED -> "Sifting was cancelled.";
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
            return new Scope.Refused(verb(this.chosen) + " narrows to a run of months, not a list. Reading "
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
     * What a mode does to somebody's photos, in one sentence.
     *
     * <p>The button row names five actions and says nothing about any of them. A name alone tells a
     * reader which one they picked, never what it is about to do. Two of the five move files out of
     * a folder they will not think to look in afterwards.
     *
     * @param mode {@link RunMode} the mode to explain
     * @return {@link String} what that mode does, in one sentence
     */
    private static String explained(final RunMode mode) {
        return switch (mode) {
            case SORT -> "Reads the dates on what is in your Inbox and moves it into Sorted, by "
                    + "year and month. Takes the oldest year in your Inbox.";
            case SIFT -> "Sifts through your sorted photos and organises them into categories.";
            case MOVE_TO_LIBRARY -> "Moves what is in Sorted into your library.";
            case CURATE -> "Sorts, then sifts automatically. Takes the oldest year in your Inbox.";
            case RESCUE -> "Moves what is left in a Review folder into your library.";
        };
    }

    /**
     * What the button that starts a mode says.
     *
     * <p>Names the work rather than saying Start. The confirm before a move to the library already
     * names its own go-ahead this way. A button saying what it is about to do is one a reader can
     * check against the scope beside it.
     *
     * <p>Run is the verb here, which the vocabulary carve-out allows. What it must not become is the
     * noun: a sift a user started is a sift, never a run.
     *
     * @param mode {@link RunMode} the mode the button would start
     * @return {@link String} what it says
     */
    private static String started(final RunMode mode) {
        return "Run " + switch (mode) {
            case SORT -> "Sort";
            case SIFT -> "Sift";
            case MOVE_TO_LIBRARY -> "Move to library";
            case CURATE -> "Curate";
            case RESCUE -> "Rescue";
        };
    }

    /**
     * How to name what a mode does, at the start of a sentence.
     *
     * @param mode {@link RunMode} the mode to name
     * @return {@link String} the mode as a verb
     */
    private static String verb(final RunMode mode) {
        return switch (mode) {
            case SORT -> "Sorting";
            case SIFT -> "Sifting";
            case MOVE_TO_LIBRARY -> "Moving to your library";
            case CURATE -> "Curating";
            case RESCUE -> "Rescuing";
        };
    }

    /**
     * What the field accepts for a mode, said before anything is typed into it.
     *
     * <p>Stated up front rather than only refused afterwards. The mode is chosen before anybody
     * types, so the screen knows what it will accept and can say so, which the command line cannot.
     *
     * @param mode {@link RunMode} the mode now chosen
     * @return {@link String} the hint under the field
     */
    private static String hintFor(final RunMode mode) {
        return switch (mode) {
            // Neither says which year is taken. The line under the mode buttons already does, and
            // saying it twice leaves two sentences to keep in step.
            case SORT -> "";
            case CURATE -> "Curating sorts your photos before it looks at them, so what the looking "
                    + "costs is not known until the sorting is done.";
            case SIFT -> "Type a year, or click one below. Add a run of months after it like "
                    + "2019 6-8, or pick months out like 2019 6,8,11.";
            case MOVE_TO_LIBRARY -> "Leave this empty to move everything in Sorted. Or type a year, "
                    + "and a run of months after it if you want less, like 2019 6-8.";
            case RESCUE -> RESCUE_NOT_READY;
        };
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
     * What the screen says when work ends badly.
     *
     * <p>Named after the work the user chose, the way the line for work that ended well is. The two
     * outcomes of one press should not name that press two different ways.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param failure {@link Throwable} what the job threw
     * @return {@link String} the line to show
     */
    private static String stoppedLine(final RunMode ran, final Throwable failure) {
        final Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return verb(ran) + " stopped. " + plainly(cause);
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
