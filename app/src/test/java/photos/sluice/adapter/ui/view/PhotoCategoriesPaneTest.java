package photos.sluice.adapter.ui.view;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.CategoryName;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportText;

// What only a built scene graph can be wrong about. Which controls a card draws, what Delete and
// Add do to the page, and what a refusal marks. What a save decides is PhotoCategoriesPresenterTest's.
class PhotoCategoriesPaneTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void drawsOneCardPerConfiguredCategory() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        assertThat(cards(pane)).hasSize(2);
    }

    @Test
    void aPageNobodyHasTypedIntoHasNothingToLose() throws Exception {
        final PhotoCategoriesPane.Mounted mounted = onFxThread(() -> mounted(store()));

        assertThat(mounted.hasUnsavedEdits().getAsBoolean()).isFalse();
    }

    @Test
    void aDescriptionTypedIntoIsSomethingToLose() throws Exception {
        final PhotoCategoriesPane.Mounted mounted = onFxThread(() -> mounted(store()));

        runOnFxThread(() -> typeIntoTheDescription(mounted));

        assertThat(mounted.hasUnsavedEdits().getAsBoolean()).isTrue();
    }

    // Add builds its card straight onto the live list rather than redrawing, so an answer taken from
    // the cards this page opened with would miss it.
    @Test
    void aCardAddedAndNotSavedIsSomethingToLose() throws Exception {
        final PhotoCategoriesPane.Mounted mounted = onFxThread(() -> mounted(store()));

        runOnFxThread(() -> press(mounted, "#photo-categories-add"));

        assertThat(mounted.hasUnsavedEdits().getAsBoolean()).isTrue();
    }

    @Test
    void aSaveThatTookLeavesNothingToLose() throws Exception {
        final PhotoCategoriesPane.Mounted mounted = onFxThread(() -> mounted(store()));
        runOnFxThread(() -> typeIntoTheDescription(mounted));

        runOnFxThread(() -> press(mounted, "#photo-categories-save"));

        assertThat(mounted.hasUnsavedEdits().getAsBoolean()).isFalse();
    }

    @Test
    void aCardOpensHoldingWhatIsConfigured() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        final Node ordinary = ordinaryCard(pane);
        assertThat(((TextField) ordinary.lookup("#category-name")).getText()).isEqualTo("blurry");
        assertThat(((TextArea) ordinary.lookup("#category-description")).getText())
                .isEqualTo("Not worth keeping");
        assertThat(((TextArea) ordinary.lookup("#category-examples")).getText())
                .isEqualTo("pocket shots\nceiling shots");
        assertThat(((CheckBox) ordinary.lookup("#category-enabled")).isSelected()).isTrue();
    }

    @Test
    void theLibraryCategorysNameCannotBeTypedIntoAndItHasNoDelete() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        final Node fixed = builtInCard(pane);
        assertThat(((TextField) fixed.lookup("#category-name")).isEditable()).isFalse();
        assertThat(fixed.lookup("#category-delete")).isNull();
        assertThat(ordinaryCard(pane).lookup("#category-delete")).isNotNull();
    }

    @Test
    void theLibraryCategorysSwitchStillWorks() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        final var toggle = (CheckBox) builtInCard(pane).lookup("#category-enabled");
        assertThat(toggle.isDisabled()).isFalse();
    }

    @Test
    void theLastCardStillOnCanBeSwitchedOffAndDeletedLikeAnyOther() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        switchOff(pane, builtInCard(pane));

        final Node last = ordinaryCard(pane);
        assertThat(last.lookup("#category-enabled").isDisabled()).isFalse();
        assertThat(last.lookup("#category-delete").isDisabled()).isFalse();
    }

    // A pointer offering an edit the field refuses. The stylesheet is what carries this, so the
    // check is that the rule reaches the control, not what the desktop draws.
    @Test
    void theBuiltInNameFieldOffersNoTextCursor() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        assertThat(builtInCard(pane).lookup("#category-name").getCursor()).isEqualTo(Cursor.DEFAULT);
        assertThat(ordinaryCard(pane).lookup("#category-name").getCursor()).isEqualTo(Cursor.TEXT);
    }

    // Focus has to reach it, because selecting the value to copy it needs focus. What must not
    // follow is the ring every editable field draws, which promises an edit this one refuses.
    @Test
    void theBuiltInNameFieldTakesFocusWithoutDrawingTheFocusRing() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));
        final var fixed = (TextField) builtInCard(pane).lookup("#category-name");
        final var ordinary = (TextField) ordinaryCard(pane).lookup("#category-name");

        runOnFxThread(() -> {
            fixed.requestFocus();
            pane.applyCss();
        });

        assertThat(fixed.isFocused()).isTrue();
        assertThat(fixed.getSelection()).isNotNull();
        assertThat(borderOf(fixed)).isNotEqualTo(focusedBorderOf(ordinary, pane));
    }

    @Test
    void everyFieldOnACardStopsAtItsOwnCeiling() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));
        final Node card = ordinaryCard(pane);
        final var name = (TextField) card.lookup("#category-name");
        final var description = (TextArea) card.lookup("#category-description");
        final var examples = (TextArea) card.lookup("#category-examples");

        runOnFxThread(() -> {
            name.appendText("n".repeat(5000));
            description.appendText("d".repeat(5000));
            examples.appendText("e".repeat(5000));
        });

        assertThat(name.getText()).hasSize(CategoryName.maxLength());
        assertThat(description.getText()).hasSize(CullCategory.maxDescription());
        assertThat(examples.getText())
                .hasSize(CullCategory.maxExamples() * CullCategory.maxExample());
    }

    @Test
    void theSwitchSaysWhichStateTheCardIsInAndFollowsIt() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));
        final var toggle = (CheckBox) ordinaryCard(pane).lookup("#category-enabled");

        assertThat(toggle.getText()).isEqualTo("Enabled");

        switchOff(pane, ordinaryCard(pane));

        assertThat(toggle.getText()).isEqualTo("Disabled");
    }

    @Test
    void addIsWithdrawnOnceTheSetIsFullAndComesBackAfterADelete() throws Exception {
        final Parent pane = onFxThread(() -> built(aFullSet()));

        assertThat(pane.lookup("#photo-categories-add").isDisabled()).isTrue();

        press(cards(pane).getFirst(), "#category-delete");

        assertThat(pane.lookup("#photo-categories-add").isDisabled()).isFalse();
    }

    @Test
    void addStaysAvailableWhileThereIsStillRoom() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        assertThat(pane.lookup("#photo-categories-add").isDisabled()).isFalse();
    }

    @Test
    void aCardThatIsAlreadyOffStaysDeletableWhileAnotherHoldsTheSetUp() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        switchOff(pane, ordinaryCard(pane));

        assertThat(ordinaryCard(pane).lookup("#category-delete").isDisabled()).isFalse();
    }

    @Test
    void switchingASecondCardBackOnOffersEveryControlAgain() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));
        switchOff(pane, builtInCard(pane));

        switchOn(pane, builtInCard(pane));

        assertThat(ordinaryCard(pane).lookup("#category-enabled").isDisabled()).isFalse();
        assertThat(ordinaryCard(pane).lookup("#category-delete").isDisabled()).isFalse();
    }

    @Test
    void deletingTheLastCardStillOnIsOfferedLikeAnyOther() throws Exception {
        final Parent pane = onFxThread(() -> built(threeOrdinaryCards()));

        switchOff(pane, cards(pane).get(0));
        switchOff(pane, cards(pane).get(1));

        final Node stillOn = cards(pane).get(2);
        assertThat(stillOn.lookup("#category-delete").isDisabled()).isFalse();
        assertThat(stillOn.lookup("#category-enabled").isDisabled()).isFalse();
        assertThat(cards(pane).getFirst().lookup("#category-delete").isDisabled()).isFalse();
    }

    @Test
    void addingACategoryPutsAnEmptyCardAtTheEnd() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        press(pane, "#photo-categories-add");

        assertThat(cards(pane)).hasSize(3);
        assertThat(((TextField) cards(pane).getLast().lookup("#category-name")).getText()).isEmpty();
    }

    // Delete has to keep pointing at its own card once the page has been edited under it, which is
    // exactly what a captured position would stop doing. Three cards, and the last one deleted
    // after the first has gone. Its position has moved from 2 to 1. A captured 2 removes nothing,
    // and a captured index into the list removes the wrong card.
    @Test
    void deletingAfterAnEarlierCardHasGoneTakesTheCardTheButtonBelongsTo() throws Exception {
        final Parent pane = onFxThread(() -> built(threeOrdinaryCards()));

        press(cards(pane).getFirst(), "#category-delete");
        press(cards(pane).getLast(), "#category-delete");

        assertThat(cards(pane)).hasSize(1);
        assertThat(((TextField) cards(pane).getFirst().lookup("#category-name")).getText())
                .isEqualTo("scenery");
    }

    @Test
    void savingWhatIsOnScreenReachesTheSettingsSeam() throws Exception {
        final var store = store();
        final Parent pane = onFxThread(() -> built(store));

        runOnFxThread(() -> {
            ((TextArea) ordinaryCard(pane).lookup("#category-description")).setText("Blurry shots");
            ((CheckBox) ordinaryCard(pane).lookup("#category-enabled")).setSelected(false);
        });
        press(pane, "#photo-categories-save");

        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories().getLast())
                .isEqualTo(new CullCategory("blurry", "Blurry shots",
                        List.of("pocket shots", "ceiling shots"), Boolean.FALSE));
    }

    @Test
    void aSavedPageSaysSo() throws Exception {
        final Parent pane = onFxThread(() -> built(store()));

        press(pane, "#photo-categories-save");

        assertThat(pane.lookup("#settings-report-banner")).isNotNull();
    }

    @Test
    void aRefusedSaveMarksTheCardAtFaultAndWritesNothing() throws Exception {
        final var store = store();
        final Parent pane = onFxThread(() -> built(store));

        runOnFxThread(() ->
                ((TextField) ordinaryCard(pane).lookup("#category-name")).setText(""));
        press(pane, "#photo-categories-save");

        assertThat(store.saved).isNull();
        assertThat(violationUnder(ordinaryCard(pane), "#category-name"))
                .contains("Give this category a name");
        assertThat(reportText(pane)).isNotEmpty();
    }

    // A save rebuilds every node, so a mark gone after one proves nothing about clearing it. Two
    // refusals in a row is the only state where clearing is what took the first one's mark away.
    @Test
    void aSecondRefusalDoesNotLeaveTheFirstOnesMarkOnACardSinceFixed() throws Exception {
        final var store = store();
        final Parent pane = onFxThread(() -> built(store));
        runOnFxThread(() -> {
            ((TextField) ordinaryCard(pane).lookup("#category-name")).setText("");
            ((TextArea) builtInCard(pane).lookup("#category-description")).setText("");
        });
        press(pane, "#photo-categories-save");

        runOnFxThread(() ->
                ((TextField) ordinaryCard(pane).lookup("#category-name")).setText("blurry"));
        press(pane, "#photo-categories-save");

        assertThat(store.saved).isNull();
        assertThat(violationUnder(ordinaryCard(pane), "#category-name")).isEmpty();
        assertThat(violationUnder(builtInCard(pane), "#category-description")).isNotEmpty();
    }

    @Test
    void fixingWhatWasRefusedAndSavingAgainLetsItThrough() throws Exception {
        final var store = store();
        final Parent pane = onFxThread(() -> built(store));
        runOnFxThread(() ->
                ((TextField) ordinaryCard(pane).lookup("#category-name")).setText(""));
        press(pane, "#photo-categories-save");

        runOnFxThread(() ->
                ((TextField) ordinaryCard(pane).lookup("#category-name")).setText("blurry"));
        press(pane, "#photo-categories-save");

        assertThat(store.saved).isNotNull();
        assertThat(pane.lookup("#settings-report-banner")).isNotNull();
    }

    @Test
    void backGoesWhereItWasOpenedFrom() throws Exception {
        final var went = new boolean[1];
        final Parent pane = onFxThread(() -> built(store(), () -> went[0] = true));

        press(pane, "#photo-categories-back");

        assertThat(went[0]).isTrue();
    }

    private static String violationUnder(final Node card, final String fieldId) {
        final Parent row = card.lookup(fieldId).getParent();
        return row.getChildrenUnmodifiable().stream()
                .filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .filter(label -> label.getStyleClass().contains("settings-violation"))
                .map(TextArea::getText).findFirst().orElseGet(() -> siblingViolation(card));
    }

    // A name field's own violation sits beside its row rather than inside it, since the row groups
    // the label, the rule and the field. Falling through to the card's own is what finds it.
    private static String siblingViolation(final Node card) {
        return ((Parent) card).getChildrenUnmodifiable().stream()
                .filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .filter(label -> label.getStyleClass().contains("settings-violation"))
                .map(TextArea::getText).findFirst().orElse("");
    }

    private static List<Node> cards(final Parent pane) {
        return new ArrayList<>(((Parent) pane.lookup(".category-cards")).getChildrenUnmodifiable());
    }

    // The root is taken before the press, not after. Delete takes the card holding the button out
    // of the scene, so asking the button's own node where it lives answers null once it has fired.
    private static void press(final Node where, final String id) throws Exception {
        runOnFxThread(() -> {
            final Parent root = where.getScene().getRoot();
            ((Button) where.lookup(id)).fire();
            root.applyCss();
            root.layout();
        });
    }

    private static void typeIntoTheDescription(final PhotoCategoriesPane.Mounted mounted) {
        ((TextArea) ordinaryCard((Parent) mounted.node()).lookup("#category-description"))
                .setText("Not worth keeping at all");
    }

    private static void press(final PhotoCategoriesPane.Mounted mounted, final String id) {
        ((Button) mounted.node().lookup(id)).fire();
    }

    private static Parent built(final RecordingSettings store) {
        return built(store, () -> { });
    }

    private static Parent built(final RecordingSettings store, final Runnable onBack) {
        return (Parent) mounted(store, onBack).node();
    }

    private static PhotoCategoriesPane.Mounted mounted(final RecordingSettings store) {
        return mounted(store, () -> { });
    }

    private static PhotoCategoriesPane.Mounted mounted(final RecordingSettings store,
                                                       final Runnable onBack) {
        // The whole page rather than the scrolling half, so a lookup reaches the header outside it
        // as well as the cards inside.
        final PhotoCategoriesPane.Mounted built =
                PhotoCategoriesPane.pane(new PhotoCategoriesPresenter(store), onBack);
        final var page = (Parent) built.node();
        final var scene = new Scene(new StackPane(page), 900, 800);
        scene.getStylesheets().add(
                Objects.requireNonNull(PhotoCategoriesPaneTest.class.getResource("/ui/sluice.css"),
                        "the app stylesheet is missing from the test classpath").toExternalForm());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return built;
    }

    private static RecordingSettings store() {
        return new RecordingSettings(
                new CullCategory("blurry", "Not worth keeping",
                        List.of("pocket shots", "ceiling shots"), Boolean.TRUE),
                CullCategory.of("funny", "Worth a laugh later"));
    }

    // Three cards, none of them the built-in one. So every one has a Delete, and the list can shift
    // under a button without the undeletable card standing in for the rule.
    private static RecordingSettings threeOrdinaryCards() {
        return new RecordingSettings(
                CullCategory.of("blurry", "Not worth keeping"),
                CullCategory.of("scenery", "Worth a second look"),
                CullCategory.of("food", "Meals and menus"));
    }

    /**
     * The built-in card, which the presenter draws first whatever the configured order.
     */
    private static Node builtInCard(final Parent pane) {
        return cards(pane).getFirst();
    }

    /**
     * The one ordinary card in a two-card fixture, drawn after the built-in one.
     */
    private static Node ordinaryCard(final Parent pane) {
        return cards(pane).getLast();
    }

    private static Paint borderOf(final Region field) {
        return field.getBorder().getStrokes().getFirst().getTopStroke();
    }

    /**
     * What an ordinary field's border becomes once it holds focus, which is the ring the fixed one
     * must not draw. Read off a real focused field rather than named as a colour, so the check
     * follows the stylesheet instead of repeating it.
     */
    private static Paint focusedBorderOf(final TextField field, final Parent pane) {
        runOnFx(() -> {
            field.requestFocus();
            pane.applyCss();
        });
        return borderOf(field);
    }

    private static void runOnFx(final Runnable work) {
        try {
            runOnFxThread(work);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RecordingSettings aFullSet() {
        return new RecordingSettings(IntStream.range(0, Settings.maxCategories())
                .mapToObj(i -> CullCategory.of("card-" + i, "description " + i))
                .toArray(CullCategory[]::new));
    }

    private static void switchOff(final Parent pane, final Node card) throws Exception {
        setSwitch(pane, card, false);
    }

    private static void switchOn(final Parent pane, final Node card) throws Exception {
        setSwitch(pane, card, true);
    }

    private static void setSwitch(final Parent pane, final Node card, final boolean on) throws Exception {
        runOnFxThread(() -> {
            ((CheckBox) card.lookup("#category-enabled")).setSelected(on);
            pane.applyCss();
            pane.layout();
        });
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static final class RecordingSettings implements SettingsUseCase {

        private final List<CullCategory> cards;

        private @Nullable Settings saved;

        private RecordingSettings(final CullCategory... cards) {
            this.cards = new ArrayList<>(List.of(cards));
        }

        @Override
        public Settings settings() {
            return new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"), "anthropic",
                    Map.of("anthropic", new CullProviderSettings("a-model", null, 2)),
                    this.saved == null ? this.cards : this.saved.categories(),
                    new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        }

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.empty();
        }

        @Override
        public void save(final Settings settings) {
            this.saved = settings;
        }
    }
}
