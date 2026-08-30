package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.TroubleshootView.Action;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.adapter.ui.TroubleshootView.Deed;
import photos.sluice.adapter.ui.TroubleshootView.Detail;
import photos.sluice.adapter.ui.TroubleshootView.Option;
import photos.sluice.adapter.ui.TroubleshootView.Problem;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.ChoiceAnswer;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.LaunchPrompt;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decides what the troubleshoot screen shows for one run, and what a press on it does.
 *
 * <p>Opened against a run, it runs a troubleshoot pass as a job, then draws what that pass could
 * not put right. Each row's answer goes back through the facade, and the run is read again after
 * every one. What is on screen is therefore what is on disk, rather than what the pass found.
 *
 * <p>Every reading blocks while it walks the run's own files, so a caller runs {@link #press} off
 * whatever thread paints. {@link #open} is safe to call from that thread: it starts the pass on
 * the job runner and returns before any of it reads a file.
 */
@Component
@Profile("!cli")
public class TroubleshootPresenter {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootPresenter.class);

    // Named for the screen it goes back to, the way the other way back on a screen with no sidebar
    // entry of its own is.
    private static final String BACK = "Back to Runs";

    private static final String CHECKING = "Looking through this sift's records...";

    private static final String DETAIL = "Technical detail";

    private static final String COPY_DETAIL = "Copy";

    private static final String COPIED = "Copied";

    private static final String FINISH = "Finish this sift";

    // Said where the pass has run and every problem left is one no answer can settle. Naming what
    // to do matters more than naming the count, since neither control is on this screen.
    private static final String NOTHING_TO_ANSWER_WITH_SHEETS_TO_REDO =
            "Those sheets are unusable and nothing here can settle them. Have them judged again, "
            + "or discard the sift.";

    private static final String NOTHING_TO_ANSWER =
            "Nothing here can settle these, and no sheet can be judged again to clear them. "
            + "Discarding the sift is the only thing that can be done.";

    private static final String LOOKED_AGAIN_GONE = "That is no longer a problem.";

    private static final String LOOKED_AGAIN_STILL_THERE = "It is still missing.";

    private static final String RUN_MOVED = "This sift changed while you were looking at it, so it "
            + "has been read again.";

    private final Pipeline pipeline;
    private final RunLauncherPresenter launcher;

    // Volatile throughout. Both readings are taken off the thread that paints, and the job reports
    // its ending from whatever thread it ran on.
    private volatile @Nullable Path prepDir;
    private volatile String scope = "";
    private volatile boolean checking;
    private volatile @Nullable TroubleshootReport report;
    private volatile List<Finding> showing = List.of();
    private volatile State state = State.BLOCKED;
    private volatile @Nullable Message message;
    private volatile @Nullable Runnable repaint;
    private volatile @Nullable Runnable openRuns;
    private volatile @Nullable Runnable openDashboard;
    private volatile boolean detailCopied;
    private volatile boolean discarding;

    // What the reader has answered this visit, in the order they answered it. An answer resolves
    // the finding, so the next reading does not report it, and the row it collapses to has nothing
    // left to be rebuilt from. Added to from a virtual thread a button press starts and read and
    // cleared from the thread that paints, so a plain list is not safe here.
    private final List<Problem> settled = new CopyOnWriteArrayList<>();

    /**
     * Creates the presenter over the facade it reads and answers through.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param launcher {@link RunLauncherPresenter} runs the sift and reports it on the dashboard
     */
    public TroubleshootPresenter(final Pipeline pipeline, final RunLauncherPresenter launcher) {
        this.pipeline = pipeline;
        this.launcher = launcher;
    }

    /**
     * Says how the screen draws itself again once something it started has finished.
     *
     * @param repaint {@link Runnable} draws the screen. Called off the thread that paints, so it
     *     marshals for itself
     */
    public void setRepaint(final Runnable repaint) {
        this.repaint = repaint;
    }

    /**
     * Says how the screen hands the reader back to the runs list.
     *
     * @param openRuns {@link Runnable} shows the runs screen. Called on the thread that paints
     */
    public void setOpenRuns(final Runnable openRuns) {
        this.openRuns = openRuns;
    }

    /**
     * Says how the screen hands the reader to the dashboard.
     *
     * <p>Finishing a sift takes them there, because that is where a running job reports itself.
     *
     * @param openDashboard {@link Runnable} shows the dashboard. Called on the thread that paints
     */
    public void setOpenDashboard(final Runnable openDashboard) {
        this.openDashboard = openDashboard;
    }

    /**
     * Points the screen at one run and looks through its records.
     *
     * <p>Everything the last run left behind goes first, so a reader arriving from a second card
     * never meets the first one's answers or its report.
     *
     * <p>Reads nothing itself. The pass is handed to the job runner and the screen draws while it
     * runs, so this is safe to call from the thread that paints.
     *
     * @param prepDir {@link Path} the run to look at
     * @param scope {@link String} what it covers, in the words the card that led here used
     */
    public void open(final Path prepDir, final String scope) {
        this.prepDir = prepDir;
        this.scope = scope;
        this.report = null;
        this.message = null;
        this.detailCopied = false;
        this.settled.clear();
        this.showing = List.of();
        this.state = State.BLOCKED;
        this.checking = true;
        this.startTheCheck(prepDir);
    }

    /**
     * What the screen shows right now.
     *
     * @return {@link TroubleshootView} every row and every button, in the order they are drawn
     */
    public TroubleshootView view() {
        final List<Finding> open = this.showing;
        final List<Problem> problems = new ArrayList<>();
        for (int at = 0; at < open.size(); at++) {
            problems.add(this.problem(at, open.get(at)));
        }
        problems.addAll(this.settled);
        final TroubleshootReport pass = this.report;
        return new TroubleshootView("Troubleshoot " + this.scope, BACK,
                this.checking ? CHECKING : null, this.summary(pass, open),
                problems, this.nothingLeft(pass, open),
                pass == null ? null : new Detail(DETAIL, pass.text(), COPY_DETAIL, COPIED),
                this.actions(), this.message);
    }

    /**
     * Takes a press on one problem's own buttons.
     *
     * <p>The finding is taken from the reading this screen was drawn from rather than rebuilt from
     * anything the button carried. A run that moved since it was drawn is read again instead, and
     * nothing is answered against a diagnosis that has been overtaken.
     *
     * <p>Reads the run's own files, so a caller runs it off whatever thread paints.
     *
     * @param problem {@link Problem} the row pressed
     * @param option {@link Option} the answer chosen
     */
    public void press(final Problem problem, final Option option) {
        final Path run = this.prepDir;
        if (run == null) {
            return;
        }
        this.message = null;
        final Finding finding = problem.finding();
        // Found by identity rather than by the index the row was drawn at. Answering one finding
        // resolves it, so every later index shifts. A row the reader can still see would otherwise
        // be answered against whatever slid into its place.
        if (finding == null || !this.showing.contains(finding)) {
            this.overtaken(run);
            return;
        }
        if (option.answer() == Answer.RECHECK) {
            this.lookAgain(run, finding);
            return;
        }
        final ChoiceAnswer answer = answerFor(finding, option.answer());
        if (answer == null) {
            this.overtaken(run);
            return;
        }
        this.answer(run, problem, option.answer(), answer);
    }

    /**
     * Takes a press on one of the buttons acting on the whole run.
     *
     * @param action {@link Action} the button pressed
     */
    public void press(final Action action) {
        final Path run = this.prepDir;
        if (run == null) {
            return;
        }
        switch (action.deed()) {
            case FINISH -> this.finish(run);
            case DISCARD -> this.discard(run);
        }
    }

    /**
     * Hands the reader back to the runs list.
     */
    public void back() {
        final Runnable runs = this.openRuns;
        if (runs != null) {
            runs.run();
        }
    }

    /**
     * The technical report, for putting on the clipboard.
     *
     * @return {@link String} the report, or null where no pass has produced one
     */
    public @Nullable String detail() {
        final TroubleshootReport pass = this.report;
        if (pass == null) {
            return null;
        }
        this.detailCopied = true;
        return pass.text();
    }

    /**
     * Whether the report has been copied since this screen was opened.
     *
     * @return boolean true once it has
     */
    public boolean detailCopied() {
        return this.detailCopied;
    }

    /**
     * Whether anything is running that this screen's own controls have to wait for.
     *
     * @return boolean true while something is
     */
    public boolean working() {
        return this.checking || this.discarding || this.pipeline.isBusy();
    }

    /**
     * Runs the pass, and holds what it reported.
     *
     * @param run {@link Path} the run to look through
     */
    private void startTheCheck(final Path run) {
        try {
            final JobHandle<TroubleshootReport> handle = this.pipeline.troubleshoot(run);
            handle.onComplete().whenComplete((pass, failure) -> this.checked(run, pass, failure));
        } catch (final RuntimeException e) {
            log.info("Could not look through {}", run, e);
            this.checking = false;
            this.message = new Message(RunRefusals.plainly(e), true);
            this.draw();
        }
    }

    /**
     * Takes what the pass reported, then reads the run again for what is still open.
     *
     * <p>The reading rather than the pass's own {@code after} is what the rows come from. The two
     * agree the instant the pass ends, and only the reading keeps agreeing once an answer has moved
     * something.
     *
     * @param run {@link Path} the run that was looked through
     * @param pass {@link TroubleshootReport} what it reported, null where it threw
     * @param failure {@link Throwable} what it threw, null where it did not
     */
    private void checked(final Path run, final @Nullable TroubleshootReport pass,
                         final @Nullable Throwable failure) {
        if (failure != null) {
            log.warn("Could not look through {}", run, failure);
            this.message = new Message(RunRefusals.plainly(RunRefusals.rootOf(failure)), true);
        }
        this.report = pass;
        this.checking = false;
        this.reread(run);
        this.draw();
    }

    /**
     * Records one answer, then reads the run again.
     *
     * @param run {@link Path} the run being answered
     * @param problem {@link Problem} the row answered
     * @param chosen {@link Answer} what the reader chose
     * @param answer {@link ChoiceAnswer} what that means for this finding
     */
    private void answer(final Path run, final Problem problem, final Answer chosen,
                        final ChoiceAnswer answer) {
        try {
            this.pipeline.answer(run, answer, AnswerSource.DESKTOP);
        } catch (final RuntimeException e) {
            log.info("Could not answer {} on {}", chosen, run, e);
            this.message = new Message(RunRefusals.plainly(e), true);
            return;
        }
        // Numbered by how many have settled rather than off the row's own id. Answering the top
        // problem twice running draws both rows from index 0, so the id it came with is not unique.
        this.settled.add(new Problem("troubleshoot-settled-" + this.settled.size(), null,
                problem.problem(), problem.about(), FindingWords.settled(chosen), List.of()));
        this.reread(run);
    }

    /**
     * Reads the run again to see whether a restored photo is where the sift expects it.
     *
     * <p>Nothing is recorded either way. The reader either put the file back or did not, and the
     * diagnosis is what says which.
     *
     * @param run {@link Path} the run
     * @param finding {@link Finding} the problem the reader answered
     */
    private void lookAgain(final Path run, final Finding finding) {
        this.reread(run);
        // An answer that ran and left the problem standing is what the reader has to notice. Said
        // in the tone of a confirmation, it reads as the press having worked.
        final boolean stillThere = this.showing.contains(finding);
        this.message = new Message(stillThere ? LOOKED_AGAIN_STILL_THERE : LOOKED_AGAIN_GONE,
                stillThere);
    }

    /**
     * Reads the run again after it turned out to have moved under the screen.
     *
     * @param run {@link Path} the run
     */
    private void overtaken(final Path run) {
        this.reread(run);
        this.message = new Message(RUN_MOVED, false);
    }

    /**
     * Reads one run's own records again.
     *
     * @param run {@link Path} the run
     */
    private void reread(final Path run) {
        try {
            final CullRunSummary summary = this.pipeline.cullRun(run);
            this.showing = summary.health().findings();
            this.state = summary.health().state();
        } catch (final RuntimeException e) {
            log.info("Could not read {}", run, e);
            this.showing = List.of();
            this.message = new Message(RunRefusals.plainly(e), true);
        }
    }

    /**
     * Carries the run on, and takes the reader to where that job reports itself.
     *
     * @param run {@link Path} the run
     */
    private void finish(final Path run) {
        this.message = null;
        this.launcher.continueRunFromRuns(run, this.scope, false);
        final Runnable dashboard = this.openDashboard;
        if (dashboard != null) {
            dashboard.run();
        }
    }

    /**
     * Gives up on the run, and hands the reader back to the list it came from.
     *
     * <p>Leaves only once the job has finished. Most of what refuses a discard is raised inside the
     * job rather than out of the call that starts it. A screen leaving straight away would take the
     * reader off the one page able to say so.
     *
     * @param run {@link Path} the run
     */
    private void discard(final Path run) {
        this.message = null;
        final JobHandle<DiscardReport> handle;
        try {
            handle = this.pipeline.discard(run);
        } catch (final RuntimeException e) {
            log.info("Refused to discard {}", run, e);
            this.message = new Message(RunRefusals.plainly(e), true);
            this.draw();
            return;
        }
        this.discarding = true;
        this.draw();
        handle.onComplete().whenComplete((_, failure) -> this.discarded(run, failure));
    }

    /**
     * Takes what the discard did, and leaves the screen only where it worked.
     *
     * @param run {@link Path} the run
     * @param failure {@link Throwable} what it threw, null where it did not
     */
    private void discarded(final Path run, final @Nullable Throwable failure) {
        this.discarding = false;
        if (failure != null) {
            log.warn("Could not discard {}", run, failure);
            this.message = new Message(RunRefusals.plainly(RunRefusals.rootOf(failure)), true);
            this.draw();
            return;
        }
        this.back();
    }

    /**
     * One still-open problem's row.
     *
     * @param at int where the finding sits in the reading this row was drawn from
     * @param finding {@link Finding} the fault
     * @return {@link Problem} the row
     */
    private Problem problem(final int at, final Finding finding) {
        final FindingWords.Told told = FindingWords.of(finding);
        final List<Option> options = new ArrayList<>();
        for (final FindingWords.Choice choice : told.choices()) {
            options.add(new Option("troubleshoot-answer-" + at + "-" + choice.answer(),
                    choice.label(), choice.answer(), choice.leading() && !this.working(),
                    choice.confirm()));
        }
        return new Problem("troubleshoot-problem-" + at, finding, told.problem(), told.about(),
                null, this.working() ? List.of() : options);
    }

    /**
     * What the pass found and what it put right, as a count rather than a sentence about itself.
     *
     * <p>Counts the repairs the report names rather than the difference between the two diagnoses.
     * A repair can uncover a problem that was hidden behind it, so a subtraction can report fewer
     * repairs than were made, or none at all. The two numbers do not add up to a total for the
     * same reason, so none is offered.
     *
     * @param pass {@link TroubleshootReport} what the pass reported, or null before one has run
     * @param open a {@link List} of {@link Finding} what is still unresolved
     * @return {@link String} the line, or null before a pass has run
     */
    private @Nullable String summary(final @Nullable TroubleshootReport pass,
                                     final List<Finding> open) {
        if (pass == null) {
            return null;
        }
        final int repaired = (pass.indexRebuilt() ? 1 : 0) + pass.strayShardsRepaired().size();
        if (repaired == 0 && open.isEmpty()) {
            return "Nothing left to put right.";
        }
        final String put = repaired == 0
                ? "Nothing repaired"
                : RunWords.counted(repaired, "problem", "problems") + " repaired";
        return put + ", " + (open.isEmpty() ? "none" : String.valueOf(open.size())) + " left.";
    }

    /**
     * What to say under the rows where none of them is one the reader can answer.
     *
     * <p>Nothing where there is nothing left at all. The summary above already ends on that, and a
     * second sentence saying it again is the screen telling a reader twice.
     *
     * @param pass {@link TroubleshootReport} what the pass reported, or null before one has run
     * @param open a {@link List} of {@link Finding} what is still unresolved
     * @return {@link String} the line, or null where there is nothing to add
     */
    private @Nullable String nothingLeft(final @Nullable TroubleshootReport pass,
                                         final List<Finding> open) {
        if (pass == null || this.checking || open.isEmpty()) {
            return null;
        }
        if (open.stream().anyMatch(finding -> !FindingWords.of(finding).choices().isEmpty())) {
            return null;
        }
        return LaunchPrompt.sheetsToRedo(open).isEmpty()
                ? NOTHING_TO_ANSWER : NOTHING_TO_ANSWER_WITH_SHEETS_TO_REDO;
    }

    /**
     * What can be done to the run as a whole, in the order the buttons are drawn.
     *
     * <p>Finishing is offered only once the run has nothing left blocking it, which is the state
     * the reader came here to reach. Throwing it away works whatever state it is in.
     *
     * @return a {@link List} of {@link Action} the buttons
     */
    private List<Action> actions() {
        if (this.working()) {
            return List.of();
        }
        final List<Action> actions = new ArrayList<>();
        actions.add(new Action("troubleshoot-discard", "Discard", Deed.DISCARD, false,
                this.discardConfirm()));
        if (this.state == State.READY) {
            actions.add(new Action("troubleshoot-finish", FINISH, Deed.FINISH, true, null));
        }
        return actions;
    }

    /**
     * What a reader is asked before this run is thrown away.
     *
     * @return {@link Confirmation} what to ask
     */
    private Confirmation discardConfirm() {
        return new Confirmation("Discard the sift of " + this.scope + "?",
                "Discarding this sift's records will archive them. They will stay on disk in "
                        + this.pipeline.archivesFolder() + " for 30 days.",
                "Discard", "Keep", false);
    }

    /**
     * Draws the screen again.
     */
    private void draw() {
        final Runnable drawn = this.repaint;
        if (drawn != null) {
            drawn.run();
        }
    }

    /**
     * What one answer means for the finding it was given against.
     *
     * <p>Answers nothing where the two do not fit each other. That is a run which moved between
     * being drawn and being pressed, rather than a button that should not have been there.
     *
     * @param finding {@link Finding} the fault as the last reading reported it
     * @param answer {@link Answer} what the reader chose
     * @return {@link ChoiceAnswer} what to record, or null where the answer does not fit
     */
    private static @Nullable ChoiceAnswer answerFor(final Finding finding, final Answer answer) {
        return switch (answer) {
            case RECHECK -> null;
            case SKIP_FILE -> finding instanceof final Finding.MissingSource missing
                    ? new ChoiceAnswer.SkipMissingSource(missing.file()) : null;
            case TRUST_DECISION -> overlap(finding, OverlapResolution.TRUST_DECISION);
            case TREAT_AS_UNREVIEWABLE -> overlap(finding, OverlapResolution.TREAT_AS_UNREVIEWABLE);
            case SET_ASIDE_SHEET -> sidecar(finding, CorruptSidecarResolution.SET_ASIDE);
            case APPLY_SHEET_ANYWAY -> sidecar(finding, CorruptSidecarResolution.APPLY_ANYWAY);
            case SET_ASIDE_STRAY_ANSWERS -> finding instanceof final Finding.StrayShard stray
                    ? new ChoiceAnswer.SetAsideStrayShard(stray) : null;
        };
    }

    /**
     * An overlap answer, where the finding is one.
     *
     * @param finding {@link Finding} the fault
     * @param resolution {@link OverlapResolution} which way the reader settled it
     * @return {@link ChoiceAnswer} what to record, or null where the finding is not an overlap
     */
    private static @Nullable ChoiceAnswer overlap(final Finding finding,
                                                  final OverlapResolution resolution) {
        return finding instanceof Finding.DecisionUnreviewableOverlap(final Decision both)
                ? new ChoiceAnswer.ResolveOverlap(both.file(), resolution) : null;
    }

    /**
     * A sheet-record answer, where the finding is one.
     *
     * @param finding {@link Finding} the fault
     * @param resolution {@link CorruptSidecarResolution} which way the reader settled it
     * @return {@link ChoiceAnswer} what to record, or null where the finding is not one
     */
    private static @Nullable ChoiceAnswer sidecar(final Finding finding,
                                                  final CorruptSidecarResolution resolution) {
        return finding instanceof Finding.CorruptSidecar(final String montage)
                ? new ChoiceAnswer.ResolveCorruptSidecar(montage, resolution) : null;
    }
}
