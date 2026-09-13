package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.domain.sift.Finding;

import java.util.List;

/**
 * What the troubleshoot screen draws, chosen from a {@link TroubleshootPresenter}. The view reads
 * fields off this and decides nothing about what they mean.
 *
 * @param heading {@link String} the screen's own name, carrying which run it is about
 * @param back {@link String} what the way out says
 * @param checking what to say while the pass is running, or null where it is not
 * @param summary {@link String} what the pass found and what it put right, or null before one has
 *     run
 * @param problems a {@link List} of {@link ProblemStack} what is still unresolved, in the order
 *     drawn, with faults of one kind gathered under one heading
 * @param nothingLeft what to say under rows none of which can be answered. Null where an answer is
 *     on offer, and null again where there is nothing left at all
 * @param detail the technical report, or null before a pass has produced one
 * @param actions a {@link List} of {@link Action} what can be done about the run as a whole
 * @param message {@link Message} what the screen has to report, or null where it has nothing
 * @param reportNumber int how many times this screen has reported anything, so a screen can tell a
 *     fresh report from a redraw carrying the last one. Two answers of one kind say the same
 *     sentence, and the second is still news
 */
public record TroubleshootView(String heading, String back, @Nullable String checking,
                               @Nullable String summary, List<ProblemStack> problems,
                               @Nullable String nothingLeft, @Nullable Detail detail,
                               List<Action> actions, @Nullable Message message, int reportNumber) {

    /**
     * Defensively copies the mutable collection components.
     *
     * @param heading {@link String} the screen's own name
     * @param back {@link String} what the way out says
     * @param checking what to say while the pass is running
     * @param summary {@link String} what the pass found and put right
     * @param problems a {@link List} of {@link ProblemStack} what is still unresolved
     * @param nothingLeft what to say under rows none of which can be answered
     * @param detail the technical report
     * @param actions a {@link List} of {@link Action} what can be done about the run
     * @param message {@link Message} what the screen has to report
     * @param reportNumber int how many times this screen has reported anything
     */
    public TroubleshootView {
        problems = List.copyOf(problems);
        actions = List.copyOf(actions);
    }

    /**
     * Several rows about one and the same fault, under the heading that covers them.
     *
     * @param heading what covers the rows and counts them, or null where there is only one row and
     *     it says what went wrong itself
     * @param rows a {@link List} of {@link Problem} one per fault, each naming what it happened to
     *     and carrying its own answers
     */
    public record ProblemStack(@Nullable String heading, List<Problem> rows) {

        /**
         * Defensively copies the mutable list.
         *
         * @param heading what covers the rows, or null
         * @param rows a {@link List} of {@link Problem} the rows it covers
         */
        public ProblemStack {
            rows = List.copyOf(rows);
        }
    }

    /**
     * One thing still standing between the run and finishing, as the reader meets it.
     *
     * @param id {@link String} the row's id, for the screen to set on it
     * @param finding {@link Finding} the fault this row was drawn from, handed straight back to
     *     {@link TroubleshootPresenter#press}. Never something for the screen to read
     * @param problem what went wrong, in the terms a reader would use, or null where the heading
     *     above this row says it for several rows at once
     * @param subject what it happened to, such as the photo or the sheet, or null where the problem
     *     names nothing narrower than the run
     * @param options a {@link List} of {@link Option} the answers on offer, empty while a job holds
     *     the run
     */
    public record Problem(String id, Finding finding, @Nullable String problem,
                          @Nullable String subject, List<Option> options) {

        /**
         * Defensively copies the mutable list.
         *
         * @param id {@link String} the row's id
         * @param finding {@link Finding} the fault this row was drawn from
         * @param problem what went wrong, or null where a heading says it
         * @param subject what it happened to, or null
         * @param options a {@link List} of {@link Option} the answers on offer
         */
        public Problem {
            options = List.copyOf(options);
        }
    }

    /**
     * One answer a reader can give to a problem.
     *
     * @param id {@link String} the button's id, for the screen to set on it
     * @param label {@link String} what the button says
     * @param answer {@link Answer} which answer it gives, handed straight back to
     *     {@link TroubleshootPresenter#press}
     * @param leading boolean whether this is the answer the row is drawn to be reached for
     * @param confirm {@link Confirmation} what to ask before it goes ahead, or null where nothing
     *     needs asking
     */
    public record Option(String id, String label, Answer answer, boolean leading,
                         @Nullable Confirmation confirm) {
    }

    /**
     * The technical report, for a reader handing this on to somebody who can read it.
     *
     * @param label {@link String} what the fold says
     * @param text {@link String} the report itself
     * @param copy {@link String} what the button copying it says
     * @param copied {@link String} what that button says once it has copied
     */
    public record Detail(String label, String text, String copy, String copied) {
    }

    /**
     * One thing that can be done to the run as a whole.
     *
     * @param id {@link String} the button's id, for the screen to set on it
     * @param label {@link String} what the button says
     * @param deed {@link Kind} what pressing it does
     * @param leading boolean whether this is the way on, drawn to be reached for
     * @param confirm {@link Confirmation} what to ask first, or null where it needs no asking
     */
    public record Action(String id, String label, Kind deed, boolean leading,
                         @Nullable Confirmation confirm) {
    }

    /**
     * What an action does, which is what {@link TroubleshootPresenter#press(Action)} switches on.
     */
    public enum Kind {

        /** Picks the run back up now that nothing blocks it. */
        FINISH,

        /** Gives up on the run, archiving its records. */
        DISCARD
    }

    /**
     * What answering a problem settles.
     *
     * <p>Named for what the reader chose rather than for the engine call it becomes. The screen
     * hands back a choice, and what that choice means for the finding it was offered against is
     * decided elsewhere.
     */
    public enum Answer {

        /** The reader says the missing photo is back, so the run is looked at again. */
        RECHECK,

        /** The run goes on without that photo. */
        SKIP_FILE,

        /** The sheet's verdict on the photo stands. */
        TRUST_DECISION,

        /** The photo stays as one nobody judged. */
        TREAT_AS_UNREVIEWABLE,

        /** The sheet's answers are abandoned, for a later sift to ask again. */
        SET_ASIDE_SHEET,

        /** The sheet's answers are taken as they are, unchecked against what it showed. */
        APPLY_SHEET_ANYWAY,

        /** The answers belonging to no sheet are filed away. */
        SET_ASIDE_STRAY_ANSWERS
    }
}
