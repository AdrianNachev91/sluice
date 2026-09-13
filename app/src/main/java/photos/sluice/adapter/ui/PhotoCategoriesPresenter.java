package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.PhotoCategoriesView.CardRefusal;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryEdit;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;
import photos.sluice.adapter.ui.PhotoCategoriesView.SaveOutcome;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.Settings;
import photos.sluice.domain.cull.CategoryName;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.JunkCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what the Photo categories screen shows and carries out what a user does on it.
 *
 * <p>The screen edits the one part of the culling brain a user owns. Everything else an automated
 * provider is told, the safety rules, the output format and the shard contract, is the app's and is
 * not on any screen.
 *
 * <p>No override note, unlike every other settings surface. One is drawn where a source above the
 * config file supplies a value, found by asking whether that source holds the property. A list of
 * cards is never held under {@code sluice.sift.categories} itself: an environment variable or a
 * command-line argument supplies {@code sluice.sift.categories[0].name} and its siblings. So the
 * question has no answer to give here, and a note that cannot fire is worse than none.
 */
@Component
@Profile("!cli")
public class PhotoCategoriesPresenter {

    private static final Logger log = LoggerFactory.getLogger(PhotoCategoriesPresenter.class);

    // Says what a category does before saying what one may be called. That is the part a reader
    // cannot change, and the part they would otherwise assume.
    private static final String DISPOSITION = "A sift always takes out the plainly worthless, and "
            + "those photos go to a junk folder under Review. A category you add gives one of those "
            + "photos a better home: where it matches a category, it goes to that folder instead, "
            + "so you can look through one reason at a time. The one exception is the built-in "
            + "category at the top, whose photos are moved straight to your Library. It is also "
            + "the only one judged on what a photo is rather than on how good it is. A description "
            + "does two jobs: it says what belongs in the category, and it says when a photo of "
            + "that kind should go to the folder rather than stay in Sorted.";

    private static final String FIXED_NOTE = "Built in. This is the one category moved straight to "
            + "your Library rather than to Review, and the one judged on what a photo is rather "
            + "than on how good it is. It cannot be renamed or deleted. You can switch it off, and "
            + "edit what belongs in it.";

    // The same sentence the Settings screen says for the same thing, down to the wording. It names
    // no direction: the marks are on the cards, and this line sits under the last of them.
    private static final String CARDS_ARE_MARKED =
            "These categories were not saved. Each field that needs fixing is marked below.";

    // The whole examples box rather than one line of it, since the box is the control. Its own
    // per-line rule stays with the value type, which is what a save is judged against.
    private static final PhotoCategoriesView.Limits LIMITS = new PhotoCategoriesView.Limits(
            CategoryName.maxLength(), CullCategory.maxDescriptionLength(),
            CullCategory.maxExamples() * CullCategory.maxExample());

    private static final String TOO_MANY = "There is room for at most " + Settings.maxCategories()
            + " photo categories. Remove some before saving.";

    private static final String NO_NAME = "Give this category a name.";

    private static final String SUPPLIED_NAME = "A built-in category is already called this, and it "
            + "is always on. Choose another name.";

    private static final String NO_DESCRIPTION = "Say what belongs in this category, so the vision "
            + "provider doing the sifting knows the criteria for sifting to it.";

    private final SettingsUseCase settingsUseCase;

    /**
     * Creates the presenter over the settings seam it reads and writes through.
     *
     * @param settingsUseCase {@link SettingsUseCase} reads the cards and saves an edited set
     */
    public PhotoCategoriesPresenter(final SettingsUseCase settingsUseCase) {
        this.settingsUseCase = settingsUseCase;
    }

    /**
     * What the screen draws right now, read fresh from the settings in force.
     *
     * <p>The built-in card is drawn first wherever it sits in configuration, because the page's
     * opening line points at it. Everything else keeps its configured order, and a save writes the
     * cards back in the order they are drawn. So opening this screen and saving can move that one
     * card to the top of the config file.
     *
     * @return {@link PhotoCategoriesView} the cards and the page's own copy
     */
    public PhotoCategoriesView view() {
        final Settings settings = this.settingsUseCase.settings();
        final List<CategoryRow> rows = settings.categories().stream()
                .sorted(Comparator.comparingInt(card -> isBuiltIn(card.name()) ? 0 : 1))
                .map(PhotoCategoriesPresenter::row)
                .toList();
        return new PhotoCategoriesView(rows, DISPOSITION, nameRule(), LIMITS);
    }

    /**
     * A blank card for the screen to add, already switched on.
     *
     * <p>Its name is empty rather than invented. A generated one would either collide with a card
     * the user already has or read as a real category until they noticed.
     *
     * @return {@link CategoryRow} the new card's row
     */
    public CategoryRow blankCard() {
        return new CategoryRow("", "", List.of(), true, null);
    }

    /**
     * Persists an edited set of cards, in the order the screen hands them over.
     *
     * <p>Nothing is written unless every card passes. A partly-saved rule set is worse than a
     * refused one: the reader would have to work out which half took.
     *
     * @param edits a {@link List} of {@link CategoryEdit} the cards as they were left on screen
     * @return {@link SaveOutcome} what happened, with a message per card when it was refused
     */
    public SaveOutcome save(final List<CategoryEdit> edits) {
        final List<CardRefusal> refusals = refusalsFor(edits);
        if (refusals.stream().anyMatch(CardRefusal::isAtFault)) {
            return new SaveOutcome.Refused(CARDS_ARE_MARKED, refusals);
        }
        final String missingBuiltIn = this.builtInGoneMissing(edits);
        if (missingBuiltIn != null) {
            return new SaveOutcome.Refused(missingBuiltIn, refusals);
        }
        // Refused here as well as in Settings, because reaching that one throws out of a button
        // press rather than answering the screen.
        if (edits.size() > Settings.maxCategories()) {
            return new SaveOutcome.Refused(TOO_MANY, refusals);
        }
        final Settings current = this.settingsUseCase.settings();
        final List<CullCategory> cards = edits.stream()
                .map(edit -> new CullCategory(edit.name().strip(), edit.description().strip(),
                        edit.examples(), edit.enabled()))
                .toList();
        try {
            this.settingsUseCase.save(new Settings(current.paths(), current.provider(),
                    current.providerSettingsById(), cards, current.montage(),
                    current.theme()));
        } catch (final RuntimeException e) {
            log.warn("Could not save the photo categories", e);
            return new SaveOutcome.Refused(
                    SettingsRefusals.refusalWording(e, "Your photo categories were not saved."),
                    refusals);
        }
        return new SaveOutcome.Saved();
    }

    /**
     * Whether the cards on screen say anything a save would store differently.
     *
     * <p>Judged against what a save would write rather than against the characters typed. Names and
     * descriptions are stored stripped, and a blank line in an examples box is stored as nothing. So
     * a trailing space is not a change, and neither is an empty row somebody tabbed through.
     *
     * <p>Order counts, because a save writes the cards back in the order they are drawn. Moving one
     * is a change even where every card still says exactly what it said.
     *
     * @param onScreen a {@link List} of {@link CategoryEdit} the cards as they stand now
     * @return boolean true where leaving would lose something
     */
    public boolean hasUnsavedEdits(final List<CategoryEdit> onScreen) {
        final List<CategoryEdit> stored = this.view().categories().stream()
                .map(row -> new CategoryEdit(row.name(), row.description(), row.examples(), row.enabled()))
                .map(PhotoCategoriesPresenter::asItWouldBeStored)
                .toList();
        return !stored.equals(onScreen.stream().map(PhotoCategoriesPresenter::asItWouldBeStored).toList());
    }

    /**
     * Whether the page already holds as many cards as an install may, so Add is not offered.
     *
     * <p>The ceiling's other two halves stay where they are. A config file reaches {@link #save}
     * without passing a button, and {@link Settings} refuses a set that never passes the screen.
     *
     * @param cardsOnScreen int how many cards the page is holding right now, edits included
     * @return boolean true when no further card may be added
     */
    public boolean isTheSetFull(final int cardsOnScreen) {
        return cardsOnScreen >= Settings.maxCategories();
    }

    /**
     * One card reduced to what a save would actually write from it.
     *
     * @param edit {@link CategoryEdit} the card as it stands
     * @return {@link CategoryEdit} the same card, stripped and with its blank examples dropped
     */
    private static CategoryEdit asItWouldBeStored(final CategoryEdit edit) {
        return new CategoryEdit(edit.name().strip(), edit.description().strip(),
                edit.examples().stream().map(String::strip).filter(example -> !example.isEmpty()).toList(),
                edit.enabled());
    }

    /**
     * Whether a save would drop the built-in card, and what to say when it would.
     *
     * <p>The screen already draws that card with no Delete and a name that cannot be typed in, so
     * this is unreachable through the interface. It is here because of what the promise is about:
     * whether a vision model may write into the user's library. A promise kept only by how a
     * screen is drawn is one refactor away from being untrue.
     *
     * <p>Conditional on the card being configured now. An install whose config file never named it
     * has nothing to protect, and refusing there would trap the user on a screen they cannot save.
     *
     * @param edits a {@link List} of {@link CategoryEdit} the cards being saved
     * @return {@link String} what to say, or null when the save may go ahead
     */
    private @Nullable String builtInGoneMissing(final List<CategoryEdit> edits) {
        final boolean wasConfigured = this.settingsUseCase.settings().categories().stream()
                .anyMatch(card -> isBuiltIn(card.name()));
        if (!wasConfigured) {
            return null;
        }
        final boolean stillThere = edits.stream()
                .anyMatch(edit -> isBuiltIn(edit.name().strip()));
        return stillThere ? null : "The built-in '" + CategoryName.LIBRARY_CATEGORY + "' category "
                + "cannot be renamed or deleted. It is the one category moved straight to your "
                + "Library rather than to Review.";
    }

    /**
     * One refusal per card, in the order they were submitted.
     *
     * <p>Every card is judged rather than stopping at the first. A reader then fixes everything in
     * one pass, instead of learning about the next problem after each save.
     *
     * @param edits a {@link List} of {@link CategoryEdit} the cards being saved
     * @return a {@link List} of {@link CardRefusal} one per card, most of them empty
     */
    private static List<CardRefusal> refusalsFor(final List<CategoryEdit> edits) {
        final Set<String> seen = new HashSet<>();
        final Set<String> duplicated = new HashSet<>();
        for (final CategoryEdit edit : edits) {
            final String name = edit.name().strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !seen.add(name)) {
                duplicated.add(name);
            }
        }
        final var refusals = new ArrayList<CardRefusal>();
        for (final CategoryEdit edit : edits) {
            refusals.add(refusalFor(edit, duplicated));
        }
        return refusals;
    }

    /**
     * What is wrong with one card, judged in the screen's own words rather than by letting
     * {@link CullCategory}'s constructor throw. A refused save has to mark the field at fault, and
     * an exception carries no field.
     *
     * @param edit {@link CategoryEdit} the card being saved
     * @param duplicated a {@link Set} of {@link String} the names more than one card claims
     * @return {@link CardRefusal} what is wrong with it, or an empty refusal
     */
    private static CardRefusal refusalFor(final CategoryEdit edit, final Set<String> duplicated) {
        final String name = edit.name().strip();
        final String description = edit.description().strip();
        final String descriptionProblem = description.isEmpty() ? NO_DESCRIPTION : null;
        final String examplesProblem = examplesProblem(edit);
        if (name.isEmpty()) {
            return new CardRefusal(NO_NAME, descriptionProblem, examplesProblem);
        }
        if (duplicated.contains(name.toLowerCase(Locale.ROOT))) {
            return new CardRefusal("Another category is already called '" + name
                    + "'. Each one needs a name of its own, because the name is its folder.",
                    descriptionProblem, examplesProblem);
        }
        // Marked here as well as refused in Settings, because reaching that one throws out of a
        // button press rather than answering the screen.
        if (JunkCategory.isJunkName(name)) {
            return new CardRefusal(SUPPLIED_NAME, descriptionProblem, examplesProblem);
        }
        final String shape = CategoryName.problemWith(name);
        return new CardRefusal(shape == null ? null : "This name " + shape + ".", descriptionProblem,
                examplesProblem);
    }

    /**
     * What is wrong with one card's examples, or null when nothing is.
     *
     * <p>The box holds one ceiling over the whole of it, which is not the rule a card is judged by:
     * that one counts the entries and measures each. So a box a reader was allowed to fill can still
     * carry twenty-one examples, or one that runs too long.
     *
     * @param edit {@link CategoryEdit} the card being saved
     * @return {@link String} what to say under the box, or null
     */
    private static @Nullable String examplesProblem(final CategoryEdit edit) {
        final List<String> offered = edit.examples().stream()
                .filter(example -> !example.isBlank())
                .toList();
        if (offered.size() > CullCategory.maxExamples()) {
            return "Keep at most " + CullCategory.maxExamples() + " examples. There are "
                    + offered.size() + ".";
        }
        return offered.stream().anyMatch(example -> example.strip().length() > CullCategory.maxExample())
                ? "An example is a phrase, not a sentence. Keep each one to "
                        + CullCategory.maxExample() + " characters."
                : null;
    }

    /**
     * The card as a row, marking the one name with a destination of its own.
     *
     * @param card {@link CullCategory} the configured card
     * @return {@link CategoryRow} the row to draw
     */
    private static CategoryRow row(final CullCategory card) {
        final boolean builtIn = isBuiltIn(card.name());
        return new CategoryRow(card.name(), card.description(), card.examples(), card.enabled(),
                builtIn ? FIXED_NOTE : null);
    }

    /**
     * Whether a name is the built-in card's, which is the one whose photos reach the library
     * without review.
     *
     * @param name {@link String} the card's name, already stripped
     * @return boolean true when this is the built-in card
     */
    private static boolean isBuiltIn(final String name) {
        return name.equals(CategoryName.LIBRARY_CATEGORY);
    }

    /**
     * The rule a name has to keep, worded for a reader. The ceiling is read off the class that
     * enforces it, so the sentence cannot promise a length the save would then refuse.
     *
     * <p>"A folder name" rather than the folder its photos are set aside in. The built-in card's
     * photos are not set aside at all, and its folder is not derived from its name.
     *
     * @return {@link String} the help line the page carries above its cards
     */
    private static String nameRule() {
        return "A category's name becomes a folder name, so it takes lower-case letters and digits "
                + "only, joined by hyphens, up to " + CategoryName.maxLength() + " characters. A "
                + "few names are refused too: ones the operating system reserves, and ones already "
                + "taken by a folder or a decision of the app's own.";
    }

}
