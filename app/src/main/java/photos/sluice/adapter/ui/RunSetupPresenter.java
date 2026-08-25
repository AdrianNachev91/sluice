package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.adapter.ui.RunLauncherView.Cost;
import photos.sluice.adapter.ui.RunLauncherView.InboxCard;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.Pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * Decides what the launcher shows and what a press on it would start.
 *
 * <p>It holds what has been chosen so far: the mode, whatever is typed in the scope field, and the
 * counts last read off disk. The screen keeps the controls and asks here after every change. So
 * what a chosen mode makes of a typed scope is settled here, which a test can reach without a
 * window. What the text itself amounts to is {@link RunScopeText}, and what an engine takes is
 * {@link RunScope}.
 *
 * <p>The counts are read by calling {@link #refreshCounts}, which walks two trees and blocks while
 * it does. The caller runs it off whatever thread paints.
 *
 * <p>Whether a job is running is not held here. {@link RunLauncherPresenter} owns that, and answers
 * the question through the supplier handed in.
 */
public class RunSetupPresenter {

    private static final Logger log = LoggerFactory.getLogger(RunSetupPresenter.class);

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

    private static final String INBOX_COUNTING = "Counting what is waiting...";
    private static final String INBOX_EMPTY = "Nothing to sort.";
    // The page rather than the window: the sidebar, the progress area and the result card take no
    // drop.
    private static final String INBOX_EMPTY_DETAIL =
            "Bring some in below, or drop folders anywhere on this page.";

    // A folder, because the picker behind it is a DirectoryChooser and takes one.
    private static final String IMPORT_LABEL = "Import a folder...";
    // The drop target only shows itself once something is already held over it. Without this line
    // the route is found by accident or not at all, and it is the only way loose files get in.
    private static final String IMPORT_HINT = "Or drop folders and files anywhere on this screen.";

    private static final String IMPORT_QUESTION = "Would you like to copy or move your files?";
    private static final String IMPORT_COPY = "Copy";
    private static final String IMPORT_MOVE = "Move";
    private static final String IMPORT_CANCEL = "Cancel";
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
    private final BooleanSupplier jobRunning;
    private final Runnable repaint;

    // Volatile throughout, and the traffic runs both ways. The counts and the flags beside them are
    // written by the read behind the cards and looked at while somebody types. The mode, the field
    // and the line go the other way: written by a press, and looked at by that same read when it
    // draws the screen. Without it a reader can see counting already false while the count is still
    // null, and the Inbox card then reports a failure that never happened.
    private volatile RunMode chosen = RunMode.SORT;
    private volatile String scopeText = "";
    private volatile @Nullable InboxTally inbox;
    private volatile @Nullable SortedTally sorted;
    private volatile boolean counting = true;
    private volatile boolean countsUnreadable;
    private volatile @Nullable Message message;

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
     * Creates the presenter over the facade it reads counts from, and the things only the dashboard
     * as a whole can answer.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param jobRunning {@link BooleanSupplier} whether a run is working right now
     * @param repaint {@link Runnable} draws the launcher again, on whichever screen is up
     */
    RunSetupPresenter(final Pipeline pipeline, final BooleanSupplier jobRunning,
                      final Runnable repaint) {
        this.pipeline = pipeline;
        this.jobRunning = jobRunning;
        this.repaint = repaint;
    }

    /**
     * What the launcher draws right now.
     *
     * @return {@link RunLauncherView} every value the screen puts on the page
     */
    public RunLauncherView view() {
        final RunScope scope = this.scope();
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
        this.scopeText = covered.isEmpty()
                ? String.valueOf(year)
                : year + " " + RunWords.joined(covered);
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
                || !(this.scope() instanceof RunScope.Everything)) {
            return null;
        }
        final List<YearRow> staged = this.stagedYears();
        final int files = staged.stream().mapToInt(YearRow::total).sum();
        return new Confirmation("Move everything to your library?",
                "This moves " + RunWords.counted(files, "file", "files") + " from "
                        + RunWords.listed(staged.stream().map(row -> String.valueOf(row.year())).toList())
                        + " into your library folder.",
                "Move to library", "Cancel");
    }

    /**
     * The question to put before bringing photos in.
     *
     * <p>Several sources go unnamed, because the only way to choose several is to drop them, and
     * somebody who has just dropped them knows what they were.
     *
     * @param sources a {@link List} of {@link Path}
     * @return {@link ImportQuestion}
     */
    public ImportQuestion importQuestion(final List<Path> sources) {
        final String heading = sources.size() == 1
                ? "Import " + RunWords.named(sources.getFirst()) + "?"
                : "Import these?";
        return new ImportQuestion(heading, IMPORT_QUESTION, IMPORT_COPY, IMPORT_MOVE, IMPORT_CANCEL);
    }

    /**
     * The mode a press would start.
     *
     * @return {@link RunMode} the mode now chosen
     */
    RunMode chosenMode() {
        return this.chosen;
    }

    /**
     * What the field and the chosen mode come to together.
     *
     * @return {@link RunScope} the parsed scope, or a refusal saying what is wrong with it
     */
    RunScope scope() {
        // A run over the Inbox takes the oldest year in it, whatever the field holds. Nothing on
        // this screen could tell a reader which years the Inbox has. Finding that out means reading
        // a date off every file in it, which is the work a sort does. So the field is left out of
        // it rather than asking for a year nobody can check.
        if (this.readsInbox()) {
            return this.blankScope();
        }
        return switch (RunScopeText.parse(this.scopeText)) {
            case RunScopeText.Typed.Refused(final String why) -> new RunScope.Refused(why);
            case RunScopeText.Typed.Blank _ -> this.blankScope();
            case RunScopeText.Typed.OfYear(final int year, final List<Integer> months) ->
                    this.yearScope(year, months);
        };
    }

    /**
     * Whether the start button is live.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return boolean true when there is something to start
     */
    boolean canStart(final RunScope scope) {
        return !this.jobRunning.getAsBoolean() && !this.counting && this.chosen != RunMode.RESCUE
                && RunScope.startable(scope) && !this.pipeline.isBusy();
    }

    /**
     * Reports why a press that reached the facade started nothing.
     *
     * <p>Every other reason is already on the screen: a refusal under the field, a dead button, a
     * mode with nothing behind it. Another job starting is the one a reader cannot see, and a
     * confirm is long enough to answer for one to start underneath it.
     */
    void reasonNothingStarted() {
        if (this.pipeline.isBusy()) {
            this.message = new Message(BUSY_ELSEWHERE, true);
        } else if (this.counting) {
            // The button is live for the first moments of a read, so this press is one the screen
            // invited. Saying nothing would make the press look like it missed.
            this.message = new Message(STILL_READING, true);
        }
    }

    /**
     * Puts a line on the launcher, or takes the one there away.
     *
     * @param said {@link Message} what to report, or null to leave the screen saying nothing
     */
    void report(final @Nullable Message said) {
        this.message = said;
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
        return new ModeChoice(mode, id, mode.label(), mode == this.chosen,
                !this.jobRunning.getAsBoolean());
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
        // Live even on the unreadable card, since a card that could not be counted is still where
        // somebody may want to put photos.
        final boolean canImport = !this.jobRunning.getAsBoolean();
        final InboxTally waiting = this.inbox;
        if (this.counting && waiting == null && !this.countsUnreadable) {
            return new InboxCard(INBOX_COUNTING, null, IMPORT_LABEL, IMPORT_HINT, canImport);
        }
        if (this.countsUnreadable || waiting == null) {
            return new InboxCard(INBOX_UNREADABLE, null, IMPORT_LABEL, IMPORT_HINT, canImport);
        }
        if (waiting.files() == 0) {
            return new InboxCard(INBOX_EMPTY, INBOX_EMPTY_DETAIL, IMPORT_LABEL, IMPORT_HINT, canImport);
        }
        return new InboxCard(RunWords.counted(waiting.files(), "photo or video", "photos and videos"),
                RunWords.sized(waiting.bytes()), IMPORT_LABEL, IMPORT_HINT, canImport);
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
        final RunScopeText.Typed typed = RunScopeText.parse(this.scopeText);
        final int selected = this.readsSorted() ? this.typedYear() : 0;
        final List<Integer> narrowed =
                typed instanceof RunScopeText.Typed.OfYear(int _, final List<Integer> months)
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
                        RunWords.monthName(month.month()),
                        RunWords.held(month.photos(), month.videos()),
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
        return RunWords.held(row.photos(), row.videos());
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
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link Cost} what to say about money, or null where this mode never spends
     */
    private @Nullable Cost cost(final RunScope scope) {
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
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link Cost.Estimate} the figure and what it is worth, or null where none can be given
     */
    private Cost.@Nullable Estimate figureFor(final RunScope scope) {
        if (!RunScope.startable(scope) || this.chosen != RunMode.SIFT) {
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
        return new Cost.Estimate("About " + RunWords.rounded(expected.totalTokens()) + " tokens",
                DISCLAIMER, expected.historicOutput() ? null : WITHOUT_HISTORY);
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
     * @param scope {@link RunScope} what the field and mode come to
     * @return int the photos in range, zero where the scope names a year holding none
     */
    private int photosIn(final RunScope scope) {
        if (!(scope instanceof RunScope.OfYear(final int year, final List<Integer> months))) {
            return 0;
        }
        return this.stagedYears().stream()
                .filter(row -> row.year() == year)
                .mapToInt(row -> months.isEmpty() ? row.photos() : row.photosIn(months))
                .sum();
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
                        this.repaint.run();
                    }
                },
                CompletableFuture.delayedExecutor(SETTLE_BEFORE_SAYING_SO, TimeUnit.MILLISECONDS));
    }

    /**
     * What an empty scope field means for the chosen mode.
     *
     * @return {@link RunScope} the scope it stands for, or a refusal where it stands for none
     */
    private RunScope blankScope() {
        return switch (this.chosen) {
            // The oldest year of an empty Inbox is no year at all, so the run would be over nothing.
            case SORT, CURATE -> this.inboxIsEmpty() ? new RunScope.Nothing() : new RunScope.OldestYear();
            // Everything means everything staged, and on an install with nothing staged that is a
            // run over no files behind a confirm naming none of them. The Sorted card says so, in
            // the same words a line here would use and in a tone that does not read as a fault.
            // Everything is the one press that reaches the whole library at once, and the confirm
            // in front of it names the years and the file count. A read that failed leaves neither
            // knowable, so the press is withheld rather than offered without its question. A typed
            // year still goes through: it is bounded, and the facade names the folder at fault.
            case MOVE_TO_LIBRARY -> this.countsAreIn() && !this.stagedYears().isEmpty()
                    ? new RunScope.Everything()
                    : new RunScope.Nothing();
            // A sift has no oldest-year to fall back on, deliberately. It reads Sorted, where
            // every year is equally ready and none of them is the one next in line. So a blank
            // field is where a sift starts rather than something gone wrong, and the hint under it
            // already says to pick a year.
            //
            // Import is here because the field is never about it: it has no button in the row, and
            // what it covers is the folders that were picked.
            case SIFT, RESCUE, IMPORT -> new RunScope.Nothing();
        };
    }

    /**
     * What a year, and possibly some months, mean for the chosen mode.
     *
     * @param year int the year typed
     * @param months a {@link List} of {@link Integer} the months typed, empty for the whole year
     * @return {@link RunScope} the scope it stands for, or a refusal where the mode cannot take it
     */
    private RunScope yearScope(final int year, final List<Integer> months) {
        if (this.chosen == RunMode.RESCUE) {
            return new RunScope.Nothing();
        }
        if (this.readsSorted() && this.countsAreIn()
                && this.stagedYears().stream().noneMatch(row -> row.year() == year)) {
            return new RunScope.Refused("Nothing is sorted for " + year + ".");
        }
        if (this.chosen == RunMode.SIFT) {
            final RunScope sift = new RunScope.OfYear(year, months);
            // A year can hold videos alone, and months can be named that hold nothing. Both leave a
            // sift with no photo to look at, and neither is caught by the year check above. Said
            // here rather than left to an absent cost line, which a free provider draws too.
            return this.countsAreIn() && this.photosIn(sift) == 0
                    ? new RunScope.Refused(months.isEmpty()
                            ? "No photos are sorted for " + year + ", so there is nothing to sift."
                            : "No photos are sorted for the chosen months of " + year
                                    + ", so there is nothing to sift.")
                    : sift;
        }
        // Moving to the library narrows by a run of months rather than a set of them. The type it
        // builds cannot hold a gap. A gapped list is widened to the run that spans it where that
        // takes nothing extra, and refused where it would.
        if (!months.isEmpty() && !RunScopeText.contiguous(months)) {
            final List<Integer> blocking = this.gapHolds(year, months);
            if (blocking != null && blocking.isEmpty()) {
                return new RunScope.OfYear(year, months);
            }
            // The way out is worded for a click as much as for a keystroke. Three presses on the
            // rows reach this state without the field being touched, and an answer that only says
            // what to type names nothing the user did.
            return new RunScope.Refused(this.chosen.verb()
                    + " narrows to a run of months, not a list. Reading "
                    + RunWords.joined(months) + " as " + months.getFirst() + "-" + months.getLast()
                    + " would take " + wouldAlsoTake(blocking) + ". Choose months that run "
                    + "together, like 6-8, or none at all for the whole year.");
        }
        return new RunScope.OfYear(year, months);
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
        return RunScopeText.parse(this.scopeText)
                instanceof RunScopeText.Typed.OfYear(final int named, final List<Integer> months)
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
        return RunScopeText.parse(this.scopeText)
                instanceof RunScopeText.Typed.OfYear(final int year, List<Integer> _) ? year : 0;
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
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link String} the refusal, or null while nothing is wrong
     */
    private @Nullable String refusalOf(final RunScope scope) {
        if (this.chosen == RunMode.RESCUE) {
            return null;
        }
        return scope instanceof RunScope.Refused(final String why) ? why : null;
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
        return RunWords.namedMonths(blocking) + " too";
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
     * The question asked before photos are brought in, which has two ways of going ahead.
     *
     * @param heading {@link String}
     * @param question {@link String}
     * @param copy {@link String}
     * @param move {@link String}
     * @param cancel {@link String}
     */
    public record ImportQuestion(String heading, String question, String copy, String move,
                                 String cancel) {
    }
}
