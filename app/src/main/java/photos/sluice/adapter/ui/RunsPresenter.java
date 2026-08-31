package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.Kind;
import photos.sluice.adapter.ui.RunsView.RunCard;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.LaunchPrompt;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Decides what the runs screen shows and what a press on it does.
 *
 * <p>Holds the last reading of what is on disk, and the words that reading comes to. The screen
 * keeps the controls, asks here after every change, and hands every press straight back through
 * {@link #press}.
 *
 * <p>The reading is taken by calling {@link #refresh}, which diagnoses every run and blocks while
 * it does. The caller runs it off whatever thread paints.
 *
 * <p>The same reading answers {@link #unfinishedRuns}, which is the number the sidebar's entry
 * carries. One source behind both rather than one instant. A reading landing between a screen being
 * drawn and its badge being counted still leaves the two a moment apart. What it rules out is the
 * badge and the cards coming from different readings of the folder.
 */
@Component
@Profile("!cli")
public class RunsPresenter {

    private static final Logger log = LoggerFactory.getLogger(RunsPresenter.class);

    private static final String HEADING = "Runs";

    private static final String NOTHING_YET = "No sifts have been started yet. A sift you start "
            + "from the Dashboard shows up here until you finish it or discard it.";

    private static final String UNREADABLE = "Sluice doesn't know what sifts are in %s because it "
            + "cannot be read. Most likely the folder is held by another process or not there "
            + "anymore.";

    private static final String CLEAR_COMPLETED = "Clear finished runs";

    // How many scopes the clear question spells out before it falls back to the count above it. A
    // scope can be as long as "2019 6,8,11", and a list of eight of those buries the two sentences
    // the reader actually has to weigh.
    private static final int NAMED_IN_A_CLEAR = 4;

    private static final String COPY_PROMPT = "Copy instructions for your agent";

    private static final String COPY_FOLLOW_UP = "Copy a follow-up for your agent";

    private static final String COPIED = "Copied";

    private static final String JUDGE_AGAIN = "Judge the faulty sheets again";

    // Says only what the press covers beyond the button's own label, and what it costs. What
    // happens to the decisions being replaced is bookkeeping the reader cannot act on.
    private static final String JUDGE_AGAIN_NOTE = "Any sheets still missing are judged too. That "
            + "spends from your provider account balance.";

    private static final String FINISH = "Finish this sift";

    // What the press does on a waiting run an agent judges. It looks at the folder again, and
    // finishes only if everything landed since the card was drawn. Where the app judges the sheets
    // itself the press dispatches them, spends, and finishes, so that route keeps the plainer
    // label.
    private static final String CHECK_AND_FINISH = "Check and finish";

    private static final String TROUBLESHOOT = "Troubleshoot";

    private static final String FINISH_WITHOUT_THE_MISSING = "Finish without the missing sheets";

    // Says nothing about what happens once shards arrive back. Whether they are picked up
    // automatically is Settings' watch mode, global rather than a choice this card makes.
    private static final String WAITING_ON_AN_AGENT = "Copy the instructions for your own agent. "
            + "They prompt it to write its decisions back into the folder below.";

    private static final String FOLLOW_UP_NOTE = "The follow-up asks your agent for every sheet "
            + "still outstanding, and discards any sheet that came back wrong so it can be judged "
            + "again. Copy it once your agent has stopped working.";

    private static final String WAITING_ON_A_PROVIDER = "Finishing it judges the sheets that are "
            + "left, and that spends from your provider account balance.";

    // Finishing is refused while a sheet came back wrong, so the note beside the button that does
    // the judging carries what this state costs instead.
    private static final String WAITING_ON_A_PROVIDER_BLAMED = "The sheets that are left cannot be "
            + "judged until the ones that came back wrong are dealt with.";

    private final Pipeline pipeline;
    private final RunLauncherPresenter launcher;

    // Volatile throughout. The reading is taken off the thread that paints. A job reporting that it
    // has ended writes the flag and the message from whatever thread it ran on.
    private volatile CullRuns runs = new CullRuns.Listed(List.of());
    // Two of them, because they are cleared by different things. A failed read is undone by the
    // next read that works. What a press had to report survives its own redraw, which takes no
    // reading. It goes on the next read, which is the reader leaving the screen and coming back.
    private volatile @Nullable Message readFailure;
    private volatile @Nullable Message message;
    private volatile boolean working;
    private volatile @Nullable Runnable repaint;
    private volatile @Nullable Runnable openDashboard;
    private volatile @Nullable BiConsumer<Path, String> openTroubleshoot;
    // The two halves a run moving on its own has to reach, held apart because they outlive each
    // other. The badge is in the shell and lives as long as the window. The cards are rebuilt on
    // every visit, and the one written here draws whichever screen is current.
    private volatile @Nullable Runnable redrawCount;
    private volatile @Nullable Runnable redrawCards;

    // Which run's copy control last handed something over, so the card draws it as Copied. Held
    // here rather than on the button because the press that fills it also files sheets away, and
    // the redraw that follows builds a new button.
    private volatile @Nullable Path justCopied;
    // The read that press triggers must not clear what the press just set, so the field survives
    // one read and the one after it clears.
    private volatile boolean copiedAwaitsItsRead;

    // Plain, unlike the fields above: one thread both writes and reads it, on the thread that
    // paints.
    private boolean completedShown;

    /**
     * Creates the presenter over the facade it reads runs through.
     *
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param launcher {@link RunLauncherPresenter} runs the job and reports it on the dashboard
     */
    public RunsPresenter(final Pipeline pipeline, final RunLauncherPresenter launcher) {
        this.pipeline = pipeline;
        this.launcher = launcher;
        // Registered once, for the life of the app. The screen behind it is rebuilt on every visit,
        // and a listener per visit would pile up.
        pipeline.onRunsMoved(this::runsMovedElsewhere);
    }

    /**
     * Says how the screen draws itself again once a job this screen started has ended.
     *
     * <p>Held rather than captured at each press. A discard outlives the screen that started it.
     * Nothing stops somebody opening Settings while it runs, and the runs screen they come back to
     * is a new one, and this field is what points at it.
     *
     * @param repaint {@link Runnable} reads the runs again and draws, off the thread that paints
     */
    public void setRepaint(final Runnable repaint) {
        this.repaint = repaint;
    }

    /**
     * Says how the sidebar's own count is drawn again once a run has moved on its own.
     *
     * <p>Told after the reading, so it draws the number the cards are about to show rather than the
     * one before it.
     *
     * @param redrawCount {@link Runnable} puts the current count on the badge. Called off the
     *     thread that paints, so it marshals for itself
     */
    public void setRedrawCount(final Runnable redrawCount) {
        this.redrawCount = redrawCount;
    }

    /**
     * Says how the cards are drawn again once a run has moved on its own.
     *
     * <p>The reading is already taken by the time this is called, unlike {@link #setRepaint}'s,
     * which takes its own. A second one would walk the whole folder to learn what is already held.
     *
     * @param redrawCards {@link Runnable} draws the cards from the reading now held. Called off the
     *     thread that paints, so it marshals for itself
     */
    public void setRedrawCards(final Runnable redrawCards) {
        this.redrawCards = redrawCards;
    }

    /**
     * Says how the screen hands the reader to the dashboard.
     *
     * <p>Carrying a sift on takes them there, because that is where a running job reports itself.
     *
     * @param openDashboard {@link Runnable} shows the dashboard. Called on the thread that paints
     */
    public void setOpenDashboard(final Runnable openDashboard) {
        this.openDashboard = openDashboard;
    }

    /**
     * Says how the screen opens the troubleshoot page for one run.
     *
     * <p>Told which run and what it covers, so the page that opens names it the way the card the
     * reader just pressed named it.
     *
     * @param openTroubleshoot a {@link BiConsumer} of {@link Path} and {@link String} shows that
     *     page for a run. Called on the thread that paints
     */
    public void setOpenTroubleshoot(final BiConsumer<Path, String> openTroubleshoot) {
        this.openTroubleshoot = openTroubleshoot;
    }

    /**
     * Reads every run on disk again.
     *
     * <p>A walk of the whole sift-prep root, reading every sidecar and shard of every run. Slow
     * enough on a real one to be seen, so a caller runs it off whatever thread paints.
     *
     * <p>A refusal is kept as the screen's own message rather than thrown on, so the reader is told
     * why on the screen in front of them. The roots being unusable is the ordinary one.
     *
     * <p>Also ages out what a press last had to say, and what a copy control last handed over. A
     * reader who leaves the screen and comes back is not still being told about a press they have
     * walked away from.
     */
    public void refresh() {
        this.forgetTheCopyAfterItsOwnRead();
        // A press reports against the run as it stood then. This reading may find a different one,
        // and a sentence about the old state reads as a claim about the new.
        this.message = null;
        this.reread();
    }


    /**
     * What the screen shows right now.
     *
     * @return {@link RunsView} every card, in the order they are drawn
     */
    public RunsView view() {
        // Read once into a local, and everything below works from it. The field is written by a
        // read on another thread, so asking it twice can answer twice about two different moments.
        final CullRuns reading = this.runs;
        final List<CullRunSummary> found = found(reading);
        final String unreadable = unreadable(reading);
        // Oldest timeline first, which is the order a reader already has in their head. Sorted here
        // rather than left to the reading's own order, since where the cards sit is this screen's
        // claim to keep.
        final List<RunCard> unfinished = found.stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .sorted(Comparator.comparing(CullRunSummary::scope))
                .map(this::card)
                .toList();
        final List<RunCard> completed = found.stream()
                .filter(run -> run.health().state() == State.COMPLETE)
                .map(this::card)
                .toList();
        final Message said = this.message == null ? this.readFailure : this.message;
        return new RunsView(HEADING, unreadable, unfinished,
                found.isEmpty() && unreadable == null ? NOTHING_YET : null,
                completedHeading(completed.size()), completed, this.completedShown,
                CLEAR_COMPLETED, !completed.isEmpty() && !this.working(),
                completed.isEmpty() ? null : clearConfirm(completed), said);
    }

    /**
     * How many runs the sidebar's entry counts.
     *
     * <p>Every run that is not finished, which is every run somebody could still pick up or throw
     * away. A folder that could not be read counts none. Nothing was established there, and a
     * number invented from a failed read is what the reading exists to avoid.
     *
     * @return int how many runs have not finished
     */
    public int unfinishedRuns() {
        return (int) found(this.runs).stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .count();
    }

    /**
     * Takes a press on one of a card's buttons.
     *
     * <p>Both kinds run as jobs, so both take the app's one job slot.
     *
     * @param action {@link Action} the button pressed, carrying the run it acts on
     */
    public void press(final Action action) {
        switch (action.kind()) {
            case CONTINUE -> this.carryOn(action.prepDir(), action.scope(), false);
            case CONTINUE_WITHOUT_THE_MISSING -> this.carryOn(action.prepDir(), action.scope(), true);
            case TROUBLESHOOT -> this.troubleshoot(action.prepDir(), action.scope());
            case DISCARD -> this.start("discard " + action.prepDir(),
                    () -> this.pipeline.discard(action.prepDir()));
        }
    }

    /**
     * What a copy button says once it has copied.
     *
     * @return {@link String} the label
     */
    public String copied() {
        return COPIED;
    }

    /**
     * Writes the instructions for the agent the reader drives themselves.
     *
     * <p>Built here rather than kept on the card, so a screen of waiting runs reads no index until
     * somebody asks for one.
     *
     * <p>A follow-up discards whatever came back unusable, then asks for every sheet outstanding,
     * both being sheets with no answer once the discard is done. A run with nothing to discard has
     * stalled rather than gone wrong, and its follow-up is the instructions themselves. The facade
     * decides which it is, so a run put right between the card being drawn and this press still
     * gets the answer that fits it rather than the one the card predicted.
     *
     * <p>A run whose records have gone bad between the draw and this press has none to write. That
     * is reported on the screen the same way a refused press is, and nothing reaches the clipboard.
     *
     * <p>Where sheets were filed away, the screen is told to read the run again and draw itself,
     * which it does off the thread that paints. Whether that happened is decided here rather than
     * by the card, which can only say what the press was offered as.
     *
     * @param prepDir {@link Path} the run
     * @param followUp boolean whether the card offered this as a follow-up
     * @return {@link String} the instructions, or null where they could not be written
     */
    public @Nullable String instructionsFor(final Path prepDir, final boolean followUp) {
        this.message = null;
        try {
            if (followUp) {
                try {
                    final String freed = this.pipeline.redoRejectedAnswers(prepDir);
                    this.justCopied = prepDir;
                    this.copiedAwaitsItsRead = true;
                    final Runnable draw = this.repaint;
                    if (draw != null) {
                        draw.run();
                    }
                    return freed;
                } catch (final Pipeline.NothingToRedoException stalled) {
                    log.info("Nothing to redo for {}, so the follow-up asks afresh", prepDir, stalled);
                }
            }
            return this.pipeline.launchPromptFor(prepDir);
        } catch (final RuntimeException e) {
            log.info("Could not write the instructions for {}", prepDir, e);
            this.message = new Message(RunRefusals.plainly(e), true);
            return null;
        }
    }

    /**
     * Sets a run's unusable answers aside, then does whatever getting them judged again takes.
     *
     * <p>What that is turns on who judges. An agent outside the app is handed instructions, which
     * come back for the caller to put on the clipboard. Where the app judges the sheets itself
     * there is nobody to hand anything to. The freed sheets are dispatched in the same press, and
     * nothing comes back. Freeing them and stopping there would destroy answers that were paid for
     * and spend nothing, which is a state no reader asked to be left in.
     *
     * <p>The run moves either way, so a caller redraws after this.
     *
     * <p>Answers nothing where the press was refused. The reason lands on the screen's own message
     * line, the way a refused press does, and nothing reaches the clipboard.
     *
     * @param prepDir {@link Path} the run
     * @param scope {@link String} the timeline it covers, for the run this may start
     * @return {@link String} the instructions to hand on, or null where there are none to hand on
     *     or the press was refused
     */
    public @Nullable String judgeAgain(final Path prepDir, final String scope) {
        this.message = null;
        if (!this.pipeline.configuredProviderSpends()) {
            return this.instructionsFor(prepDir, true);
        }
        try {
            this.pipeline.redoRejectedAnswers(prepDir);
        } catch (final RuntimeException e) {
            log.info("Could not set the rejected answers aside for {}", prepDir, e);
            this.message = new Message(RunRefusals.plainly(e), true);
            return null;
        }
        // The freed sheets are what this press exists to judge, so it never goes on without them.
        this.carryOn(prepDir, scope, false);
        return null;
    }

    /**
     * Clears every finished run's records.
     *
     * <p>Reports what the sweep did. A button that deletes and says nothing leaves a reader unable
     * to tell a sweep that found nothing from one that could not look.
     */
    public void clearCompleted() {
        this.start("clear the finished runs", () -> {
            final JobHandle<PurgeReport> handle = this.pipeline.purgeCompleted();
            handle.onComplete().thenAccept(report -> this.message = swept(report));
            return handle;
        });
    }

    /**
     * Folds the finished runs open or shut.
     */
    public void toggleCompleted() {
        this.completedShown = !this.completedShown;
    }

    /**
     * Whether anything is running that this screen's own controls have to wait for.
     *
     * <p>Asks the app rather than only remembering what this screen started. Carrying a run on is
     * handed to the dashboard, so a job this screen caused is one it holds no handle to. The
     * app also takes one job at a time whoever started it. A card offering a second while a sort
     * runs is offering a press that would be refused.
     *
     * @return boolean true while something is
     */
    public boolean working() {
        return this.working || this.pipeline.isBusy();
    }

    /**
     * Opens the screen that says what is wrong with one run and what can be done about it.
     *
     * @param prepDir {@link Path} the run
     * @param scope {@link String} what it covers, in the words this screen's card used
     */
    private void troubleshoot(final Path prepDir, final String scope) {
        this.message = null;
        final BiConsumer<Path, String> open = this.openTroubleshoot;
        if (open != null) {
            open.accept(prepDir, scope);
        }
    }

    /**
     * Carries one run on, and takes the reader to where that job reports itself.
     *
     * <p>Handed to the dashboard rather than run from here. A sift moving a reader's photos shows a
     * progress bar, a running count and a card saying what it did. All of that already exists on
     * one screen. Run from here it would move the photos behind a screen that looked stuck.
     *
     * <p>The reader is taken there rather than told to go. A press that starts several minutes of
     * work and leaves them where they were is a press that looks like it missed.
     *
     * @param prepDir {@link Path} the run to carry on
     * @param scope {@link String} what it covers, in the words this screen's card used
     * @param withoutTheMissing boolean whether to go on without the sheets still owed
     */
    private void carryOn(final Path prepDir, final String scope, final boolean withoutTheMissing) {
        this.message = null;
        this.launcher.continueRunFromRuns(prepDir, scope, withoutTheMissing);
        final Runnable open = this.openDashboard;
        if (open != null) {
            open.run();
        }
    }

    /**
     * Reads the folder again after something moved a run with nobody pressing anything, then tells
     * whoever is drawing from that reading.
     *
     * <p>Reads once here rather than leaving each of the two to read for itself. The sidebar's
     * count and the cards come from one reading by design. Two listeners each walking the whole
     * folder would pay for it twice and race over which reading won.
     *
     * <p>On a thread of its own, because the caller's may be the one that paints. A watch announces
     * from its polling thread, and a reader turning a watch on announces from the toolkit's. The
     * walk opens every sidecar and shard of every run, which is not something to do on either.
     */
    private void runsMovedElsewhere() {
        Thread.ofVirtual().start(() -> {
            this.reread();
            final Runnable badge = this.redrawCount;
            if (badge != null) {
                badge.run();
            }
            final Runnable draw = this.redrawCards;
            if (draw != null) {
                draw.run();
            }
        });
    }

    /**
     * Hands work to the facade and takes the screen into its working state.
     *
     * <p>Nothing on screen changes until the facade has taken the work. A refusal leaves the cards
     * exactly as they were, with a line saying why, which is the state a reader can act on.
     *
     * @param action {@link String} what was being attempted, for the log
     * @param submit a {@link Supplier} of {@link JobHandle} hands the work over
     */
    private void start(final String action, final Supplier<JobHandle<?>> submit) {
        this.message = null;
        try {
            final JobHandle<?> handle = submit.get();
            this.working = true;
            handle.onComplete().whenComplete((_, failure) -> this.ended(action, failure));
        } catch (final RuntimeException e) {
            log.info("Refused to {}", action, e);
            this.message = new Message(RunRefusals.plainly(e), true);
        }
    }

    /**
     * Takes the screen out of its working state and draws what the job left behind.
     *
     * <p>The flag drops before the redraw, so the screen the reader gets back is one whose buttons
     * are live again.
     *
     * @param action {@link String} what was attempted, for the log
     * @param failure {@link Throwable} what it threw, null where it did not
     */
    private void ended(final String action, final @Nullable Throwable failure) {
        if (failure != null) {
            log.warn("Could not {}", action, failure);
            this.message = new Message(RunRefusals.plainly(RunRefusals.rootOf(failure)), true);
        }
        this.working = false;
        final Runnable draw = this.repaint;
        if (draw != null) {
            draw.run();
        }
    }

    /**
     * What the runs folder said, or nothing where it could not be read.
     *
     * @return a {@link List} of {@link CullRunSummary} the runs found
     */
    private static List<CullRunSummary> found(final CullRuns reading) {
        return reading instanceof CullRuns.Listed(final List<CullRunSummary> listed)
                ? listed
                : List.of();
    }

    /**
     * What to say in place of the cards where the folder could not be read.
     *
     * @return {@link String} the sentence, or null where the folder was read
     */
    private static @Nullable String unreadable(final CullRuns reading) {
        return reading instanceof CullRuns.Unlistable(final Path root)
                ? UNREADABLE.formatted(root)
                : null;
    }

    /**
     * One run's card.
     *
     * <p>Whether a way back is offered at all is read off the findings, never off the tally. A
     * sheet the tally counts as invalid can be so for a reason no rewrite fixes. A run's own
     * problems can be about the run rather than about any one sheet.
     *
     * <p>Which button leads is decided here rather than by either half, because the answer is about
     * the pair. Finishing leads only where nothing is blamed on a sheet, since apply refuses on an
     * unusable answer however many have arrived.
     *
     * <p>The way back leads only where the app does the judging, and only where it could clear the
     * run outright. A press that spends is worth leading with where it finishes the job. Where an
     * agent outside the app judges, nothing on the card is dressed as the way on, whatever state
     * the run is in: what it waits on is not the reader, and a copy is a quiet act anyway.
     *
     * @param run {@link CullRunSummary} the run as it sits on disk
     * @return {@link RunCard} what the screen draws for it
     */
    private RunCard card(final CullRunSummary run) {
        final State state = run.health().state();
        final List<Finding> findings = run.health().findings();
        final boolean itJudgesThemItself = this.pipeline.configuredProviderSpends();
        final boolean blamesASheet = !LaunchPrompt.sheetsToRedo(findings).isEmpty();
        // On the agent route this slot is used only where there is no waiting block to hold the
        // follow-up. A card then carries one control rather than two saying near-enough the same.
        final boolean anythingToRedo = !this.working() && blamesASheet
                && (itJudgesThemItself || state == State.BLOCKED);
        final boolean redoLeads = anythingToRedo && itJudgesThemItself
                && findings.stream().allMatch(finding -> LaunchPrompt.sheetOf(finding) != null);
        final boolean waitingOnAnAgent = state == State.WAITING && !itJudgesThemItself;
        final List<Action> actions =
                this.actions(run, state, blamesASheet || waitingOnAnAgent, redoLeads);
        // Counted off the row, so the two cannot disagree about whether this card offers a way to
        // finish the run. Null where an agent judges. The offer sits with the card's text there,
        // which is where the waiting block puts it, so one press does not move the control.
        final Integer redoAt = itJudgesThemItself
                ? (int) actions.stream().filter(action -> action.kind() == Kind.CONTINUE).count()
                : null;
        return new RunCard("run-card-" + run.scope(), run.scope(), this.headline(state),
                this.detail(run, state), sheets(run.shards(), state), age(run.since()),
                this.waiting(run, state, blamesASheet),
                anythingToRedo ? this.redo(run, findings, redoLeads, redoAt) : null, actions);
    }

    /**
     * The card's own way back, on a run the diagnosis blames a sheet for.
     *
     * <p>Where the app judges the sheets this spans WAITING and BLOCKED, sitting beside the waiting
     * block on the first. Where an agent judges them it is BLOCKED only, a waiting run carrying its
     * follow-up in the block instead.
     *
     * <p>Where the app judges the sheets, the press frees them and judges them again in one
     * gesture, which spends, so it is asked about first. Where an agent does, it frees them and
     * hands back the follow-up to pass on, which spends nothing and needs no question.
     *
     * @param run {@link CullRunSummary} the run
     * @param findings a {@link List} of {@link Finding} what the diagnosis blamed
     * @param leads boolean whether this is the press the card is drawn to be reached for
     * @param drawnAt {@link Integer} where it sits in the card's button row, or null to sit with
     *     the card's own text
     * @return {@link RunsView.Redo} the control
     */
    private RunsView.Redo redo(final CullRunSummary run, final List<Finding> findings,
                               final boolean leads, final @Nullable Integer drawnAt) {
        final boolean itJudgesThemItself = this.pipeline.configuredProviderSpends();
        return new RunsView.Redo("run-redo-" + run.scope(),
                itJudgesThemItself ? JUDGE_AGAIN : COPY_FOLLOW_UP,
                itJudgesThemItself ? JUDGE_AGAIN_NOTE : FOLLOW_UP_NOTE,
                leads, drawnAt, run.prepDir(), run.scope(),
                itJudgesThemItself ? this.judgeAgainConfirm(run, findings) : null);
    }

    /**
     * What a reader is asked before sheets are judged again at their own expense.
     *
     * <p>Asked only where the app does the judging. On the other route the press spends nothing and
     * the reader's own agent redoes the work, so there is nothing to weigh.
     *
     * <p>Counts the sheets rather than pricing them. What they cost depends on the provider's rates
     * and the model, and neither is a number Sluice holds.
     *
     * <p>Counted the way the press chooses what to dispatch: the sheets the findings blame, plus
     * the sheets that never arrived. The tally's own valid count would be the wrong number here,
     * being computed per shard for display, so a fault spanning two shards leaves both of them
     * counted valid while the press dispatches them anyway.
     *
     * <p>Going ahead is the loud choice, unlike the discard below. Judging a few sheets again is
     * the cheaper of the two roads out. Backing away leaves a run nothing can finish, and the only
     * way on from there discards every answer already paid for.
     *
     * <p>That holds while throwing the whole run away is the only other road. Somewhere a reader
     * can discard single sheets, backing away costs them one sheet rather than all of them, and
     * the two choices weigh about the same.
     *
     * @param run {@link CullRunSummary} the run
     * @param findings a {@link List} of {@link Finding} what the diagnosis blamed
     * @return {@link Confirmation} what to ask
     */
    private Confirmation judgeAgainConfirm(final CullRunSummary run, final List<Finding> findings) {
        final ShardTally sheets = run.shards();
        final int missing = sheets == null ? 0 : sheets.total() - sheets.present();
        final int dispatched = LaunchPrompt.sheetsToRedo(findings).size() + missing;
        return new Confirmation("Judge the faulty sheets again?",
                RunWords.counted(dispatched, "sheet", "sheets") + " will be judged. That spends "
                        + "from your provider account balance.",
                "Judge again", "Leave it", true);
    }

    /**
     * What a card carries while its run is still short of judged sheets.
     *
     * <p>Only a waiting run has any of it. A ready run is owed nothing, so there is no folder to
     * point anybody at, and nothing for a watch to notice arriving.
     *
     * <p>Which shape it takes turns on whether the configured provider judges the sheets itself. A
     * provider that does needs no instructions handed out, since nobody outside the app is being
     * handed anything.
     *
     * <p>Reads the provider now rather than what the run was started under. Nothing on disk records
     * that, and the question this answers is what going on would do today.
     *
     * <p>A follow-up is withheld while a job runs, the way every button on the card is. It files
     * shards into the drawer, so offering it beside a resume already applying them would let one
     * press take work out from under the other.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @param blamesASheet boolean whether any finding is one a sheet could answer for
     * @return {@link RunsView.Waiting} the block, or null on a run past waiting
     */
    private RunsView.@Nullable Waiting waiting(final CullRunSummary run, final State state,
                                               final boolean blamesASheet) {
        if (state != State.WAITING) {
            return null;
        }
        final boolean itJudgesThemItself = this.pipeline.configuredProviderSpends();
        final boolean corrects = !itJudgesThemItself && !this.working()
                && anAgentLeftItUnfinished(run, state);
        final String asks = run.prepDir().equals(this.justCopied)
                ? COPIED
                : corrects ? COPY_FOLLOW_UP : COPY_PROMPT;
        return new RunsView.Waiting(run.prepDir(),
                itJudgesThemItself ? null : asks, corrects,
                itJudgesThemItself
                        ? blamesASheet ? WAITING_ON_A_PROVIDER_BLAMED : WAITING_ON_A_PROVIDER
                        : corrects ? FOLLOW_UP_NOTE : WAITING_ON_AN_AGENT);
    }

    /**
     * Whether the press should ask an agent to pick a run back up, rather than start it.
     *
     * <p>From the moment one sheet has an answer, or from the moment nothing more is coming. Both
     * are runs an agent has begun and left unfinished, whether it stopped or wrote answers nothing
     * could use, and the same follow-up serves both.
     *
     * <p>Nothing answered and sheets still owed is the one case this refuses. An agent that has
     * written nothing is not making mistakes, it is not working. What its owner needs then is to
     * stop it and start it again, rather than a follow-up naming the whole run.
     *
     * <p>Counts what arrived rather than what passed. An answer that came back unusable is still
     * an agent that started, and it is the one thing a follow-up exists to discard.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return boolean whether the press should ask for a follow-up
     */
    private static boolean anAgentLeftItUnfinished(final CullRunSummary run, final State state) {
        final ShardTally sheets = run.shards();
        return state != State.WAITING || (sheets != null && sheets.present() >= 1);
    }


    /**
     * What can be done about a run, in the order the buttons are drawn.
     *
     * <p>Continuing is offered only where it can help. A blocked run needs a decision first, and a
     * damaged one has said nothing about itself. Throwing away is the one thing that works whatever
     * state a run is in.
     *
     * <p>A blocked run is the one with something to look at, so it is the one offered the way in to
     * that. A damaged one established nothing, so it has no findings to act on. Reading it again is
     * what the screen already does on every visit.
     *
     * <p>A finished run offers nothing. Clearing them is one button at the top of the section, and
     * clearing exactly one while keeping the rest has no story behind it.
     *
     * <p>Every button goes dead while a job is running, since the app takes one at a time.
     *
     * <p>Finishing leads only where no answer has come back unusable. Apply refuses on one however
     * many sheets have arrived, so finishing is then a press that cannot get through, whether or
     * not the way back could clear the run either. A card where neither press can finish it draws
     * no filled button at all rather than dressing one of them as the way on.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @param blamesASheet boolean whether any finding is one a sheet could answer for
     * @param redoLeads boolean whether the card's own way back is already drawn as the way on, so
     *     Troubleshoot does not draw as a second one beside it
     * @return a {@link List} of {@link Action} the buttons
     */
    private List<Action> actions(final CullRunSummary run, final State state,
                                 final boolean blamesASheet, final boolean redoLeads) {
        if (state == State.COMPLETE || this.working()) {
            return List.of();
        }
        final List<Action> actions = new ArrayList<>();
        if (state == State.READY || state == State.WAITING) {
            actions.add(new Action("run-continue-" + run.scope(), this.finishLabel(state),
                    Kind.CONTINUE, !blamesASheet, run.prepDir(), run.scope(), null));
        }
        if (this.canGoOnWithoutTheMissing(run, state)) {
            actions.add(new Action("run-continue-partial-" + run.scope(), FINISH_WITHOUT_THE_MISSING,
                    Kind.CONTINUE_WITHOUT_THE_MISSING, false, run.prepDir(), run.scope(), null));
        }
        if (state == State.BLOCKED) {
            actions.add(new Action("run-troubleshoot-" + run.scope(), TROUBLESHOOT,
                    Kind.TROUBLESHOOT, !redoLeads, run.prepDir(), run.scope(), null));
        }
        actions.add(new Action("run-discard-" + run.scope(), "Discard", Kind.DISCARD, false,
                run.prepDir(), run.scope(), this.discardConfirm(run)));
        return actions;
    }

    /**
     * What the press that carries a run on says.
     *
     * <p>A waiting run an agent judges cannot be dispatched from here. The press re-reads the
     * folder and gets through only where every sheet has landed since the card was drawn, so the
     * label says both halves. Anywhere else the press finishes the run outright.
     *
     * @param state {@link State} the run's state
     * @return {@link String} the label
     */
    private String finishLabel(final State state) {
        return state == State.WAITING && !this.pipeline.configuredProviderSpends()
                ? CHECK_AND_FINISH
                : FINISH;
    }

    /**
     * Whether this run can be finished without waiting for the sheets nobody has answered.
     *
     * <p>False where the configured provider judges the sheets itself. Answering true there would
     * spend the reader's money rather than skip anything.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return boolean whether to offer it
     */
    private boolean canGoOnWithoutTheMissing(final CullRunSummary run, final State state) {
        final ShardTally sheets = run.shards();
        return state == State.WAITING && !this.pipeline.configuredProviderSpends()
                && sheets != null && sheets.present() < sheets.total();
    }

    /**
     * What a reader is asked before a run is thrown away.
     *
     * <p>Counts the sheet decisions being set aside rather than pricing them. A count is the same
     * number whichever provider produced them, and it is a number Sluice actually holds. What they
     * cost depends on the provider's own rates and on the model.
     *
     * <p>Only says they were paid for where the configured provider charges. On the other route the
     * judging is done by an agent the reader runs, which may have cost them nothing at all, and a
     * sentence telling them otherwise is asking for a decision on a fact the app made up.
     *
     * <p>Names the folder the records go to, because the reader can open it. Sluice offers no way
     * back to them from inside the app, and saying so is the part that decides whether somebody
     * presses this.
     *
     * @param run {@link CullRunSummary} the run
     * @return {@link Confirmation} what to ask
     */
    private Confirmation discardConfirm(final CullRunSummary run) {
        final ShardTally sheets = run.shards();
        final String judged = RunWords.counted(
                sheets == null ? 0 : sheets.valid(), "sheet decision", "sheet decisions");
        final String paidFor = sheets == null || sheets.valid() == 0
                ? ""
                : judged + (this.pipeline.configuredProviderSpends()
                        ? " you have already paid for are set aside with it. "
                        : " are set aside with it. ");
        return new Confirmation("Discard the sift of " + run.scope() + "?",
                paidFor + "Discarding this run's records will archive them. "
                        + "They will stay on disk in " + this.pipeline.archivesFolder()
                        + " for 30 days.",
                "Discard", "Keep", false);
    }

    /**
     * What state a run is in, in words a reader would use.
     *
     * @param state {@link State} the run's state
     * @return {@link String} the headline
     */
    private String headline(final State state) {
        if (state == State.WAITING && this.pipeline.configuredProviderSpends()) {
            return "Not finished";
        }
        return switch (state) {
            case READY -> "Ready to finish";
            case BLOCKED -> "Needs your call";
            case DAMAGED -> "Records could not be read";
            case WAITING -> "Waiting for sheets";
            case COMPLETE -> "Finished";
        };
    }

    /**
     * The sentence under the headline.
     *
     * <p>A waiting run whose provider judges its own sheets is not waiting on anybody. Nothing is
     * coming back on its own, so it is a sift that stopped part way rather than one in progress.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return {@link String} the sentence, or null where the headline says it all
     */
    private @Nullable String detail(final CullRunSummary run, final State state) {
        return state == State.WAITING && this.pipeline.configuredProviderSpends()
                ? "This sift stopped before every sheet was judged."
                : waitingOnSomebodyElse(run, state);
    }

    /**
     * The sentence under the headline, for every run whose sheets are not Sluice's own to judge.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return {@link String} the sentence, or null where the headline says it all
     */
    private static @Nullable String waitingOnSomebodyElse(final CullRunSummary run, final State state) {
        return switch (state) {
            case READY -> "Every sheet was judged. Once you finish the sift the photos will be "
                    + "moved to their category destinations.";
            case BLOCKED -> FindingFamily.nothingMoved(run.health().findings());
            // "Often", because the read failed and nothing here knows why. Naming the usual cause
            // is as far as this can honestly go.
            case DAMAGED -> "Often another program has the folder open.";
            case WAITING -> "Waiting for the rest of the sheets to come back.";
            case COMPLETE -> null;
        };
    }

    /**
     * How far through its sheets a run got.
     *
     * <p>Accounts for every sheet, so the three numbers add up to the total a reader can see. A
     * line naming only what was judged leaves them subtracting to find out whether the rest are
     * late or turned away, and those are different problems with different next steps.
     *
     * <p>Withheld from a stopped run whose sheets are all in and all sound. The tally counts what
     * arrived and parsed, which is narrower than the run being well. Where the sheets are not what
     * stopped it, the line calls them healthy under a heading saying the run needs a decision. Both
     * are true, and the reader is left to reconcile them.
     *
     * @param sheets {@link ShardTally} what the prep dir holds, or null where nothing counted them
     * @param state {@link State} the run's state
     * @return {@link String} the count, or null where there is none to give
     */
    private static @Nullable String sheets(final @Nullable ShardTally sheets, final State state) {
        if (sheets == null) {
            return null;
        }
        final int unusable = sheets.present() - sheets.valid();
        final int missing = sheets.total() - sheets.present();
        final String judged = RunWords.grouped(sheets.valid()) + " out of "
                + RunWords.grouped(sheets.total()) + " sheets are judged and healthy.";
        final List<String> rest = new ArrayList<>();
        if (unusable > 0) {
            rest.add(RunWords.grouped(unusable) + " came back wrong");
        }
        if (missing > 0) {
            rest.add(RunWords.grouped(missing) + (missing == 1 ? " is" : " are") + " still missing");
        }
        if (rest.isEmpty()) {
            return state == State.BLOCKED ? null : judged;
        }
        return judged + " " + String.join(" and ", rest) + ".";
    }

    /**
     * How long ago a run was last written to.
     *
     * @param since {@link Instant} when it was last written to
     * @return {@link String} how long ago, as a reader would say it
     */
    private static String age(final Instant since) {
        return "Last activity: " + RunWords.howLongAgo(since);
    }

    /**
     * What to ask before the records of every finished run go.
     *
     * <p>Asked at all because this is the one press on the screen with no way back. A discard files
     * what it takes into the archives folder for thirty days. This deletes outright.
     *
     * <p>Names them up to a handful, because a count alone leaves a reader working out which runs
     * are finished from a section that may be folded shut. Past that the list is longer than the
     * sentence around it and the heading's own count is the better answer.
     *
     * <p>Keeping is the loud choice, since deleting is the one of the two this app cannot undo.
     *
     * <p>Nothing here contrasts it with archiving. Archive is a word this app teaches on the
     * discard confirm, and a reader who has only ever pressed this button has never met it.
     *
     * @param completed a {@link List} of {@link RunCard} the finished runs the sweep would take
     * @return {@link Confirmation} the question
     */
    private static Confirmation clearConfirm(final List<RunCard> completed) {
        final List<String> scopes = completed.stream().map(RunCard::scope).toList();
        return new Confirmation("Clear the records of "
                + RunWords.counted(completed.size(), "finished run", "finished runs") + "?",
                (scopes.size() > NAMED_IN_A_CLEAR ? "This deletes everything kept about them."
                        : "This deletes everything kept about " + RunWords.listed(scopes) + ".")
                        + " Your photos are not touched, and neither is any run you have not "
                        + "finished. There is no way back to them.",
                "Clear them", "Keep them", false);
    }

    /**
     * What a sweep of the finished runs did, in the reader's own terms.
     *
     * <p>A root nobody could list is not a sweep that found nothing, and says so. Everything else
     * counts what went and what stayed, since a run left behind is one the reader may have expected
     * to see go.
     *
     * @param report {@link PurgeReport} what the sweep did
     * @return {@link Message} the line to show
     */
    private static Message swept(final PurgeReport report) {
        if (report.unlistableRoot() != null) {
            return new Message("Nothing was cleared, because " + report.unlistableRoot()
                    + " cannot be read. Most likely the folder is held by another process or not "
                    + "there anymore.", true);
        }
        final int leftBehind = report.skipped().size() + report.unreadable().size();
        final String cleared = report.purged().isEmpty()
                ? "No finished runs to clear."
                : "Cleared " + RunWords.counted(report.purged().size(), "finished run", "finished runs") + ".";
        final String unfinished = RunWords.counted(leftBehind, "run", "runs")
                + (leftBehind == 1
                ? " has not finished, so nothing from it was touched."
                : " have not finished, so nothing from them was touched.");
        return new Message(leftBehind == 0 ? cleared : cleared + " " + unfinished, false);
    }

    /**
     * The folded section's own label, carrying how many are in it.
     *
     * @param completed int how many runs have finished
     * @return {@link String} the label
     */
    private static String completedHeading(final int completed) {
        return "Finished runs (" + RunWords.grouped(completed) + ")";
    }

    /**
     * Lets what a copy control handed over stand through the read its own press sets off, and
     * clears it on the read after that.
     */
    private void forgetTheCopyAfterItsOwnRead() {
        if (this.copiedAwaitsItsRead) {
            this.copiedAwaitsItsRead = false;
            return;
        }
        this.justCopied = null;
    }

    /**
     * The reading half of {@link #refresh}, without either of its two agings-out.
     *
     * <p>A tally moving as a watched run gains a shard is not a reader leaving this screen and
     * coming back to it, which is what the two agings-out are about.
     */
    private void reread() {
        try {
            this.runs = this.pipeline.cullRuns();
            // Cleared on the way through, so a read that fails once and works after does not leave
            // its sentence pinned under cards that are now fine.
            this.readFailure = null;
        } catch (final PathsMisconfiguredException unset) {
            // Its message and no trace. An install nobody has configured yet meets this on every
            // start and every press. That is what a first run is, rather than anything going wrong.
            // A trace here fills the log a reader would send about something else.
            log.info("Could not read the runs: {}", unset.getMessage());
            this.runs = new CullRuns.Listed(List.of());
            this.readFailure = new Message(RunRefusals.plainly(unset), true);
        } catch (final RuntimeException e) {
            log.info("Could not read the runs", e);
            this.runs = new CullRuns.Listed(List.of());
            this.readFailure = new Message(RunRefusals.plainly(e), true);
        }
    }
}
