package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.adapter.ui.RunLauncherView.Cost;
import photos.sluice.adapter.ui.RunLauncherView.InboxCard;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.StartAction;
import photos.sluice.adapter.ui.RunLauncherView.UndatedChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.paths.SortFolderNames;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
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
 * <p>The counts are read by calling {@link #refreshCounts} or {@link #refreshCountsUnprompted},
 * each of which walks three trees and blocks while it does. The caller runs it off whatever thread
 * paints. The runs folder is the expensive one: reading it diagnoses every sift on disk.
 *
 * <p>Whether a job is running is not held here. {@link RunLauncherPresenter} owns that, and answers
 * the question through the supplier handed in.
 */
public class RunSetupPresenter {

    private static final Logger log = LoggerFactory.getLogger(RunSetupPresenter.class);

    private static final String SCOPE_LABEL = "Timeframe for this run";

    // How long a folder read is given before the screen says it is reading. Under this, a reader
    // sees one state rather than three. Over it, they are waiting and want to know why.
    private static final long SETTLE_BEFORE_SAYING_SO = 200;

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

    // What the mark means, once under the rows. A reader who never hovers a row would otherwise
    // meet a bare asterisk. The screen opens this line with the mark itself, in its own colour.
    // Three pieces because the middle one is pressable. Split here rather than on the screen, which
    // would have to read the sentence to find the word that leads anywhere.
    private static final String UNFINISHED_LEGEND = "This timeframe already has a sift that has not "
            + "finished. Open ";
    private static final String UNFINISHED_WAY_THERE = "Runs";
    private static final String UNFINISHED_LEGEND_AFTER = " to see it.";

    // A run scoped to a whole year covers every month a row could stand for.
    private static final Set<Integer> WHOLE_YEAR =
            IntStream.rangeClosed(1, 12).boxed().collect(Collectors.toUnmodifiableSet());

    private static final String NOTHING_STAGED =
            "Nothing is sorted yet. Sort your Inbox first, and the years will show up here.";

    private static final RunLauncherView.RowLink REVIEW_STEP =
            new RunLauncherView.RowLink("run-mode-review", "Review", RunMode.SIFT);

    private static final String UNDATED_LABEL = SortFolderNames.UNDATED;

    private static final String NOTHING_UNDATED = "Nothing is waiting without a date.";

    private static final String SIFT_TAKES_NO_UNDATED =
            "A sift looks at photos filed under a year, and these have no date.";

    // One paragraph, so a reader meets a single statement about the figure rather than two they
    // have to reconcile. Only the middle varies, and it is absent where the warning box says it.
    private static final String ESTIMATE_OPENING = "An estimate, not a quote.";

    // The load-bearing part: what a reader needs is not that the number is right, but that
    // something stops a run that outgrows it. So it closes all three.
    private static final String ESTIMATE_CEILING = " Sluice will stop and ask whether to continue "
            + "if the sift goes far past the estimate.";

    private static final String FROM_HISTORY = "It is an average of what sifts like this one have "
            + "cost, and a sift costs more when the model needs a second attempt at a sheet.";

    private static final String WITHOUT_HISTORY = "Nothing has finished a sift on this computer "
            + "yet, so this figure is Sluice's own starting guess rather than an average of your "
            + "own sifts. It starts showing your actual numbers after a sift or two.";

    // The same figure as the line above, and a different thing to tell somebody. That one resolves
    // itself on the next finished sift. This one does not, because a broken record stays broken,
    // and every estimate falls back to the guess until somebody deals with it.
    private static final String HISTORY_UNREADABLE = "The record of what your past sifts cost is "
            + "broken, so this figure is a starting guess rather than an average of your own "
            + "sifts. It will keep guessing until that record is replaced.";

    private static final String FRESH_RECORD = "Start a fresh record";

    private static final String FRESH_RECORD_STARTED = "The old record is discarded. Your next "
            + "finished sift starts the new one.";

    private static final String RECORD_READS_AFTER_ALL = "The record is now healthy, so it has "
            + "been left where it is.";

    private final Pipeline pipeline;
    private final BooleanSupplier jobRunning;
    private final Runnable repaint;

    // Volatile throughout, and the traffic runs both ways. The counts and the flags beside them are
    // written by the read behind the cards and looked at while somebody types. The mode, the field
    // and the line go the other way: written by a press, and looked at by that same read when it
    // draws the screen. Without it a reader can see the read already over while the count is still
    // null, and the Inbox card then reports a failure that never happened.
    private volatile RunMode chosen = RunMode.SORT;
    private volatile String scopeText = "";
    private volatile @Nullable InboxTally inbox;
    private volatile @Nullable SortedTally sorted;
    private volatile boolean countsUnreadable;
    private volatile List<CullRunSummary> unfinished = List.of();
    private volatile @Nullable Message message;

    // How many asked-for reads are somewhere between being asked for and finishing their walk. A
    // count rather than a flag, because two of them can overlap. A boolean the first cleared on its
    // way out would bring the button live over counts the second is still replacing. The pane's
    // mount read and a recount after a run are the pair that does it. Finish a run, then arrive
    // back at the Dashboard while that recount is still walking.
    private final AtomicInteger askedReads = new AtomicInteger();

    // Whether any read at all has landed. A screen that has read nothing has no counts to offer,
    // and no asked-for read need be in flight for that to be true.
    private volatile boolean wasReadAtLeastOnce;

    // Plain, because no other thread touches this one. It is written by a press and by a keystroke,
    // and read while drawing, all on the thread that paints. Volatile would not make its own toggle
    // atomic anyway, and would suggest a second writer that does not exist.
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
        final List<YearChoice> years = this.yearChoices();
        final StartAction action = this.startAction(scope);
        return new RunLauncherView(this.modes(), REVIEW_STEP, this.chosen.explained(), this.inboxCard(),
                years, this.undatedChoice(), this.readsSorted(),
                this.nothingStagedLine(), SCOPE_LABEL, this.scopeText, this.chosen.scopeHint(),
                this.refusalOf(scope), this.cost(scope), legendFor(years),
                legendFor(years) == null ? null : UNFINISHED_WAY_THERE,
                legendFor(years) == null ? null : UNFINISHED_LEGEND_AFTER,
                this.startLabel(action), this.canStart(scope), action, this.message);
    }

    /**
     * Everything the last read found, as one value.
     *
     * @param inbox {@link InboxTally} what is waiting, or null where the read failed
     * @param sorted {@link SortedTally} what is staged, or null where the read failed
     * @param unfinished a {@link List} of {@link CullRunSummary} the sifts left part way through
     * @param unreadable boolean whether the last read failed
     */
    public record Counts(@Nullable InboxTally inbox, @Nullable SortedTally sorted,
                         List<CullRunSummary> unfinished, boolean unreadable) {

        /**
         * Defensively copies the mutable list.
         *
         * @param inbox {@link InboxTally} what is waiting, or null
         * @param sorted {@link SortedTally} what is staged, or null
         * @param unfinished a {@link List} of {@link CullRunSummary} the sifts left part way through
         * @param unreadable boolean whether the last read failed
         */
        public Counts {
            unfinished = List.copyOf(unfinished);
        }
    }

    /**
     * What the last read found, so a caller can tell a read that moved something from one that
     * found everything where it was.
     *
     * @return {@link Counts} the four values a read writes
     */
    public Counts counts() {
        return new Counts(this.inbox, this.sorted, this.unfinished, this.countsUnreadable);
    }

    /**
     * Reads what is in the Inbox, what is staged in Sorted and what sifts are on disk.
     *
     * <p>Blocks for as long as walking those three trees takes, which on a full Inbox is long
     * enough to be felt. Called off the thread that paints.
     *
     * <p>A refusal from the facade is kept rather than thrown on. The roots were usable when this
     * screen was drawn, so one that is not now went wrong while somebody was looking at it. The
     * cards say so. The start button stays live, because the refusal it raises names which folder
     * is at fault, and that is more than this screen knows.
     */
    public void refreshCounts() {
        this.readCounts(true);
    }

    /**
     * Reads the same two trees for a caller nobody asked for an answer from.
     */
    public void refreshCountsUnprompted() {
        this.readCounts(false);
    }

    /**
     * Walks the two folder trees and the runs folder, and records what they hold.
     *
     * <p>Serialised rather than allowed to overlap. Left concurrent, the cards would pair an Inbox
     * from one moment with years from another. A waiting caller re-walks rather than skipping,
     * since the read it would have skipped may predate the run that asked for this one.
     *
     * <p>An asked-for read counts itself in before the lock and out once its own walk has ended, so
     * it covers its wait as well as its walk. A read queued behind a walk already running would
     * otherwise leave the start button live for the rest of that walk. A press landing there scopes
     * a job by the very counts this read was asked to correct.
     *
     * <p>A read nobody asked for counts nothing, so it cannot take the button away every few seconds
     * from a reader who is only sitting there. What it does do is record that a read has landed,
     * which is what ends the state a screen starts in, having read nothing.
     *
     * @param prompted boolean whether something happened that the screen has yet to catch up with
     */
    private void readCounts(final boolean prompted) {
        if (prompted) {
            // Drawn before the read rather than after it, but not straight away. A wait behind
            // another walk, or a walk over a full Inbox, is long enough to be felt. A screen still
            // showing the live button it was drawn with takes a press and does nothing. A read that
            // waits for nothing and walks an empty Inbox is over in milliseconds. A button going
            // dead and live again inside that reads as a glitch. So the dead state waits to see
            // which kind of read this turns out to be.
            this.redrawIfStillReadingByThen();
            // Last before the lock, so nothing that allocates sits between the count going up and
            // the try that brings it down again. A count left up has no way back down, and the
            // start button is then dead for the life of the process.
            this.askedReads.incrementAndGet();
        }
        this.reading.lock();
        // A throw between the lock and the try would reach the caller with the lock still held,
        // and with the count above it still up. Every later read would park on that lock for the
        // rest of the run.
        try {
            // A run that just finished spent against the ledger the estimate is averaged from.
            this.lastEstimateCovered = -1;
            // Read first and guarded on its own. What the runs folder answers decides which rows
            // carry a mark and nothing else on this screen, so its failures stay off the Inbox and
            // Sorted cards.
            this.unfinished = this.unfinishedRunsOrNone();
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
            // Set before the count comes down, so no reader sees an asked-for read gone while the
            // screen still reads as having read nothing.
            this.wasReadAtLeastOnce = true;
            if (prompted) {
                this.askedReads.decrementAndGet();
            }
            this.reading.unlock();
        }
    }

    /**
     * Whether the counts on the cards are being replaced.
     *
     * @return boolean true while an asked-for read is under way, and until the first read of any
     *     kind has landed
     */
    private boolean isCounting() {
        return this.askedReads.get() > 0 || !this.wasReadAtLeastOnce;
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
     * Empties the field where nothing is left in what it names.
     *
     * <p>What a run empties, the field still says. A reader coming back from a finished move over
     * 2019 meets a refusal of the very thing that just worked. The better the run went, the more
     * certain that refusal.
     *
     * <p>Keyed on the scope being refused rather than on the run having finished. A run the reader
     * stopped leaves something behind, so its scope is not refused and it stays, which is what they
     * need to finish it. A year still worth running in another mode stays for the same reason.
     */
    public void forgetAScopeNothingIsLeftIn() {
        if (!this.scopeText.isBlank() && this.scope() instanceof RunScope.Refused) {
            this.setScope("");
        }
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
     * Takes a press on the undated row, which clears the field where it is already the scope.
     */
    public void pressUndated() {
        this.setScope(this.scope() instanceof RunScope.Undated ? "" : RunScopeText.UNDATED);
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
     * <p>The bare move to the library is the one that needs one. Every other mode either names its
     * own scope or takes the oldest year, and both are small enough to be undone by hand. Moving
     * everything staged is the one press that reaches the whole library in one go, so it says out
     * loud what it is about to move.
     *
     * <p>Sifting is not asked about here, because the launcher has already put what it will cost in
     * the box above the button. Nothing is weighed twice. {@link #siftNowNeeds} is where a
     * sift does get a question, on the one screen carrying no such box.
     *
     * @return {@link Confirmation} what to ask, or null where nothing needs asking
     */
    public @Nullable Confirmation confirmationNeeded() {
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
                "Move to library", "Cancel", true);
    }

    /**
     * What a press to sift a timeframe from a finished sort's result card needs before it can start.
     *
     * <p>One answer rather than a question and a separate guard, so the whole decision is taken
     * from one read of the counts.
     *
     * <p>A question is always put, whatever the provider costs. The launcher says what a run
     * covers and what it costs above its own button, and this card has room for neither. So the
     * dialog is the only place a reader learns either, and skipping it on a free provider would
     * skip the scope along with the money.
     *
     * <p>The question names the whole timeframe and splits out what this run put there. A sort that
     * added two months to a year already holding others yields a sift over every month of it, and
     * the photos it did not add are the ones a reader would not think they were paying for.
     *
     * @param year int the timeframe the card offered to sift
     * @param justSorted int how many photos this run filed into that timeframe
     * @return {@link SiftNow} the question to put first, or the refusal to report instead
     */
    public SiftNow siftNowNeeds(final int year, final int justSorted) {
        final Message blocked = this.cannotSizeARun();
        if (blocked != null) {
            return new SiftNow.Refuse(blocked);
        }
        final int photos = this.photosIn(new RunScope.OfYear(year, List.of()));
        // A sort files videos under a year as readily as photos, so a timeframe can reach this card
        // holding nothing a provider could look at. The launcher refuses the same timeframe in the
        // same words.
        if (photos == 0) {
            return new SiftNow.Refuse(new Message(nothingToSift(year), true));
        }
        return new SiftNow.Ask(new Confirmation("Sift " + year + "?",
                whatItCovers(year, photos, justSorted) + " " + this.spendClause(photos),
                "Sift " + year, "Cancel", true));
    }

    /**
     * What a sift of this timeframe would look at, split into what the run just filed and what was
     * already there.
     *
     * <p>The split is the point. A reader pressing this after a sort that filed six photos has no
     * reason to expect the other two hundred. A single total hides those behind a number that
     * reads as the run's own.
     *
     * <p>Falls back to the total alone where the two cannot be told apart. A stale count can put
     * the timeframe behind what the run reported. A sentence claiming a negative remainder is worse
     * than one that simply says how many there are.
     *
     * @param year int the timeframe
     * @param photos int how many photos it holds in all
     * @param justSorted int how many of those this run filed
     * @return {@link String} the sentence
     */
    private static String whatItCovers(final int year, final int photos, final int justSorted) {
        final String looksAt = "This looks at " + RunWords.counted(photos, "photo", "photos")
                + " sorted for " + year;
        final int earlier = photos - justSorted;
        if (earlier <= 0) {
            return looksAt + ", all of them from this run.";
        }
        return looksAt + ": " + RunWords.grouped(justSorted) + " from this run and "
                + RunWords.grouped(earlier) + " sorted earlier.";
    }

    /**
     * What a press to sift from a result card is answered with.
     */
    public sealed interface SiftNow {

        /**
         * Put this question first, and start only where the reader agrees.
         *
         * @param question {@link Confirmation} what to ask
         */
        record Ask(Confirmation question) implements SiftNow {
        }

        /**
         * Start nothing, and report this instead.
         *
         * @param reason {@link Message} what to say on the card
         */
        record Refuse(Message reason) implements SiftNow {
        }
    }

    /**
     * Why a press that needs the folder counts cannot go ahead, or null where it can.
     *
     * <p>Two states, and they are not the same news. A read in flight is over in a moment and the
     * press is worth making again. A read that failed will keep failing until the folders in
     * Settings are put right.
     *
     * @return {@link Message} what to say instead of starting, or null where nothing is in the way
     */
    RunLauncherView.@Nullable Message cannotSizeARun() {
        if (this.isCounting()) {
            return new Message(STILL_READING, true);
        }
        return this.countsUnreadable ? new Message(INBOX_UNREADABLE, true) : null;
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
            case RunScopeText.Typed.Refused(final String reason) -> new RunScope.Refused(reason);
            case RunScopeText.Typed.Blank _ -> this.blankScope();
            case RunScopeText.Typed.Undated _ -> this.undatedScope();
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
        return !this.jobRunning.getAsBoolean() && !this.isCounting()
                && RunScope.startable(scope) && !this.pipeline.isBusy()
                && this.overlapRefusal(scope) == null;
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
        } else if (this.isCounting()) {
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
     * Files away a record of past spend that nothing can read, so the next one starts clean.
     *
     * <p>The held estimate goes with it. The figure beside the button is then worked out again from
     * what the app can read now, rather than from the answer that produced the button.
     *
     * <p>A record that turns out to read is left alone, and the reader is told so. The button is
     * drawn from a reading that can be minutes old on a screen nobody has touched.
     */
    public void startAFreshSpendRecord() {
        this.lastEstimate = null;
        try {
            this.message = this.pipeline.setAsideUnreadableSpendLedger() == null
                    ? new Message(RECORD_READS_AFTER_ALL, false)
                    : new Message(FRESH_RECORD_STARTED, false);
        } catch (final RuntimeException e) {
            log.info("Could not file the unreadable spend record away", e);
            this.message = new Message(RunRefusals.plainly(e), true);
        }
    }

    /**
     * The buttons across the top, in the order they are drawn.
     *
     * <p>The order is the order the work runs in. So a mode added here is a claim that it belongs at
     * that point in the sequence.
     *
     * @return a {@link List} of {@link ModeChoice} one per mode with a button
     */
    private List<ModeChoice> modes() {
        return List.of(
                this.modeChoice(RunMode.SORT, "run-mode-sort"),
                this.modeChoice(RunMode.SIFT, "run-mode-sift"),
                this.modeChoice(RunMode.MOVE_TO_LIBRARY, "run-mode-move"));
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
        if (this.isCounting() && waiting == null && !this.countsUnreadable) {
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
        // The undated row counts too. It comes from a tree the year rows never see, and it is drawn
        // on this same card. A card holding one and no years would say nothing is sorted, directly
        // above something that is.
        if (this.countsUnreadable || this.sorted == null || !this.yearChoices().isEmpty()
                || this.undatedHeld() > 0) {
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
                .map(row -> this.yearChoice(row, selected, narrowed))
                .toList();
    }

    /**
     * One year's row, and the month rows under it.
     *
     * <p>The mark is drawn whatever mode is chosen, since it is about the timeframe rather than the
     * mode. A reader looking at Sorted is looking at the same timeframes whichever mode they came
     * here for.
     *
     * @param row {@link YearRow} the year's counts
     * @param selected int the year the field names, or 0 where it names none
     * @param narrowed a {@link List} of {@link Integer} the months the field names
     * @return {@link YearChoice} the row
     */
    private YearChoice yearChoice(final YearRow row, final int selected,
                                  final List<Integer> narrowed) {
        final Set<Integer> sifted = this.siftedMonthsOf(row.year());
        return new YearChoice(row.year(), "run-year-" + row.year(), String.valueOf(row.year()),
                held(row), row.year() == selected,
                row.year() == selected && !this.monthsCollapsed, !sifted.isEmpty(),
                monthChoices(row, row.year() == selected ? narrowed : List.of(), sifted));
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
     * @param sifted a {@link Set} of {@link Integer} the months an unfinished sift already covers
     * @return a {@link List} of {@link MonthChoice} one per month holding anything
     */
    private static List<MonthChoice> monthChoices(final YearRow row, final List<Integer> narrowed,
                                                  final Set<Integer> sifted) {
        return row.months().stream()
                .filter(month -> month.photos() > 0 || month.videos() > 0)
                .sorted(Comparator.comparingInt(MonthRow::month))
                .map(month -> new MonthChoice(month.month(),
                        "run-month-" + row.year() + "-" + month.month(),
                        RunWords.monthName(month.month()),
                        RunWords.held(month.photos(), month.videos()),
                        narrowed.contains(month.month()),
                        sifted.contains(month.month())))
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
     * @return boolean true for the one mode that sifts
     */
    private boolean reachesAProvider() {
        return this.chosen == RunMode.SIFT;
    }

    /**
     * The expected cost of sifting the chosen scope, where one can be worked out.
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
        final String rests = whereTheFigureCameFrom(expected);
        return new Cost.Estimate("About " + RunWords.rounded(expected.totalTokens()) + " tokens",
                ESTIMATE_OPENING + (rests == null ? "" : " " + rests) + ESTIMATE_CEILING,
                expected.historyUnreadable() ? this.brokenRecord() : null);
    }

    /**
     * The broken record of past spend, and the way out of it.
     *
     * <p>Offered only where the record is the thing holding the figure back. An install that has
     * simply not finished a sift yet has nothing to put right. A control there would invite a
     * reader to throw away a record that is fine.
     *
     * @return {@link Cost.Warning} what is wrong and the control that ends it
     */
    private Cost.Warning brokenRecord() {
        return new Cost.Warning(HISTORY_UNREADABLE, new Cost.Repair("run-estimate-repair",
                FRESH_RECORD,
                new Confirmation("Start a fresh record of what your sifts cost?",
                        "The broken record is kept in " + this.pipeline.archivesFolder()
                                + ", and a new one starts from your next finished sift. What the "
                                + "old one holds does not come back, so the estimate stays a "
                                + "starting guess until a sift or two have finished.",
                        "Start fresh", "Cancel", false)));
    }

    /**
     * What the figure rests on.
     *
     * @param expected {@link SpendEstimate} what the facade said about this scope
     * @return the middle of the disclaimer, or null where the warning box carries it instead
     */
    private static @Nullable String whereTheFigureCameFrom(final SpendEstimate expected) {
        if (expected.historicOutput()) {
            return FROM_HISTORY;
        }
        return expected.historyUnreadable() ? null : WITHOUT_HISTORY;
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
     * Puts the counting state on the screen, but only if an asked-for read is still going by then.
     *
     * <p>Nothing cancels this. It asks the same flag the screen does when it wakes, so a read that
     * has already finished leaves it nothing to draw. A second asked-for read in the meantime
     * raises the flag again. A read nobody asked for does not, and the screen wants its live button
     * through one of those anyway.
     */
    private void redrawIfStillReadingByThen() {
        CompletableFuture.runAsync(
                () -> {
                    if (this.isCounting()) {
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
            case SORT -> this.inboxIsEmpty() ? new RunScope.Nothing() : new RunScope.OldestYear();
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
            // Import and rescue are here because the field is never about either: neither has a
            // button in the row, and each already knows what it covers.
            case SIFT, RESCUE, IMPORT -> new RunScope.Nothing();
        };
    }

    /**
     * What the undated word means for the chosen mode.
     *
     * @return {@link RunScope} the scope it stands for, or a refusal where the mode cannot take it
     */
    private RunScope undatedScope() {
        return switch (this.chosen) {
            case MOVE_TO_LIBRARY -> this.countsAreIn() && this.undatedHeld() == 0
                    ? new RunScope.Refused(NOTHING_UNDATED)
                    : new RunScope.Undated();
            case SIFT -> new RunScope.Refused(SIFT_TAKES_NO_UNDATED);
            // A sort never reads the field. Neither rescue nor import has a button in the row. So
            // none of the three can be the chosen mode while text sits in it.
            case SORT, RESCUE, IMPORT -> new RunScope.Nothing();
        };
    }

    /**
     * How much is waiting in the undated folder, or none while the walk that would say is running.
     *
     * @return int what the last completed read found there
     */
    private int undatedHeld() {
        final SortedTally staged = this.sorted;
        return staged == null ? 0 : staged.undated();
    }

    /**
     * The undated row, where anything is waiting without a date.
     *
     * @return {@link UndatedChoice} the row, or null where there is nothing to show
     */
    private @Nullable UndatedChoice undatedChoice() {
        final int held = this.undatedHeld();
        if (held == 0) {
            return null;
        }
        return new UndatedChoice("run-undated-row", UNDATED_LABEL,
                RunWords.counted(held, "photo or video", "photos and videos"),
                this.scope() instanceof RunScope.Undated,
                this.chosen == RunMode.MOVE_TO_LIBRARY);
    }

    /**
     * What a year, and possibly some months, mean for the chosen mode.
     *
     * @param year int the year typed
     * @param months a {@link List} of {@link Integer} the months typed, empty for the whole year
     * @return {@link RunScope} the scope it stands for, or a refusal where the mode cannot take it
     */
    private RunScope yearScope(final int year, final List<Integer> months) {
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
                            ? nothingToSift(year)
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
     * @return boolean true for the one mode that reads the Inbox
     */
    private boolean readsInbox() {
        return this.chosen == RunMode.SORT;
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
        return !this.isCounting() && !this.countsUnreadable;
    }

    /**
     * What a timeframe holding no photo is refused with.
     *
     * <p>One sentence, because two surfaces refuse the same timeframe. The launcher greys Start on
     * it, and a result card's Sift reports it. A reader who meets both must not be told two
     * different things about one folder.
     *
     * @param year int the timeframe nothing can be sifted out of
     * @return {@link String} the refusal
     */
    private static String nothingToSift(final int year) {
        return "No photos are sorted for " + year + ", so there is nothing to sift.";
    }

    /**
     * What the sift question says about money.
     *
     * <p>Split out because the figure can be missing while the spending is certain. A provider
     * forecasting nothing leaves the estimate empty, and the sentence still has to say that this
     * press spends.
     *
     * <p>The ceiling rides with the figure and not without it. It is the reassurance the launcher's
     * own disclaimer carries, and this route is the only other way to start a sift, so a reader who
     * never sees that box hears it here instead. With no figure there is nothing for a reader to
     * measure "far past" against.
     *
     * @param photos int how many photos the sift would cover
     * @return {@link String} the clause about spending, with a figure where one can be given
     */
    private String spendClause(final int photos) {
        if (!this.pipeline.configuredProviderSpends()) {
            return "Sifting costs you nothing through Sluice. An agent you run yourself still "
                    + "costs whatever you pay for it.";
        }
        final SpendEstimate expected = this.expectedFor(photos);
        if (expected.totalTokens() == 0) {
            return "Sifting spends from your provider account balance.";
        }
        return "That is about " + RunWords.rounded(expected.totalTokens())
                + " tokens, and sifting spends from your provider account balance. Sluice will "
                + "stop and ask if it goes far past that.";
    }

    /**
     * What is wrong with the scope, where anything is.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link String} the refusal, or null while nothing is wrong
     */
    private @Nullable String refusalOf(final RunScope scope) {
        if (scope instanceof RunScope.Refused(final String reason)) {
            return reason;
        }
        return this.overlapRefusal(scope);
    }

    /**
     * What to say where the chosen timeframe runs across an unfinished sift without being it.
     *
     * <p>The screen's half of a guard the facade also makes. This one greys Start while somebody
     * types, off the last reading of the folder, so it can be a moment out of date and the worst it
     * can do is fail to warn. {@code CullEngine.refuseIfScopeOverlaps} reads freshly and is the
     * guarantee. Both word it through {@link RunRefusals#coveringUnfinished}, so the sentence on the
     * screen and the sentence in the refusal cannot drift apart.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link String} the sentence to show, or null where nothing overlaps
     */
    private @Nullable String overlapRefusal(final RunScope scope) {
        final CullScope.Year chosenYear = this.siftedYear(scope);
        if (chosenYear == null) {
            return null;
        }
        final String exact = CullScope.tag(chosenYear);
        final List<CullScope.Year> across = this.unfinished.stream()
                .filter(run -> !run.scope().equals(exact))
                .map(run -> CullScope.yearScopeOf(run.scope()))
                .filter(Objects::nonNull)
                .filter(chosenYear::overlaps)
                .toList();
        return across.isEmpty() ? null : RunRefusals.coveringUnfinished(chosenYear, across);
    }

    /**
     * The year a sift of this scope would cover, or null where this is not a sift of one.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link CullScope.Year} the year and months, or null
     */
    private CullScope.@Nullable Year siftedYear(final RunScope scope) {
        if (this.chosen != RunMode.SIFT || !(scope instanceof RunScope.OfYear(final int year,
                final List<Integer> months))) {
            return null;
        }
        return new CullScope.Year(year, months.isEmpty() ? null : months);
    }


    /**
     * What pressing the button under the field does.
     *
     * <p>A sift of a timeframe that already holds an unfinished one is refused by the facade. Where
     * that run can be continued, the button continues it. Where it cannot, the button goes to the
     * screen that can deal with it.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link StartAction} what the press should do
     */
    private StartAction startAction(final RunScope scope) {
        final CullScope.Year chosenYear = this.siftedYear(scope);
        if (chosenYear == null) {
            return new StartAction.StartFresh();
        }
        final String exact = CullScope.tag(chosenYear);
        return this.unfinished.stream()
                .filter(run -> run.scope().equals(exact))
                .findFirst()
                .<StartAction>map(run -> carriedOn(run.health().state())
                        ? new StartAction.ContinueRun(run.prepDir())
                        : new StartAction.OpenRuns())
                .orElseGet(StartAction.StartFresh::new);
    }

    /**
     * Whether a run in this state can be picked up from the launcher.
     *
     * @param state {@link State} the run's state
     * @return boolean true where continuing it would get somewhere
     */
    private static boolean carriedOn(final State state) {
        return state == State.WAITING || state == State.READY;
    }

    /**
     * What the button under the field says.
     *
     * @param action {@link StartAction} what pressing it does
     * @return {@link String} the label
     */
    private String startLabel(final StartAction action) {
        return switch (action) {
            case StartAction.StartFresh _ -> this.chosen.started();
            case StartAction.ContinueRun _ -> "Continue sifting";
            case StartAction.OpenRuns _ -> "Open in Runs";
        };
    }

    /**
     * What the mark on a timeframe row means, or nothing where no row carries one.
     *
     * @param years a {@link List} of {@link YearChoice} the rows as they will be drawn
     * @return {@link String} the legend, or null
     */
    private static @Nullable String legendFor(final List<YearChoice> years) {
        return years.stream().anyMatch(YearChoice::unfinishedSift) ? UNFINISHED_LEGEND : null;
    }

    /**
     * Every run on disk that still owes somebody something.
     *
     * <p>A folder nobody could read marks nothing, and neither does one this refused to read at
     * all. A mark is only ever drawn from a run the read established.
     *
     * @return a {@link List} of {@link CullRunSummary} the unfinished ones
     */
    private List<CullRunSummary> unfinishedRunsOrNone() {
        try {
            return this.pipeline.cullRuns() instanceof CullRuns.Listed(final List<CullRunSummary> listed)
                    ? listed.stream().filter(run -> run.health().state() != State.COMPLETE).toList()
                    : List.of();
        } catch (final RuntimeException e) {
            log.warn("Could not read the runs, so no timeframe is marked this pass", e);
            return List.of();
        }
    }

    /**
     * Which months of one year an unfinished sift already covers.
     *
     * <p>An empty answer means no sift touches that year. A run scoped to the whole year answers
     * every month there is. So a month row is marked whether the sift named that month or named
     * the year it sits in.
     *
     * @param year int the year the rows belong to
     * @return a {@link Set} of {@link Integer} the months covered, empty where none are
     */
    private Set<Integer> siftedMonthsOf(final int year) {
        final List<CullScope.Year> covering = this.unfinished.stream()
                .map(run -> CullScope.yearScopeOf(run.scope()))
                .filter(Objects::nonNull)
                .filter(scope -> scope.year() == year)
                .toList();
        if (covering.stream().anyMatch(scope -> scope.months() == null)) {
            return WHOLE_YEAR;
        }
        return covering.stream()
                .flatMap(scope -> Objects.requireNonNull(scope.months()).stream())
                .collect(Collectors.toSet());
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
     * <p>Which of the two is drawn as the way on is settled here rather than by whichever screen
     * puts the question up. The answer turns on what each choice costs the reader. That is what
     * the question itself is about, and a screen asking two of them would otherwise weigh both the
     * same way.
     *
     * @param heading {@link String} what the question is about
     * @param question {@link String} the question itself, naming what is about to happen
     * @param goAhead {@link String} what the button that goes ahead says
     * @param cancel {@link String} what the button that backs out says
     * @param goAheadLeads boolean whether going ahead is the loud choice, the one Enter lands on
     */
    public record Confirmation(String heading, String question, String goAhead, String cancel,
                               boolean goAheadLeads) {
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
