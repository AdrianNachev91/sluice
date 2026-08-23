package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the run launcher draws, chosen from a {@link RunLauncherPresenter} and carrying only
 * display-ready values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param modes a {@link List} of {@link ModeChoice} the buttons across the top, in the order drawn
 * @param modeHint {@link String} what the mode now chosen does
 * @param inbox {@link InboxCard} what the Inbox card says right now
 * @param years a {@link List} of {@link YearChoice} the Sorted rows, newest first, empty when
 *     nothing is staged
 * @param scopeNamesTheRun boolean whether the field and the Sorted rows decide what this run
 *     covers, false for a mode that takes the oldest year whatever they say
 * @param nothingStaged what to say in place of the rows when there are none, or null when there
 *     are some
 * @param scopeLabel {@link String} the label above the scope field
 * @param scopeText {@link String} what the scope field should read
 * @param scopeHint {@link String} what the field accepts for the mode now chosen
 * @param scopeRefusal what is wrong with what has been typed, or null while nothing is
 * @param estimate {@link Estimate} what a sift over the chosen scope would cost, or null where
 *     this mode and provider spend nothing
 * @param startLabel {@link String} what the start button says
 * @param canStart boolean whether the start button is live
 * @param message {@link Message} what the screen has to report, or null where it has nothing
 */
public record RunLauncherView(List<ModeChoice> modes, String modeHint, InboxCard inbox,
                              List<YearChoice> years, boolean scopeNamesTheRun,
                              @Nullable String nothingStaged,
                              String scopeLabel, String scopeText, String scopeHint,
                              @Nullable String scopeRefusal, @Nullable Estimate estimate,
                              String startLabel, boolean canStart, @Nullable Message message) {

    /**
     * Defensively copies the mutable collection components.
     *
     * @param modes a {@link List} of {@link ModeChoice} the buttons across the top
     * @param modeHint {@link String} what the mode now chosen does
     * @param inbox {@link InboxCard} what the Inbox card says right now
     * @param years a {@link List} of {@link YearChoice} the Sorted rows, newest first
     * @param scopeNamesTheRun boolean whether the field and the rows decide what this run covers
     * @param nothingStaged what to say in place of the rows when there are none
     * @param scopeLabel {@link String} the label above the scope field
     * @param scopeText {@link String} what the scope field should read
     * @param scopeHint {@link String} what the field accepts for the mode now chosen
     * @param scopeRefusal what is wrong with what has been typed, or null
     * @param estimate {@link Estimate} what a sift over the chosen scope would cost, or null
     * @param startLabel {@link String} what the start button says
     * @param canStart boolean whether the start button is live
     * @param message {@link Message} what the screen has to report, or null
     */
    public RunLauncherView {
        modes = List.copyOf(modes);
        years = List.copyOf(years);
    }

    /**
     * One of the buttons across the top of the launcher.
     *
     * <p>A mode with nothing behind it yet is still pressable. What such a mode can and cannot do
     * is said by the hint under the scope field and by whether the start button is live. A button
     * drawn dead would say the same thing less clearly, and would leave the row looking different
     * from the row this screen ends up with.
     *
     * <p>What does draw the whole row dead is a job in flight. The line reporting how that job
     * ended names the work it was started as. A row that could still move would let a user change
     * the answer to a question already being answered.
     *
     * @param mode {@link RunMode} which kind of work it starts, handed back when it is pressed
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what it says
     * @param chosen boolean whether this is the mode now selected
     * @param pressable boolean whether it can be pressed at all right now
     */
    public record ModeChoice(RunMode mode, String id, String label, boolean chosen, boolean pressable) {
    }

    /**
     * The Inbox card, which says how much is waiting and nothing about which years it covers.
     *
     * <p>Deliberately opaque. A file's year is not known until a sort resolves its date. A card
     * breaking the Inbox down by year would be inventing the one thing it cannot know.
     *
     * @param headline {@link String} the count and size, or what the card is doing or could not do
     * @param detail a second line, or null where the headline says it all
     */
    public record InboxCard(String headline, @Nullable String detail) {
    }

    /**
     * One year staged in Sorted, as a row that scopes a run when clicked.
     *
     * <p>{@code year} is an identity handed back when the row is chosen, never something for the
     * screen to render. What the row says is {@code label}, already written out, and what it is
     * found by is {@code id}. A screen composing that id from the year would be spelling a naming
     * rule, in the one place meant to decide nothing.
     *
     * @param year int which year the row stands for
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} the year as the row says it
     * @param counts {@link String} what the row holds, written out
     * @param chosen boolean whether this row is the one now selected
     * @param monthsShown boolean whether its months are on screen
     * @param months a {@link List} of {@link MonthChoice} its months, always listed
     */
    public record YearChoice(int year, String id, String label, String counts, boolean chosen,
                             boolean monthsShown, List<MonthChoice> months) {

        /**
         * Defensively copies the mutable list.
         *
         * @param year int the year
         * @param id {@link String} the control's id
         * @param label {@link String} what the row says
         * @param counts {@link String} what the year holds
         * @param chosen boolean whether the scope names this year
         * @param monthsShown boolean whether its months are on screen
         * @param months a {@link List} of {@link MonthChoice} its months, always listed
         */
        public YearChoice {
            months = List.copyOf(months);
        }
    }

    /**
     * One month of a chosen year, as a row that narrows the scope to it.
     *
     * <p>Shown only under the year now chosen. Choosing a year is already the gesture that writes it
     * into the field, so revealing its months asks for no second one. Nothing is expanded at rest,
     * because no year is chosen until somebody picks one.
     *
     * @param month int the month, 1 to 12
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} the month's name
     * @param counts {@link String} what it holds, written out
     * @param chosen boolean whether the scope names this month
     */
    public record MonthChoice(int month, String id, String label, String counts, boolean chosen) {
    }

    /**
     * What a sift over the chosen scope is expected to cost, with what the figure is worth.
     *
     * <p>The figure is never presented on its own. It is an average of past runs rather than a
     * quote, and a run costs more when a sheet needs a second attempt.
     *
     * @param figure {@link String} the expected cost, in the terms the app measures it
     * @param disclaimer {@link String} what the figure is and is not
     * @param withoutHistory what to add where this install has no finished runs behind the
     *     figure, or null where it has some
     */
    public record Estimate(String figure, String disclaimer, @Nullable String withoutHistory) {
    }

    /**
     * Something the screen has to report about what just happened.
     *
     * @param text {@link String} what to say
     * @param refused boolean whether this is a refusal rather than an ordinary report
     */
    public record Message(String text, boolean refused) {
    }
}
