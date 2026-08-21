package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the Photo categories screen draws, chosen from a {@link PhotoCategoriesPresenter} and carrying only
 * display-ready values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param categories a {@link List} of {@link CategoryRow} one row per configured card, in order
 * @param disposition {@link String} what happens to the photos a card catches, said once for the page
 * @param nameRule {@link String} the help line under a name field, stating what a name may be
 * @param limits {@link Limits} how much each field may hold
 */
public record PhotoCategoriesView(List<CategoryRow> categories, String disposition, String nameRule,
                               Limits limits) {

    /**
     * How much text each of a card's fields accepts, decided where the rule is rather than by
     * whichever control happens to draw it. The same reason {@code SettingsView.NumberRange}
     * exists: a view binds to a limit, it does not hold one.
     *
     * @param name int the most a name may hold
     * @param description int the most a description may hold
     * @param examples int the most the whole examples box may hold
     */
    public record Limits(int name, int description, int examples) {
    }

    /**
     * One category card as the screen shows it.
     *
     * <p>{@code fixed} carries the whole difference between the built-in card and an ordinary one.
     * Non-null means the name cannot be edited and the card cannot be deleted, and the string says
     * why. One field rather than a flag beside a sentence, so a card cannot be drawn as fixed
     * without saying why, or explained without being fixed.
     *
     * @param name {@link String} the card's name, which also names the folder its photos go to
     * @param description {@link String} the "what belongs here" prose
     * @param examples a {@link List} of {@link String} sample subjects, empty when it offers none
     * @param enabled boolean whether a cull routes to this card
     * @param fixed why this card cannot be renamed or deleted, or null for an ordinary card
     */
    public record CategoryRow(String name, String description, List<String> examples, boolean enabled,
                              @Nullable String fixed) {
    }

    /**
     * One card as the screen hands it back to be saved. Separate from {@link CategoryRow} because a
     * row carries what to draw and this carries what was typed. An edit has no {@code fixed} note:
     * a card the screen drew as fixed comes back with the name it was drawn with.
     *
     * @param name {@link String} the name field's text
     * @param description {@link String} the description box's text
     * @param examples a {@link List} of {@link String} the examples box's lines
     * @param enabled boolean where the card's own switch was left
     */
    public record CategoryEdit(String name, String description, List<String> examples, boolean enabled) {
    }

    /**
     * What a save did.
     */
    public sealed interface SaveOutcome {

        /**
         * The cards were written and are in force.
         */
        record Saved() implements SaveOutcome {
        }

        /**
         * Nothing was written, and what needs fixing is said per card.
         *
         * <p>{@code cards} runs parallel to the list handed to the save: one entry per submitted
         * card, in that order. So a screen marks a row by position rather than by name. A name is
         * exactly what a refused save may have found wrong with it.
         *
         * @param summary {@link String} what to say above the page
         * @param cards a {@link List} of {@link CardRefusal} one per submitted card, in that order
         */
        record Refused(String summary, List<CardRefusal> cards) implements SaveOutcome {
        }
    }

    /**
     * What one submitted card had wrong with it, or nulls throughout when it had nothing.
     *
     * @param name what is wrong with the name, or null
     * @param description what is wrong with the description, or null
     * @param examples what is wrong with the examples, or null
     */
    public record CardRefusal(@Nullable String name, @Nullable String description,
                              @Nullable String examples) {

        /**
         * Whether this card is one the reader has to go and fix.
         *
         * @return boolean true when any field carries a message
         */
        public boolean isAtFault() {
            return this.name != null || this.description != null || this.examples != null;
        }
    }
}
