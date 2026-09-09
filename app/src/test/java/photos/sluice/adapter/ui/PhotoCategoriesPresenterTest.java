package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.PhotoCategoriesView.CardRefusal;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryEdit;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;
import photos.sluice.adapter.ui.PhotoCategoriesView.SaveOutcome;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoCategoriesPresenterTest {

    private static final CullCategory BLURRY = CullCategory.of("blurry", "Not worth keeping");
    private static final CullCategory FUNNY = CullCategory.of("funny", "Worth a laugh later");

    @Test
    void drawsTheBuiltInCardFirstWhereverItIsConfigured() {
        final PhotoCategoriesView view = presenterOver(BLURRY, FUNNY).view();

        assertThat(view.categories()).extracting(CategoryRow::name).containsExactly("funny", "blurry");
    }

    @Test
    void everyOtherCardKeepsTheOrderItIsConfiguredIn() {
        final PhotoCategoriesView view = presenterOver(
                CullCategory.of("scenery", "Worth a second look"), FUNNY,
                CullCategory.of("food", "Meals and menus"), BLURRY).view();

        assertThat(view.categories()).extracting(CategoryRow::name)
                .containsExactly("funny", "scenery", "food", "blurry");
    }

    @Test
    void theLibraryCategoryIsTheOnlyOneDrawnAsFixed() {
        final PhotoCategoriesView view = presenterOver(BLURRY, FUNNY).view();

        assertThat(view.categories().getFirst().fixed()).contains("Library");
        assertThat(view.categories().getLast().fixed()).isNull();
    }

    @Test
    void theNameRuleQuotesTheLengthTheDomainActuallyEnforces() {
        final PhotoCategoriesView view = presenterOver(BLURRY).view();

        assertThat(view.nameRule()).contains("24 characters");
    }

    @Test
    void aNewCardOpensEmptyAndSwitchedOn() {
        final CategoryRow blank = presenterOver(BLURRY).blankCard();

        assertThat(blank.name()).isEmpty();
        assertThat(blank.description()).isEmpty();
        assertThat(blank.examples()).isEmpty();
        assertThat(blank.enabled()).isTrue();
        assertThat(blank.fixed()).isNull();
    }

    @Test
    void savingWritesTheEditedCardsAndLeavesEverySettingBesideThemAlone() {
        final var store = new RecordingSettings(BLURRY, FUNNY);
        final var presenter = new PhotoCategoriesPresenter(store);

        final SaveOutcome outcome = presenter.save(List.of(
                new CategoryEdit("blurry", "Blurry and accidental", List.of("pocket shots"), false),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories()).containsExactly(
                new CullCategory("blurry", "Blurry and accidental", List.of("pocket shots"), Boolean.FALSE),
                new CullCategory("funny", "Worth a laugh later", List.of(), Boolean.TRUE));
        assertThat(store.saved.provider()).isEqualTo("anthropic");
        assertThat(store.saved.montage()).isEqualTo(new MontageConfig(224, 5));
        assertThat(store.saved.theme()).isEqualTo(ThemeChoice.SYSTEM);
    }

    // Every refusal this screen puts up is one it worked out itself, so the save call reads as the
    // part that cannot fail.
    @Test
    void aSaveTheSettingsFileRefusesIsReportedRatherThanThrown() {
        final var store = new RecordingSettings(BLURRY, FUNNY);
        store.refusal = new UncheckedIOException(new IOException("the settings file is held open"));

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("blurry", "Blurry and accidental", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(((SaveOutcome.Refused) outcome).summary())
                .startsWith("Your photo categories were not saved")
                .contains("Try again");
    }

    @Test
    void aCardWithNoNameIsRefusedAndNothingIsWritten() {
        final var store = new RecordingSettings(BLURRY, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("  ", "Something", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(refusalsOf(outcome).getFirst().name()).contains("Give this category a name");
        assertThat(refusalsOf(outcome).getLast().isAtFault()).isFalse();
    }

    @Test
    void aNameThatCouldNotBecomeAFolderIsRefusedInTheDomainsOwnWords() {
        final SaveOutcome outcome = presenterOver(BLURRY, FUNNY).save(List.of(
                new CategoryEdit("Receipts", "Paperwork", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).getFirst().name()).contains("lower-case");
    }

    @Test
    void aCardNamedJunkIsMarkedOnTheScreenRatherThanThrowingOutOfTheSaveButton() {
        final SaveOutcome outcome = presenterOver(BLURRY, FUNNY).save(List.of(
                new CategoryEdit("junk", "Worthless", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Refused.class);
        assertThat(refusalsOf(outcome).getFirst().name()).contains("A built-in category is already called this");
    }

    @Test
    void aBlankDescriptionIsRefused() {
        final SaveOutcome outcome = presenterOver(BLURRY, FUNNY).save(List.of(
                new CategoryEdit("receipts", "   ", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).getFirst().description()).contains("what belongs");
    }

    // Two cards under one name would silently alias one folder, and the value type refuses the set
    // outright. Caught here instead, because a refusal has to say which card to fix.
    @Test
    void twoCardsUnderOneNameAreBothMarkedRatherThanThrowing() {
        final SaveOutcome outcome = presenterOver(BLURRY, FUNNY).save(List.of(
                new CategoryEdit("receipts", "Paperwork", List.of(), true),
                new CategoryEdit("receipts", "More paperwork", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).get(0).name()).contains("already called");
        assertThat(refusalsOf(outcome).get(1).name()).contains("already called");
        assertThat(refusalsOf(outcome).get(2).isAtFault()).isFalse();
    }

    @Test
    void everyCardAtFaultIsMarkedInOnePassRatherThanTheFirstOne() {
        final SaveOutcome outcome = presenterOver(BLURRY, FUNNY).save(List.of(
                new CategoryEdit("", "Paperwork", List.of(), true),
                new CategoryEdit("Receipts", "", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).get(0).name()).isNotNull();
        assertThat(refusalsOf(outcome).get(1).name()).isNotNull();
        assertThat(refusalsOf(outcome).get(1).description()).isNotNull();
    }

    @Test
    void aSaveThatDropsTheLibraryCategoryIsRefused() {
        final var store = new RecordingSettings(BLURRY, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store)
                .save(List.of(new CategoryEdit("blurry", "Not worth keeping", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(((SaveOutcome.Refused) outcome).summary()).contains("cannot be renamed or deleted");
    }

    @Test
    void aSaveOfMoreCardsThanOneInstallMayHoldIsRefused() {
        final var store = new RecordingSettings(BLURRY, FUNNY);
        final List<CategoryEdit> tooMany = new ArrayList<>(
                IntStream.range(0, 20).mapToObj(i -> new CategoryEdit("card-" + i, "d" + i, List.of(), true))
                        .toList());
        tooMany.add(new CategoryEdit("funny", "Worth a laugh later", List.of(), true));

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(tooMany);

        assertThat(store.saved).isNull();
        assertThat(((SaveOutcome.Refused) outcome).summary()).contains("20");
    }

    @Test
    void aSaveWithAnExamplePastItsCeilingIsRefused() {
        final var store = new RecordingSettings(BLURRY, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("blurry", "Not worth keeping",
                        List.of("x".repeat(CullCategory.maxExample() + 1)), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(outcome).isInstanceOf(SaveOutcome.Refused.class);
    }

    @Test
    void aSaveWithMoreExamplesThanACardMayCarryIsRefused() {
        final var store = new RecordingSettings(BLURRY, FUNNY);
        final List<String> tooMany = IntStream.rangeClosed(0, CullCategory.maxExamples())
                .mapToObj(i -> "example " + i).toList();

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("blurry", "Not worth keeping", tooMany, true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(outcome).isInstanceOf(SaveOutcome.Refused.class);
    }

    @Test
    void everyCardSwitchedOffSavesAndLeavesTheSiftWithJunkAlone() {
        final var store = new RecordingSettings(BLURRY, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("blurry", "Not worth keeping", List.of(), false),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), false)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories()).extracting(CullCategory::enabled)
                .containsExactly(false, false);
    }

    @Test
    void anInstallThatNeverConfiguredTheLibraryCategoryCanStillSave() {
        final var store = new RecordingSettings(BLURRY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store)
                .save(List.of(new CategoryEdit("blurry", "Not worth keeping", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories()).extracting(CullCategory::name).containsExactly("blurry");
    }

    @Test
    void cardsMatchingWhatIsStoredHaveNothingToLose() {
        final PhotoCategoriesPresenter presenter = presenterOver(BLURRY, FUNNY);

        assertThat(presenter.hasUnsavedEdits(asEdits(presenter))).isFalse();
    }

    @Test
    void aChangedDescriptionIsSomethingToLose() {
        final PhotoCategoriesPresenter presenter = presenterOver(BLURRY, FUNNY);
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        final CategoryEdit first = typed.getFirst();
        typed.set(0, new CategoryEdit(first.name(), first.description() + " and a bit more",
                first.examples(), first.enabled()));

        assertThat(presenter.hasUnsavedEdits(typed)).isTrue();
    }

    @Test
    void aCardSwitchedOffIsSomethingToLose() {
        final PhotoCategoriesPresenter presenter = presenterOver(BLURRY, FUNNY);
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        final CategoryEdit last = typed.getLast();
        typed.set(typed.size() - 1,
                new CategoryEdit(last.name(), last.description(), last.examples(), !last.enabled()));

        assertThat(presenter.hasUnsavedEdits(typed)).isTrue();
    }

    @Test
    void reorderedCardsAreSomethingToLose() {
        final PhotoCategoriesPresenter presenter = presenterOver(BLURRY, FUNNY,
                CullCategory.of("food", "Meals and menus"));
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        typed.add(typed.remove(1));

        assertThat(presenter.hasUnsavedEdits(typed)).isTrue();
    }

    @Test
    void aTrailingSpaceIsNothingToLose() {
        final PhotoCategoriesPresenter presenter = presenterOver(BLURRY, FUNNY);
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        final CategoryEdit first = typed.getFirst();
        typed.set(0, new CategoryEdit(first.name() + " ", "  " + first.description(),
                first.examples(), first.enabled()));

        assertThat(presenter.hasUnsavedEdits(typed)).isFalse();
    }

    @Test
    void anEmptyRowInAnExamplesBoxIsNothingToLose() {
        final PhotoCategoriesPresenter presenter =
                presenterOver(FUNNY, new CullCategory("blurry", "Not worth keeping", List.of("blurry"), true));
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        final CategoryEdit last = typed.getLast();
        typed.set(typed.size() - 1, new CategoryEdit(last.name(), last.description(),
                List.of("blurry", "   ", ""), last.enabled()));

        assertThat(presenter.hasUnsavedEdits(typed)).isFalse();
    }

    @Test
    void anAddedExampleIsSomethingToLose() {
        final PhotoCategoriesPresenter presenter =
                presenterOver(FUNNY, new CullCategory("blurry", "Not worth keeping", List.of("blurry"), true));
        final List<CategoryEdit> typed = new ArrayList<>(asEdits(presenter));
        final CategoryEdit last = typed.getLast();
        typed.set(typed.size() - 1, new CategoryEdit(last.name(), last.description(),
                List.of("blurry", "screenshots"), last.enabled()));

        assertThat(presenter.hasUnsavedEdits(typed)).isTrue();
    }

    private static List<CardRefusal> refusalsOf(final SaveOutcome outcome) {
        return ((SaveOutcome.Refused) outcome).cards();
    }

    private static List<CategoryEdit> asEdits(final PhotoCategoriesPresenter presenter) {
        return presenter.view().categories().stream()
                .map(row -> new CategoryEdit(row.name(), row.description(), row.examples(), row.enabled()))
                .toList();
    }

    private static PhotoCategoriesPresenter presenterOver(final CullCategory... cards) {
        return new PhotoCategoriesPresenter(new RecordingSettings(cards));
    }

    private static final class RecordingSettings implements SettingsUseCase {

        private final List<CullCategory> cards;

        private @Nullable Settings saved;
        private @Nullable RuntimeException refusal;

        private RecordingSettings(final CullCategory... cards) {
            this.cards = new ArrayList<>(List.of(cards));
        }

        @Override
        public Settings settings() {
            return new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"), "anthropic",
                    Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), this.cards,
                    new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        }

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.empty();
        }

        @Override
        public void save(final Settings settings) {
            if (this.refusal != null) {
                throw this.refusal;
            }
            this.saved = settings;
        }
    }
}
