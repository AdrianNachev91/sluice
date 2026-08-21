package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.PhotoCategoriesView.CardRefusal;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryEdit;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;
import photos.sluice.adapter.ui.PhotoCategoriesView.SaveOutcome;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoCategoriesPresenterTest {

    private static final CullCategory JUNK = CullCategory.of("junk", "Not worth keeping");
    private static final CullCategory FUNNY = CullCategory.of("funny", "Worth a laugh later");

    @Test
    void drawsTheBuiltInCardFirstWhereverItIsConfigured() {
        final PhotoCategoriesView view = presenterOver(JUNK, FUNNY).view();

        assertThat(view.categories()).extracting(CategoryRow::name).containsExactly("funny", "junk");
    }

    @Test
    void everyOtherCardKeepsTheOrderItIsConfiguredIn() {
        final PhotoCategoriesView view = presenterOver(
                CullCategory.of("scenery", "Worth a second look"), FUNNY,
                CullCategory.of("food", "Meals and menus"), JUNK).view();

        assertThat(view.categories()).extracting(CategoryRow::name)
                .containsExactly("funny", "scenery", "food", "junk");
    }

    @Test
    void theLibraryCategoryIsTheOnlyOneDrawnAsFixed() {
        final PhotoCategoriesView view = presenterOver(JUNK, FUNNY).view();

        assertThat(view.categories().getFirst().fixed()).contains("library");
        assertThat(view.categories().getLast().fixed()).isNull();
    }

    @Test
    void theNameRuleQuotesTheLengthTheDomainActuallyEnforces() {
        final PhotoCategoriesView view = presenterOver(JUNK).view();

        assertThat(view.nameRule()).contains("24 characters");
    }

    @Test
    void aNewCardOpensEmptyAndSwitchedOn() {
        final CategoryRow blank = presenterOver(JUNK).blankCard();

        assertThat(blank.name()).isEmpty();
        assertThat(blank.description()).isEmpty();
        assertThat(blank.examples()).isEmpty();
        assertThat(blank.enabled()).isTrue();
        assertThat(blank.fixed()).isNull();
    }

    @Test
    void savingWritesTheEditedCardsAndLeavesEverySettingBesideThemAlone() {
        final var store = new RecordingSettings(JUNK, FUNNY);
        final var presenter = new PhotoCategoriesPresenter(store);

        final SaveOutcome outcome = presenter.save(List.of(
                new CategoryEdit("junk", "Blurry and accidental", List.of("pocket shots"), false),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories()).containsExactly(
                new CullCategory("junk", "Blurry and accidental", List.of("pocket shots"), Boolean.FALSE),
                new CullCategory("funny", "Worth a laugh later", List.of(), Boolean.TRUE));
        assertThat(store.saved.provider()).isEqualTo("anthropic");
        assertThat(store.saved.montage()).isEqualTo(new MontageConfig(224, 5));
        assertThat(store.saved.theme()).isEqualTo(ThemeChoice.SYSTEM);
    }

    @Test
    void aCardWithNoNameIsRefusedAndNothingIsWritten() {
        final var store = new RecordingSettings(JUNK, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("  ", "Something", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(refusalsOf(outcome).getFirst().name()).contains("Give this category a name");
        assertThat(refusalsOf(outcome).getLast().isAtFault()).isFalse();
    }

    @Test
    void aNameThatCouldNotBecomeAFolderIsRefusedInTheDomainsOwnWords() {
        final SaveOutcome outcome = presenterOver(JUNK, FUNNY).save(List.of(
                new CategoryEdit("Receipts", "Paperwork", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).getFirst().name()).contains("lower-case");
    }

    @Test
    void aBlankDescriptionIsRefused() {
        final SaveOutcome outcome = presenterOver(JUNK, FUNNY).save(List.of(
                new CategoryEdit("receipts", "   ", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).getFirst().description()).contains("what belongs");
    }

    // Two cards under one name would silently alias one folder, and the value type refuses the set
    // outright. Caught here instead, because a refusal has to say which card to fix.
    @Test
    void twoCardsUnderOneNameAreBothMarkedRatherThanThrowing() {
        final SaveOutcome outcome = presenterOver(JUNK, FUNNY).save(List.of(
                new CategoryEdit("receipts", "Paperwork", List.of(), true),
                new CategoryEdit("receipts", "More paperwork", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).get(0).name()).contains("already called");
        assertThat(refusalsOf(outcome).get(1).name()).contains("already called");
        assertThat(refusalsOf(outcome).get(2).isAtFault()).isFalse();
    }

    @Test
    void everyCardAtFaultIsMarkedInOnePassRatherThanTheFirstOne() {
        final SaveOutcome outcome = presenterOver(JUNK, FUNNY).save(List.of(
                new CategoryEdit("", "Paperwork", List.of(), true),
                new CategoryEdit("Receipts", "", List.of(), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(refusalsOf(outcome).get(0).name()).isNotNull();
        assertThat(refusalsOf(outcome).get(1).name()).isNotNull();
        assertThat(refusalsOf(outcome).get(1).description()).isNotNull();
    }

    @Test
    void aSaveThatDropsTheLibraryCategoryIsRefused() {
        final var store = new RecordingSettings(JUNK, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store)
                .save(List.of(new CategoryEdit("junk", "Not worth keeping", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(((SaveOutcome.Refused) outcome).summary()).contains("cannot be renamed or deleted");
    }

    @Test
    void aSaveOfMoreCardsThanOneInstallMayHoldIsRefused() {
        final var store = new RecordingSettings(JUNK, FUNNY);
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
        final var store = new RecordingSettings(JUNK, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("junk", "Not worth keeping",
                        List.of("x".repeat(CullCategory.maxExample() + 1)), true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(outcome).isInstanceOf(SaveOutcome.Refused.class);
    }

    @Test
    void aSaveWithMoreExamplesThanACardMayCarryIsRefused() {
        final var store = new RecordingSettings(JUNK, FUNNY);
        final List<String> tooMany = IntStream.rangeClosed(0, CullCategory.maxExamples())
                .mapToObj(i -> "example " + i).toList();

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("junk", "Not worth keeping", tooMany, true),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(store.saved).isNull();
        assertThat(outcome).isInstanceOf(SaveOutcome.Refused.class);
    }

    @Test
    void aSaveWithEveryCardSwitchedOffIsRefused() {
        final var store = new RecordingSettings(JUNK, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("junk", "Not worth keeping", List.of(), false),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), false)));

        assertThat(store.saved).isNull();
        assertThat(((SaveOutcome.Refused) outcome).summary()).contains("at least one category");
    }

    @Test
    void oneCardLeftOnIsEnoughToSave() {
        final var store = new RecordingSettings(JUNK, FUNNY);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store).save(List.of(
                new CategoryEdit("junk", "Not worth keeping", List.of(), false),
                new CategoryEdit("funny", "Worth a laugh later", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
    }

    @Test
    void onlyTheCardThatIsOnIsHoldingTheSetUpWhenItIsTheLastOne() {
        final var presenter = presenterOver(JUNK, FUNNY);

        assertThat(presenter.isTheLastOneOn(1, true)).isTrue();
        assertThat(presenter.isTheLastOneOn(1, false)).isFalse();
        assertThat(presenter.isTheLastOneOn(2, true)).isFalse();
        assertThat(presenter.isTheLastOneOn(0, false)).isFalse();
    }

    @Test
    void anInstallThatNeverConfiguredTheLibraryCategoryCanStillSave() {
        final var store = new RecordingSettings(JUNK);

        final SaveOutcome outcome = new PhotoCategoriesPresenter(store)
                .save(List.of(new CategoryEdit("junk", "Not worth keeping", List.of(), true)));

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.categories()).extracting(CullCategory::name).containsExactly("junk");
    }

    private static List<CardRefusal> refusalsOf(final SaveOutcome outcome) {
        return ((SaveOutcome.Refused) outcome).cards();
    }

    private static PhotoCategoriesPresenter presenterOver(final CullCategory... cards) {
        return new PhotoCategoriesPresenter(new RecordingSettings(cards));
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
                    Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), this.cards,
                    new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
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
