package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;

import java.nio.file.Path;
import java.util.List;

/**
 * What the runs screen draws, chosen from a {@link RunsPresenter} and carrying only display-ready
 * values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param heading {@link String} the screen's own name
 * @param unreadable what to say in place of everything else where the runs folder could not be
 *     read, or null where it could
 * @param unfinished a {@link List} of {@link RunCard} the runs still owing somebody something, most
 *     in need of attention first
 * @param nothingYet what to say in place of the cards where there are none at all, or null where
 *     there are some
 * @param completedHeading {@link String} the folded section's own label, carrying its count
 * @param completed a {@link List} of {@link RunCard} the runs that finished, empty when none have
 * @param completedShown boolean whether that section is folded open
 * @param clearCompleted {@link String} what the button clearing them says
 * @param canClearCompleted boolean whether that button is live
 * @param message {@link Message} what the screen has to report, or null where it has nothing
 */
public record RunsView(String heading, @Nullable String unreadable, List<RunCard> unfinished,
                       @Nullable String nothingYet, String completedHeading,
                       List<RunCard> completed, boolean completedShown,
                       String clearCompleted, boolean canClearCompleted,
                       @Nullable Message message) {

    /**
     * Defensively copies the mutable collection components.
     *
     * @param heading {@link String} the screen's own name
     * @param unreadable what to say where the runs folder could not be read
     * @param unfinished a {@link List} of {@link RunCard} the runs still owing somebody something
     * @param nothingYet what to say where there are no runs at all
     * @param completedHeading {@link String} the folded section's own label
     * @param completed a {@link List} of {@link RunCard} the runs that finished
     * @param completedShown boolean whether that section is folded open
     * @param clearCompleted {@link String} what the button clearing them says
     * @param canClearCompleted boolean whether that button is live
     * @param message {@link Message} what the screen has to report
     */
    public RunsView {
        unfinished = List.copyOf(unfinished);
        completed = List.copyOf(completed);
    }

    /**
     * One run on disk, as the card that stands for it.
     *
     * @param id {@link String} the card's id, for the screen to set on it
     * @param scope {@link String} what the run covers, as a reader would say it
     * @param headline {@link String} what state it is in, in plain words
     * @param detail a second line, or null where the headline says it all
     * @param sheets how far through its sheets it got, or null where nothing counted them
     * @param age {@link String} when it was last written to, as a reader would say it
     * @param waiting what the card carries only while sheets are still owed, or null on a run past
     *     that point
     * @param actions a {@link List} of {@link Action} what can be done about it, in the order drawn
     */
    public record RunCard(String id, String scope, String headline, @Nullable String detail,
                          @Nullable String sheets, String age, @Nullable Waiting waiting,
                          List<Action> actions) {

        /**
         * Defensively copies the mutable list.
         *
         * @param id {@link String} the card's id
         * @param scope {@link String} what the run covers
         * @param headline {@link String} what state it is in
         * @param detail a second line, or null
         * @param sheets how far through its sheets it got, or null
         * @param age {@link String} when it was last written to
         * @param waiting what the card carries while sheets are still owed, or null
         * @param actions a {@link List} of {@link Action} what can be done about it
         */
        public RunCard {
            actions = List.copyOf(actions);
        }
    }

    /**
     * What a card carries only while its run is still short of judged sheets.
     *
     * <p>A state that has none of this cannot be handed half of it, which is what keeps these
     * together rather than loose on a card.
     *
     * @param folder {@link Path} where the sheets are, shown so a reader can go and look
     * @param copyFolder {@link String} what the button copying that path says
     * @param copyPrompt what the button copying the instructions says, or null where nobody outside
     *     the app is doing the judging
     * @param autoApply the control deciding whether answers are acted on as they arrive, or null
     *     where nothing arrives from outside
     * @param waiveMissing whether going on means going on without the sheets still owed, or null
     *     where going on would judge them rather than skip them
     * @param note {@link String} what happens next, in the words that state fits
     */
    public record Waiting(Path folder, String copyFolder, @Nullable String copyPrompt,
                          @Nullable Switch autoApply, @Nullable Switch waiveMissing, String note) {
    }

    /**
     * One control a reader turns on and off.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what it says
     * @param on boolean where it currently sits
     */
    public record Switch(String id, String label, boolean on) {
    }

    /**
     * One thing that can be done to a run.
     *
     * <p>Carries the prep dir rather than leaving the screen to look it up. That is an identity
     * handed straight back to {@link RunsPresenter#press}, never something for the screen to read.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what the button says
     * @param kind {@link Kind} what pressing it does
     * @param leading boolean whether this is the way on from the card, drawn to be reached for
     * @param prepDir {@link Path} the run it does it to
     * @param scope {@link String} what that run covers, in the words this card used. Carried so a
     *     press landing on another screen can name the run the way the reader just saw it named
     * @param confirm {@link Confirmation} what to ask first, or null where the action needs no
     *     asking
     */
    public record Action(String id, String label, Kind kind, boolean leading, Path prepDir,
                         String scope, @Nullable Confirmation confirm) {
    }

    /**
     * What an action does, which is what {@link RunsPresenter#press} switches on.
     *
     * <p>What it looks like is {@link Action#leading} instead. The two agree today and are still
     * separate questions: one is what a press runs, the other is which button a reader reaches for.
     */
    public enum Kind {

        /** Picks the run back up from where it stopped. */
        CONTINUE,

        /** Gives up on the run, archiving its records. */
        DISCARD
    }
}
