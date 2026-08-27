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

    private final Pipeline pipeline;

    // Volatile throughout. The reading is taken off the thread that paints. A job reporting that it
    // has ended writes the flag and the message from whatever thread it ran on.
    private volatile CullRuns runs = new CullRuns.Listed(List.of());
    // Two of them, because they are cleared by different things. A failed read is undone by the
    // next read that works. What a press had to report is undone by the next press.
    private volatile @Nullable Message readFailure;
    private volatile @Nullable Message message;
    private volatile boolean working;
    private volatile @Nullable Runnable repaint;

    // Plain, unlike the fields above: one thread both writes and reads it, on the thread that
    // paints.
    private boolean completedShown;

    /**
     * Creates the presenter over the facade it reads runs through.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     */
    public RunsPresenter(final Pipeline pipeline) {
        this.pipeline = pipeline;
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
                CLEAR_COMPLETED, !completed.isEmpty() && !this.working, said);
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
     * <p>Both kinds run as jobs, so both take the app's one job slot. What this screen keeps is the
     * refusal and the fact that something is running, since those are what its own reader is
     * waiting on.
     *
     * <p>A job started here reports nowhere while it runs, and says nothing when it works. The
     * progress area belongs to the launcher and draws only what the launcher itself started. What a
     * reader gets is the card moving and the count changing.
     *
     * @param action {@link Action} the button pressed, carrying the run it acts on
     */
    public void press(final Action action) {
        this.start(action.kind() + " " + action.prepDir(), () -> switch (action.kind()) {
            case CONTINUE -> this.pipeline.resume(action.prepDir(), false);
            case DISCARD -> this.pipeline.discard(action.prepDir());
        });
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
     * Whether a job this screen started is still running.
     *
     * @return boolean true while one is
     */
    public boolean working() {
        return this.working;
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
        return new RunCard("run-card-" + run.scope(), run.scope(), headline(state),
                detail(run, state), sheets(run.shards()), age(run.since()),
                this.actions(run, state));
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
        if (state == State.COMPLETE || this.working) {
            return List.of();
        }
        final List<Action> actions = new ArrayList<>();
        if (state == State.READY || state == State.WAITING) {
            actions.add(new Action("run-continue-" + run.scope(),
                    state == State.READY ? "Finish this sift" : "Check for answers",
                    Kind.CONTINUE, true, run.prepDir(), null));
        }
        actions.add(new Action("run-discard-" + run.scope(), "Discard", Kind.DISCARD, false,
                run.prepDir(), this.discardConfirm(run)));
        return actions;
    }

    /**
     * What a reader is asked before a run is thrown away.
     *
     * <p>Counts the sheet decisions being set aside rather than pricing them. A count is the same
     * number whichever provider produced them, and it is a number Sluice actually holds. What they
     * cost depends on the provider's own rates, on the model, and on whether the reader runs one
     * themselves for nothing.
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
        final String paidFor = sheets == null || sheets.valid() == 0
                ? ""
                : RunWords.counted(sheets.valid(), "sheet decision", "sheet decisions")
                        + " you have already paid for are set aside with it. ";
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
    private static String headline(final State state) {
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
     * @param run {@link CullRunSummary} the run
     * @param state {@link State} its state
     * @return {@link String} the sentence, or null where the headline says it all
     */
    private static @Nullable String detail(final CullRunSummary run, final State state) {
        return switch (state) {
            case READY -> "Every sheet was judged. Once you finish the sift the photos will be "
                    + "moved to their category destinations.";
            case BLOCKED -> FindingFamily.wentWrong(run.health().findings())
                    + ", so none of these photos were moved.";
            // "Often", because the read failed and nothing here knows why. Naming the usual cause
            // is as far as this can honestly go.
            case DAMAGED -> "Often something else is holding the folder, a backup or a sync tool. "
                    + "Sluice looks again every time you open this screen.";
            case WAITING -> "Waiting for the rest of the sheets to come back.";
            case COMPLETE -> null;
        };
    }

    /**
     * How far through its sheets a run got.
     *
     * @param sheets {@link ShardTally} what the prep dir holds, or null where nothing counted them
     * @return {@link String} the count, or null where there is none
     */
    private static @Nullable String sheets(final @Nullable ShardTally sheets) {
        return sheets == null
                ? null
                : RunWords.grouped(sheets.valid()) + " of " + RunWords.grouped(sheets.total())
                        + " sheets judged";
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
