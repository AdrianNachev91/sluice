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
import photos.sluice.adapter.ui.TroubleshootView.ProblemStack;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.ChoiceAnswer;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.LaunchPrompt;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.cull.Verdict;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

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

    // Held as well as reported through the notice below, which is transient. Left to that alone,
    // the screen ends up carrying no sign that the check never happened.
    private volatile @Nullable String checkRefusedReason;

    // What the last reading found, held as one value rather than three fields. Read apart, a caller
    // can take one reading's findings and another reading's refusal. The screen then puts a count
    // on a run the second reading could not see, and offers Finish over it.
    //
    // Atomic because every press runs on its own virtual thread, and the pass reports its ending
    // from whichever thread it ran on.
    private final AtomicReference<Reading> reading = new AtomicReference<>(Reading.NOTHING_YET);

    private volatile @Nullable Runnable repaint;
    private volatile @Nullable Runnable openRuns;
    private volatile @Nullable Runnable openDashboard;
    private volatile boolean detailCopied;
    private volatile boolean discarding;

    // What the screen has to report, and which report it is. Held as one value rather than two
    // fields. Read apart, a caller can take one report's words and the next report's number. The
    // screen then files the old sentence under the new number, and the real one is suppressed for
    // good as a redraw it has already seen.
    private final AtomicReference<Notice> notice = new AtomicReference<>(new Notice(null, 0));

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
        this.announce(null);
        this.detailCopied = false;
        this.checkRefusedReason = null;
        this.reading.set(Reading.NOTHING_YET);
        this.checking = true;
        this.startTheCheck(prepDir);
    }

    /**
     * What the screen shows right now.
     *
     * @return {@link TroubleshootView} every row and every button, in the order they are drawn
     */
    public TroubleshootView view() {
        final Reading last = this.reading.get();
        final TroubleshootReport pass = this.report;
        final Notice reported = this.notice.get();
        // Both asked once for the whole draw. A job starting or ending partway through would
        // otherwise put answers on some rows and take the buttons off the rest.
        final boolean stillChecking = this.checking;
        final boolean busy = stillChecking || this.discarding || this.pipeline.isBusy();
        return new TroubleshootView("Troubleshoot " + this.scope, BACK,
                stillChecking ? CHECKING : null, this.summary(pass, last),
                this.groupedProblems(last.findings(), busy),
                nothingLeft(pass, last.findings(), stillChecking),
                pass == null ? null : new Detail(DETAIL, pass.text(), COPY_DETAIL, COPIED),
                this.actions(last.state(), busy), reported.message(), reported.number());
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
        this.announce(null);
        final Finding finding = problem.finding();
        // Found by identity rather than by the index the row was drawn at. Answering one finding
        // resolves it, so every later index shifts. A row the reader can still see would otherwise
        // be answered against whatever slid into its place.
        if (!this.reading.get().findings().contains(finding)) {
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
        this.answer(run, option.answer(), answer);
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
            final RunRefusals.Refusal refused = RunRefusals.said(e);
            this.checking = false;
            this.checkRefusedReason = refused.sentence();
            this.announce(RunRefusals.refuseMessage(e));
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
        if (failure == null) {
            this.checkRefusedReason = null;
        } else {
            log.warn("Could not look through {}", run, failure);
            final Throwable root = RunRefusals.rootOf(failure);
            this.checkRefusedReason = RunRefusals.refuseSentence(root);
            this.announce(RunRefusals.refuseMessage(root));
        }
        this.report = pass;
        this.checking = false;
        this.reread(run);
        this.draw();
    }

    /**
     * Records one answer, then reads the run again.
     *
     * <p>{@link Answer#RECHECK} never reaches here.
     *
     * @param run {@link Path} the run being answered
     * @param chosen {@link Answer} what the reader chose
     * @param answer {@link ChoiceAnswer} what that means for this finding
     */
    private void answer(final Path run, final Answer chosen, final ChoiceAnswer answer) {
        try {
            this.pipeline.answer(run, answer, AnswerSource.DESKTOP);
        } catch (final RuntimeException e) {
            log.info("Could not answer {} on {}", chosen, run, e);
            this.announce(RunRefusals.refuseMessage(e));
            return;
        }
        this.announce(new Message(requireNonNull(FindingWords.settled(chosen)), false));
        this.reread(run);
    }

    /**
     * Records what the screen has to report, or that it has nothing.
     *
     * @param message {@link Message} what to report, or null to leave the screen saying nothing
     */
    private void announce(final @Nullable Message message) {
        this.notice.updateAndGet(last -> new Notice(message, last.number() + 1));
    }

    /**
     * One thing the screen was given to report, and where it sits in the sequence of them.
     *
     * @param message {@link Message} what to report, or null to leave the screen saying nothing
     * @param number int which report this is, rising by one each time and never reset
     */
    private record Notice(@Nullable Message message, int number) {
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
        final Reading landed = this.reread(run);
        if (landed == null) {
            return;
        }
        // An answer that ran and left the problem standing is what the reader has to notice. Said
        // in the tone of a confirmation, it reads as the press having worked.
        final boolean stillThere = landed.findings().contains(finding);
        this.announce(new Message(stillThere ? LOOKED_AGAIN_STILL_THERE : LOOKED_AGAIN_GONE,
                stillThere));
    }

    /**
     * Reads the run again after it turned out to have moved under the screen.
     *
     * @param run {@link Path} the run
     */
    private void overtaken(final Path run) {
        if (this.reread(run) != null) {
            this.announce(new Message(RUN_MOVED, false));
        }
    }

    /**
     * Reads one run's own records again.
     *
     * <p>A read that failed empties the findings and reports why. A caller with something to say
     * about what the run now holds then has nothing to say it from. Speaking anyway puts a
     * sentence about the photos over a refusal about the folder.
     *
     * @param run {@link Path} the run
     * @return {@link Reading} what this reading found, or null where it failed. Answered rather
     *     than left to be picked up off the field. A caller acts on its own reading, never on
     *     whichever one has landed by the time it looks
     */
    private @Nullable Reading reread(final Path run) {
        try {
            final PrepDirHealth health = this.pipeline.cullRun(run).health();
            final Reading landed = new Reading(health.findings(), health.state(), null);
            this.reading.set(landed);
            return landed;
        } catch (final RuntimeException e) {
            log.info("Could not read {}", run, e);
            // Moved off READY with the findings it was read from, or Finish stays on offer over a
            // run this cannot see.
            this.reading.set(new Reading(List.of(), State.DAMAGED, RunRefusals.refuseSentence(e)));
            this.announce(RunRefusals.refuseMessage(e));
            return null;
        }
    }

    /**
     * What one reading of the run found.
     *
     * @param findings a {@link List} of {@link Finding} what is still unresolved, in reading order
     * @param state {@link State} the run's own state as this reading found it
     * @param nothingKnownReason {@link String} why this reading found nothing, or null where it
     *     found nothing because there is nothing. An empty findings list means both, and the counts
     *     drawn from it are only true of the second. The refusal's own words rather than a sentence
     *     of this screen's, so what the reader is told cannot drift from what actually refused
     */
    private record Reading(List<Finding> findings, State state,
                           @Nullable String nothingKnownReason) {

        private static final Reading NOTHING_YET = new Reading(List.of(), State.BLOCKED, null);
    }

    /**
     * Carries the run on, and takes the reader to where that job reports itself.
     *
     * @param run {@link Path} the run
     */
    private void finish(final Path run) {
        this.announce(null);
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
        this.announce(null);
        final JobHandle<DiscardReport> handle;
        try {
            handle = this.pipeline.discard(run);
        } catch (final RuntimeException e) {
            log.info("Refused to discard {}", run, e);
            this.announce(RunRefusals.refuseMessage(e));
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
            this.announce(RunRefusals.refuseMessage(RunRefusals.rootOf(failure)));
            this.draw();
            return;
        }
        this.back();
    }

    /**
     * The open findings as the screen draws them, with faults of one kind under one heading.
     *
     * <p>Kept in the order the reading found them, keyed on the first of each kind. A pass that
     * puts one fault right and leaves another alone must not reshuffle what is left. A reader
     * would otherwise come back to a page they have to read again from the top.
     *
     * <p>Rows keep their own answers whatever the heading above them says. An answer is about one
     * photo or one sheet, and one press settling several of them would do something other than
     * what the button reads.
     *
     * @param open a {@link List} of {@link Finding} what is still unresolved, in reading order
     * @param busy boolean whether something is running that these rows have to wait for
     * @return a {@link List} of {@link ProblemStack} the headings and their rows, in drawing order
     */
    private List<ProblemStack> groupedProblems(final List<Finding> open, final boolean busy) {
        final Map<Class<? extends Finding>, List<Integer>> byKind = new LinkedHashMap<>();
        for (int i = 0; i < open.size(); i++) {
            byKind.computeIfAbsent(open.get(i).getClass(), _ -> new ArrayList<>()).add(i);
        }
        final List<ProblemStack> grouped = new ArrayList<>();
        for (final List<Integer> places : byKind.values()) {
            final String heading = places.size() == 1
                    ? null
                    : FindingWords.of(open.get(places.getFirst())).heading(places.size());
            grouped.add(new ProblemStack(heading, places.stream()
                    .map(i -> problem(i, open.get(i), heading != null, busy))
                    .toList()));
        }
        return grouped;
    }

    /**
     * One still-open problem's row.
     *
     * @param i int where the finding sits in the reading this row was drawn from
     * @param finding {@link Finding} the fault
     * @param headed boolean whether a heading above this row already says what went wrong
     * @param busy boolean whether something is running that this row has to wait for
     * @return {@link Problem} the row
     */
    private static Problem problem(final int i, final Finding finding, final boolean headed,
                                   final boolean busy) {
        final FindingWords.Statement statement = FindingWords.of(finding);
        final List<Option> options = new ArrayList<>();
        for (final FindingWords.Choice choice : statement.choices()) {
            options.add(new Option("troubleshoot-answer-" + i + "-" + choice.answer(),
                    choice.label(), choice.answer(), choice.leading() && !busy,
                    choice.confirm()));
        }
        return new Problem("troubleshoot-problem-" + i, finding, headed ? null : statement.problem(),
                statement.about(), busy ? List.of() : options);
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
     * @param last {@link Reading} the reading this screen is being drawn from
     * @return {@link String} the line, or null while a pass is still running
     */
    private @Nullable String summary(final @Nullable TroubleshootReport pass, final Reading last) {
        // Ahead of the pass's own refusal below, so two failures at once read as one problem. The
        // notice carries this same sentence, and the two would otherwise name different causes.
        final String refused = last.nothingKnownReason();
        if (refused != null) {
            return refused;
        }
        // Rows can still be under this one, read by the reading that followed the pass. That is
        // what separates it from the arm above, which speaks only where nothing was read at all.
        final String checkRefused = this.checkRefusedReason;
        if (checkRefused != null) {
            return checkRefused;
        }
        if (pass == null) {
            return null;
        }
        final List<Finding> open = last.findings();
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
     * @param stillChecking boolean whether the pass was still running when this draw began
     * @return {@link String} the line, or null where there is nothing to add
     */
    private static @Nullable String nothingLeft(final @Nullable TroubleshootReport pass,
                                                final List<Finding> open,
                                                final boolean stillChecking) {
        if (pass == null || stillChecking || open.isEmpty()) {
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
     * @param state {@link State} the run's state as the reading being drawn found it
     * @param busy boolean whether something is running that these buttons have to wait for
     * @return a {@link List} of {@link Action} the buttons
     */
    private List<Action> actions(final State state, final boolean busy) {
        if (busy) {
            return List.of();
        }
        final List<Action> actions = new ArrayList<>();
        actions.add(new Action("troubleshoot-discard", "Discard", Deed.DISCARD, false,
                this.discardConfirm()));
        if (state == State.READY) {
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
            case TRUST_DECISION -> overlapAnswer(finding, OverlapResolution.TRUST_DECISION);
            case TREAT_AS_UNREVIEWABLE -> overlapAnswer(finding, OverlapResolution.TREAT_AS_UNREVIEWABLE);
            case SET_ASIDE_SHEET -> sidecarAnswer(finding, CorruptSidecarResolution.SET_ASIDE);
            case APPLY_SHEET_ANYWAY -> sidecarAnswer(finding, CorruptSidecarResolution.APPLY_ANYWAY);
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
    private static @Nullable ChoiceAnswer overlapAnswer(final Finding finding,
                                                        final OverlapResolution resolution) {
        return finding instanceof Finding.VerdictUnreviewableOverlap(final Verdict both)
                ? new ChoiceAnswer.ResolveOverlap(both.file(), resolution) : null;
    }

    /**
     * A sheet-record answer, where the finding is one.
     *
     * @param finding {@link Finding} the fault
     * @param resolution {@link CorruptSidecarResolution} which way the reader settled it
     * @return {@link ChoiceAnswer} what to record, or null where the finding is not one
     */
    private static @Nullable ChoiceAnswer sidecarAnswer(final Finding finding,
                                                        final CorruptSidecarResolution resolution) {
        return finding instanceof Finding.CorruptSidecar(final String montage)
                ? new ChoiceAnswer.ResolveCorruptSidecar(montage, resolution) : null;
    }
}
