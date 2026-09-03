package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;

import java.nio.file.Path;
import java.util.List;

/**
 * What the run launcher draws, chosen from a {@link RunSetupPresenter} and carrying only
 * display-ready values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param modes a {@link List} of {@link ModeChoice} the buttons across the top, in the order drawn
 * @param rowLink {@link RowLink} the step in that row that leads somewhere instead of starting a run
 * @param modeHint {@link String} what the mode now chosen does
 * @param inbox {@link InboxCard} what the Inbox card says right now
 * @param years a {@link List} of {@link YearChoice} the Sorted rows, newest first, empty when
 *     nothing is staged
 * @param undated {@link UndatedChoice} the row for what is sorted without a date, or null where
 *     nothing is
 * @param scopeNamesTheRun boolean whether the field and the Sorted rows decide what this run
 *     covers, false for a mode that takes the oldest year whatever they say
 * @param nothingStaged what to say in place of the rows when there are none, or null when there
 *     are some
 * @param scopeLabel {@link String} the label above the scope field
 * @param scopeText {@link String} what the scope field should read
 * @param scopeHint {@link String} what the field accepts for the mode now chosen
 * @param scopeRefusal what is wrong with what has been typed, or null while nothing is
 * @param cost {@link Cost} what the screen says about money, or null where this mode never reaches
 *     a vision provider
 * @param scopeLegend what the mark on a timeframe row means, or null where no row carries one
 * @param scopeLegendWayThere the word inside it that leads to that sift, or null with the legend
 * @param scopeLegendAfter what the legend says after that word, or null with the legend
 * @param startLabel {@link String} what the start button says
 * @param canStart boolean whether the start button is live
 * @param startAction {@link StartAction} what pressing it does
 * @param message {@link Message} what the screen has to report, or null where it has nothing
 */
public record RunLauncherView(List<ModeChoice> modes, RowLink rowLink, String modeHint, InboxCard inbox,
                              List<YearChoice> years, @Nullable UndatedChoice undated,
                              boolean scopeNamesTheRun,
                              @Nullable String nothingStaged,
                              String scopeLabel, String scopeText, String scopeHint,
                              @Nullable String scopeRefusal, @Nullable Cost cost,
                              @Nullable String scopeLegend,
                              @Nullable String scopeLegendWayThere,
                              @Nullable String scopeLegendAfter,
                              String startLabel, boolean canStart, StartAction startAction,
                              @Nullable Message message) {

    /**
     * Defensively copies the mutable collection components.
     *
     * @param modes a {@link List} of {@link ModeChoice} the buttons across the top
     * @param rowLink {@link RowLink} the step in that row that leads somewhere
     * @param modeHint {@link String} what the mode now chosen does
     * @param inbox {@link InboxCard} what the Inbox card says right now
     * @param years a {@link List} of {@link YearChoice} the Sorted rows, newest first
     * @param undated {@link UndatedChoice} the row for what is sorted without a date, or null
     * @param scopeNamesTheRun boolean whether the field and the rows decide what this run covers
     * @param nothingStaged what to say in place of the rows when there are none
     * @param scopeLabel {@link String} the label above the scope field
     * @param scopeText {@link String} what the scope field should read
     * @param scopeHint {@link String} what the field accepts for the mode now chosen
     * @param scopeRefusal what is wrong with what has been typed, or null
     * @param cost {@link Cost} what the screen says about money, or null
     * @param scopeLegend what the mark on a timeframe row means, or null
     * @param scopeLegendWayThere the word leading to that sift, or null
     * @param scopeLegendAfter what follows it, or null
     * @param startLabel {@link String} what the start button says
     * @param canStart boolean whether the start button is live
     * @param startAction {@link StartAction} what pressing it does
     * @param message {@link Message} what the screen has to report, or null
     */
    public RunLauncherView {
        modes = List.copyOf(modes);
        years = List.copyOf(years);
    }

    /**
     * What the button under the scope field does when it is pressed.
     *
     * <p>Sealed rather than a flag, because these are three different actions rather than three
     * dressings of one. What the button says comes from {@code startLabel} beside this, so a screen
     * never works out what to call any of them.
     *
     * <p>A timeframe already holding an unfinished sift cannot be sifted again.
     */
    public sealed interface StartAction {

        /** Starts the work the launcher is set up for. */
        record StartFresh() implements StartAction {
        }

        /**
         * Picks the unfinished sift of the chosen timeframe back up.
         *
         * @param prepDir {@link Path} that run's own directory
         */
        record ContinueRun(Path prepDir) implements StartAction {
        }

        /**
         * Goes to the runs screen, where the chosen timeframe's run can be dealt with.
         *
         * <p>Reached where that run is blocked or its records could not be read. Neither can be
         * carried on from here. One wants an answer first, and the other has said nothing about
         * itself.
         */
        record OpenRuns() implements StartAction {
        }
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
     * The step in the mode row that leads somewhere rather than starting something.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what it says
     * @param after {@link RunMode} the mode it is drawn after
     */
    public record RowLink(String id, String label, RunMode after) {
    }

    /**
     * The Inbox card, which says how much is waiting and nothing about which years it covers.
     *
     * <p>Deliberately opaque. A file's year is not known until a sort resolves its date. A card
     * breaking the Inbox down by year would be inventing the one thing it cannot know.
     *
     * @param headline {@link String} the count and size, or what the card is doing or could not do
     * @param detail a second line, or null where the headline says it all
     * @param importLabel {@link String}
     * @param importHint {@link String} the other way in, which nothing on screen would otherwise show
     * @param canImport boolean whether that button can be pressed right now
     */
    public record InboxCard(String headline, @Nullable String detail, String importLabel,
                            String importHint, boolean canImport) {
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
                             boolean monthsShown, boolean unfinishedSift, List<MonthChoice> months) {

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
     * What is sorted without a date, as a row that scopes a run to it.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what the row says
     * @param counts {@link String} what it holds, written out
     * @param chosen boolean whether this row is the one now selected
     * @param pressable boolean whether pressing it scopes the run
     */
    public record UndatedChoice(String id, String label, String counts, boolean chosen,
                                boolean pressable) {
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
    public record MonthChoice(int month, String id, String label, String counts, boolean chosen,
                              boolean unfinishedSift) {
    }

    /**
     * What the screen says about money for the mode now chosen.
     *
     * <p>Sealed, and null where the screen says nothing at all. Silence has to mean one thing, and
     * on this screen it means the mode does not reach a vision provider. A provider that is reached
     * and spends nothing says so. Left silent, its empty space reads as a figure that could not be
     * worked out.
     */
    public sealed interface Cost {

        /**
         * What a sift over the chosen scope is expected to cost, with what the figure is worth.
         *
         * <p>The figure is never presented on its own. One paragraph carries what it is, what it
         * rests on, and what stops a run that outgrows it.
         *
         * @param figure {@link String} the expected cost, in the terms the app measures it
         * @param disclaimer {@link String} what the figure is, what it rests on, and what stops a
         *     run that outgrows it
         * @param warning what is holding the figure back and the way out of it, or null where
         *     nothing is
         */
        record Estimate(String figure, String disclaimer,
                        @Nullable Warning warning) implements Cost {
        }

        /**
         * Something wrong with what the figure rests on, and the way out of it.
         *
         * <p>Apart from the disclaimer because it is a different kind of statement. The disclaimer
         * is true of every estimate this screen draws. This is a state the reader can leave.
         *
         * @param problem {@link String} what is wrong and what it costs the figure
         * @param repair {@link Repair} the way out
         */
        record Warning(String problem, Repair repair) {
        }

        /**
         * The way out of a record of past spend that nothing can read.
         *
         * <p>The problem beside it says the figure will keep guessing until that record is
         * replaced. Nothing else in the app makes that true, so without this the sentence names
         * something the reader cannot reach.
         *
         * @param id {@link String} the button's id, for the screen to set on it
         * @param label {@link String} what the button says
         * @param confirm {@link Confirmation} what to ask before it goes ahead
         */
        record Repair(String id, String label, Confirmation confirm) {
        }

        /**
         * The configured provider spends nothing, so there is no figure to show.
         *
         * <p>Drawn for every mode that reaches a vision provider, whether or not a scope has been
         * chosen. What it reports is the provider rather than the scope, and a reader switching
         * provider to find out what it costs is asking about exactly that.
         *
         * @param headline {@link String} what the box says first
         * @param detail {@link String} why there is no figure
         */
        record Free(String headline, String detail) implements Cost {
        }
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
