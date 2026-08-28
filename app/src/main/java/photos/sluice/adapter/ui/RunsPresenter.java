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
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final String COPY_FOLDER = "Copy folder path";

    private static final String COPY_PROMPT = "Copy instructions for your agent";

    private static final String COPIED = "Copied";

    // One label for that press whatever state the run is in. What it attempts is the same in all of
    // them, and the card's own tally already says whether it can get there.
    private static final String FINISH = "Finish this sift";

    private static final String AUTO_APPLY = "Move the photos as soon as the answers are all in";

    private static final String WAIVE_MISSING = "Go on without the sheets that are still missing";

    // Says nothing about what happens afterwards. The toggle under it answers that, and answers it
    // two ways, so a sentence here would contradict one of them.
    private static final String WAITING_ON_AN_AGENT = "Copy the instructions for your own agent. "
            + "They prompt it to write its answers back into this folder.";

    private static final String WAITING_ON_A_PROVIDER = "Finishing it judges the sheets that are "
            + "left, and that spends from your provider account balance.";

    private final Pipeline pipeline;
    private final RunLauncherPresenter launcher;

    // Volatile throughout. The reading is taken off the thread that paints. A job reporting that it
    // has ended writes the flag and the message from whatever thread it ran on.
    private volatile CullRuns runs = new CullRuns.Listed(List.of());
    // Two of them, because they are cleared by different things. A failed read is undone by the
    // next read that works. What a press had to report is undone by the next press.
    private volatile @Nullable Message readFailure;
    private volatile @Nullable Message message;
    private volatile boolean working;
    private volatile @Nullable Runnable repaint;
    private volatile @Nullable Runnable openDashboard;
    // The two halves a run moving on its own has to reach, held apart because they outlive each
    // other. The badge is in the shell and lives as long as the window. The cards are rebuilt on
    // every visit, and the one written here draws whichever screen is current.
    private volatile @Nullable Runnable redrawCount;
    private volatile @Nullable Runnable redrawCards;

    // Which runs a reader has said to go on without their missing sheets. Concurrent because a
    // redraw reads it off the thread that paints while a press writes it. Nothing prunes it:
    // filling it would take thousands of discarded and re-sifted scopes in one sitting.
    private final Set<Path> waiveMissing = ConcurrentHashMap.newKeySet();

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
     * Reads every run on disk again.
     *
     * <p>A walk of the whole sift-prep root, reading every sidecar and shard of every run. Slow
     * enough on a real one to be seen, so a caller runs it off whatever thread paints.
     *
     * <p>A refusal is kept as the screen's own message rather than thrown on, so the reader is told
     * why on the screen in front of them. The roots being unusable is the ordinary one.
     */
    public void refresh() {
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
        final List<RunCard> unfinished = found.stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .sorted(Comparator.comparingInt(run -> urgency(run.health().state())))
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
                CLEAR_COMPLETED, !completed.isEmpty() && !this.working(), said);
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
            case CONTINUE -> this.carryOn(action.prepDir(), action.scope());
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
     * Copies one run's folder path, for a reader who wants to go and look at it.
     *
     * @param prepDir {@link Path} the run
     * @return {@link String} the path, as text
     */
    public String folderPath(final Path prepDir) {
        return prepDir.toString();
    }

    /**
     * Writes the instructions for the agent the reader drives themselves.
     *
     * <p>Built here rather than kept on the card, so a screen of waiting runs reads no index until
     * somebody asks for one.
     *
     * <p>A run whose records have gone bad between the draw and this press has none to write. That
     * is reported on the screen the same way a refused press is, and nothing reaches the clipboard.
     *
     * @param prepDir {@link Path} the run
     * @return {@link String} the instructions, or null where they could not be written
     */
    public @Nullable String instructionsFor(final Path prepDir) {
        try {
            return this.pipeline.launchPromptFor(prepDir);
        } catch (final RuntimeException e) {
            log.info("Could not write the instructions for {}", prepDir, e);
            this.message = new Message(RunRefusals.plainly(e), true);
            return null;
        }
    }

    /**
     * Turns one run's auto-apply on or off.
     *
     * <p>Says nothing back. Where it worked, the toggle's next draw reads the engine and shows it;
     * where it did not, the same draw shows the toggle back where it started.
     *
     * @param prepDir {@link Path} the run
     * @param on boolean where the reader put the toggle
     */
    public void setAutoApply(final Path prepDir, final boolean on) {
        try {
            if (on) {
                this.pipeline.startWatching(prepDir);
            } else {
                this.pipeline.stopWatching(prepDir);
            }
        } catch (final RuntimeException e) {
            log.info("Could not change the watch on {}", prepDir, e);
            this.message = new Message(RunRefusals.plainly(e), true);
        }
    }

    /**
     * Records whether carrying this run on means carrying it on without the sheets still owed.
     *
     * @param prepDir {@link Path} the run
     * @param on boolean where the reader put the control
     */
    public void setWaiveMissing(final Path prepDir, final boolean on) {
        if (on) {
            this.waiveMissing.add(prepDir);
        } else {
            this.waiveMissing.remove(prepDir);
        }
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
     */
    private void carryOn(final Path prepDir, final String scope) {
        this.message = null;
        this.launcher.continueRunFromRuns(prepDir, scope, this.waiveMissing.contains(prepDir));
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
            this.refresh();
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
     * @param run {@link CullRunSummary} the run as it sits on disk
     * @return {@link RunCard} what the screen draws for it
     */
    private RunCard card(final CullRunSummary run) {
        final State state = run.health().state();
        return new RunCard("run-card-" + run.scope(), run.scope(), this.headline(state),
                this.detail(run, state), sheets(run.shards()), age(run.since()),
                this.waiting(run, state), this.actions(run, state));
    }

    /**
     * What a card carries while its run is still short of judged sheets.
     *
     * <p>Only a waiting run has any of it. A ready run is owed nothing, so there is no folder to
     * point anybody at, nothing to waive, and nothing for a watch to notice arriving.
     *
     * <p>Which shape it takes turns on whether the configured provider judges the sheets itself. A
     * provider that does needs no instructions handed out, since nobody outside the app is being
     * handed anything.
     *
     * <p>It is offered no way to go on without the missing sheets either, and that one is a money
     * question rather than a tidiness one. Going on dispatches every sheet still lacking an answer
     * whatever this says, so a control promising to skip them would charge for them instead. There
     * is nothing to go on without when the thing that judges them is the app.
     *
     * <p>The toggle is drawn wherever a watch could be turned off, which is not the same as
     * wherever one could be turned on. The engine arms only for the manual provider, and it checks
     * that as the arm is made. A reader who armed one and then configured a paying provider still
     * has a watcher polling, and taking the control away would leave them no way to stop it.
     *
     * <p>Reads the provider now rather than what the run was started under. Nothing on disk records
     * that, and the question this answers is what going on would do today.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return {@link RunsView.Waiting} the block, or null on a run past waiting
     */
    private RunsView.@Nullable Waiting waiting(final CullRunSummary run, final State state) {
        if (state != State.WAITING) {
            return null;
        }
        final boolean itJudgesThemItself = this.pipeline.configuredProviderSpends();
        final boolean watched = this.pipeline.isWatchActive(run.prepDir());
        final RunsView.Switch autoApply = itJudgesThemItself && !watched
                ? null
                : new RunsView.Switch("run-auto-apply-" + run.scope(), AUTO_APPLY, watched);
        return new RunsView.Waiting(run.prepDir(), COPY_FOLDER,
                itJudgesThemItself ? null : COPY_PROMPT, autoApply,
                itJudgesThemItself ? null
                        : new RunsView.Switch("run-waive-missing-" + run.scope(), WAIVE_MISSING,
                                this.waiveMissing.contains(run.prepDir())),
                itJudgesThemItself ? WAITING_ON_A_PROVIDER : WAITING_ON_AN_AGENT);
    }

    /**
     * What can be done about a run, in the order the buttons are drawn.
     *
     * <p>Continuing is offered only where it can help. A blocked run needs a decision first, and a
     * damaged one has said nothing about itself. Throwing away is the one thing that works whatever
     * state a run is in.
     *
     * <p>A finished run offers nothing. Clearing them is one button at the top of the section, and
     * clearing exactly one while keeping the rest has no story behind it.
     *
     * <p>Every button goes dead while a job is running, since the app takes one at a time.
     *
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return a {@link List} of {@link Action} the buttons
     */
    private List<Action> actions(final CullRunSummary run, final State state) {
        if (state == State.COMPLETE || this.working()) {
            return List.of();
        }
        final List<Action> actions = new ArrayList<>();
        if (state == State.READY || state == State.WAITING) {
            actions.add(new Action("run-continue-" + run.scope(), FINISH, Kind.CONTINUE, true,
                    run.prepDir(), run.scope(), null));
        }
        actions.add(new Action("run-discard-" + run.scope(), "Discard", Kind.DISCARD, false,
                run.prepDir(), run.scope(), this.discardConfirm(run)));
        return actions;
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
                "Discard", "Keep");
    }

    /**
     * Where one state sits in a list ordered by what wants somebody first.
     *
     * <p>Ready leads because it is one press from finished and costs nothing. Blocked next, since
     * it is the one waiting on a decision only a person can make. Then damaged, which usually
     * clears itself. Then waiting, since what it waits on is not the reader. A finished run sorts
     * after all of them.
     *
     * @param state {@link State} the run's state
     * @return int lower sorts higher up the screen
     */
    private static int urgency(final State state) {
        return switch (state) {
            case READY -> 0;
            case BLOCKED -> 1;
            case DAMAGED -> 2;
            case WAITING -> 3;
            case COMPLETE -> 4;
        };
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
        if (state == State.WAITING && this.pipeline.configuredProviderSpends()) {
            return "This sift stopped before every sheet was judged.";
        }
        return waitingOnSomebodyElse(run, state);
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
            case BLOCKED -> FindingFamily.wentWrong(run.health().findings())
                    + ", so none of these photos were moved.";
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
     * <p>Names the answers that came back but cannot be used, where there are any. Otherwise a
     * reader whose count has stopped climbing cannot tell an agent that has not answered from one
     * whose answers are being turned away.
     *
     * @param sheets {@link ShardTally} what the prep dir holds, or null where nothing counted them
     * @return {@link String} the count, or null where there is none
     */
    private static @Nullable String sheets(final @Nullable ShardTally sheets) {
        if (sheets == null) {
            return null;
        }
        final int unusable = sheets.present() - sheets.valid();
        final String judged = RunWords.grouped(sheets.valid()) + " of "
                + RunWords.grouped(sheets.total()) + " sheets judged";
        return unusable == 0
                ? judged
                : judged + ", and " + RunWords.counted(unusable, "answer", "answers")
                        + " could not be read";
    }

    /**
     * How long ago a run was last written to, at the coarsest honest precision.
     *
     * <p>A prep dir nobody could stat is aged as the epoch. That reads here as not known, rather
     * than as a date in 1970.
     *
     * @param since {@link Instant} when it was last written to
     * @return {@link String} how long ago, as a reader would say it
     */
    private static String age(final Instant since) {
        if (Instant.EPOCH.equals(since)) {
            return "Last activity: not known";
        }
        final Duration ago = Duration.between(since, Instant.now());
        if (ago.toHours() < 1) {
            return "Last activity: less than an hour ago";
        }
        if (ago.toDays() < 1) {
            return "Last activity: " + RunWords.counted((int) ago.toHours(), "hour", "hours") + " ago";
        }
        return "Last activity: " + RunWords.counted((int) ago.toDays(), "day", "days") + " ago";
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
}
