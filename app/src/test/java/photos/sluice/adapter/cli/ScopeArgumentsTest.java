package photos.sluice.adapter.cli;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ScopeArgumentsTest {

    @Test
    void sortWithNothingSaidTakesTheOldestYearThereIs() {
        assertThat(nothing().sortScope("sort")).isEqualTo(new SortScope.OldestYear());
    }

    @Test
    void siftWithNothingSaidIsRefusedRatherThanGuessedAt() {
        final Refusal refusal = refusalOf(() -> nothing().cullScope("sift"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_MISSING);
        assertThat(refusal.sentence()).contains("sift 2019").contains("--oldest 30");
    }

    @Test
    void movingToTheLibraryWithNothingSaidIsRefusedAndAllIsWrittenOut() {
        final Refusal refusal = refusalOf(() -> nothing().commitScope("commit"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_MISSING);
        assertThat(refusal.sentence()).contains("commit all");
    }

    @Test
    void aYearScopesEachOfTheThreeVerbsToThatYear() {
        assertThat(year("2019").sortScope("sort")).isEqualTo(new SortScope.Year(2019, null));
        assertThat(year("2019").cullScope("sift")).isEqualTo(new CullScope.Year(2019, null));
        assertThat(year("2019").commitScope("commit")).isEqualTo(new CommitScope.Year(2019, null));
    }

    @Test
    void aSpanOfMonthsNarrowsAYearOnEveryVerbThatTakesOne() {
        assertThat(new ScopeArguments("2019", "6-8", null).sortScope("sort"))
                .isEqualTo(new SortScope.Year(2019, new MonthRange(6, 8)));
        assertThat(new ScopeArguments("2019", "6-8", null).commitScope("commit"))
                .isEqualTo(new CommitScope.Year(2019, new MonthRange(6, 8)));
        assertThat(new ScopeArguments("2019", "6-8", null).cullScope("sift"))
                .isEqualTo(new CullScope.Year(2019, List.of(6, 7, 8)));
    }

    @Test
    void onlySiftCanBeNarrowedToMonthsWithAGapBetweenThem() {
        assertThat(new ScopeArguments("2019", "6,8,11", null).cullScope("sift"))
                .isEqualTo(new CullScope.Year(2019, List.of(6, 8, 11)));
        assertThat(refusalOf(() -> new ScopeArguments("2019", "6,8,11", null).sortScope("sort")).kind())
                .isEqualTo(RefusalKind.MONTHS_NOT_A_SPAN);
        assertThat(refusalOf(() -> new ScopeArguments("2019", "6,8,11", null).commitScope("commit")).kind())
                .isEqualTo(RefusalKind.MONTHS_NOT_A_SPAN);
    }

    @Test
    void theOldestCountIsItsOwnScopeOnTheTwoVerbsThatTakeIt() {
        assertThat(oldest(30).sortScope("sort")).isEqualTo(new SortScope.OldestN(30));
        assertThat(oldest(30).cullScope("sift")).isEqualTo(new CullScope.OldestN(30));
    }

    @Test
    void aYearAndACountNameDifferentPhotosSoNeitherVerbTakesBoth() {
        final ScopeArguments both = new ScopeArguments("2019", null, 30);

        assertThat(refusalOf(() -> both.sortScope("sort")).kind()).isEqualTo(RefusalKind.SCOPE_CONFLICTING);
        assertThat(refusalOf(() -> both.cullScope("sift")).kind()).isEqualTo(RefusalKind.SCOPE_CONFLICTING);
    }

    @Test
    void monthsAskedForBesideACountAreRefusedBecauseACountNamesNoYear() {
        final ScopeArguments both = new ScopeArguments(null, "6-8", 30);

        assertThat(refusalOf(() -> both.sortScope("sort")).kind()).isEqualTo(RefusalKind.SCOPE_CONFLICTING);
        assertThat(refusalOf(() -> both.cullScope("sift")).kind()).isEqualTo(RefusalKind.SCOPE_CONFLICTING);
    }

    @Test
    void monthsWithNoYearToNarrowAreRefusedRatherThanAppliedToTheDefault() {
        final ScopeArguments monthsOnly = new ScopeArguments(null, "6-8", null);

        assertThat(refusalOf(() -> monthsOnly.sortScope("sort")).kind()).isEqualTo(RefusalKind.SCOPE_MISSING);
        assertThat(refusalOf(() -> monthsOnly.cullScope("sift")).kind()).isEqualTo(RefusalKind.SCOPE_MISSING);
        assertThat(refusalOf(() -> monthsOnly.commitScope("commit")).kind()).isEqualTo(RefusalKind.SCOPE_MISSING);
    }

    @Test
    void allThreeVerbsBuildTheMonthsWithNoYearRefusalFromOneSentence() {
        final ScopeArguments monthsOnly = new ScopeArguments(null, "6-8", null);

        assertThat(refusalOf(() -> monthsOnly.sortScope("sort")).sentence())
                .isEqualTo(refusalOf(() -> monthsOnly.cullScope("sort")).sentence())
                .isEqualTo(refusalOf(() -> monthsOnly.commitScope("sort")).sentence())
                .contains("--months narrows a year");
    }

    @Test
    void theMonthsWithNoYearRefusalNamesWhicheverVerbTheCallerTyped() {
        final ScopeArguments monthsOnly = new ScopeArguments(null, "6-8", null);

        assertThat(refusalOf(() -> monthsOnly.sortScope("sort")).sentence()).contains("sort 2019");
        assertThat(refusalOf(() -> monthsOnly.cullScope("sift")).sentence()).contains("sift 2019");
        assertThat(refusalOf(() -> monthsOnly.commitScope("commit")).sentence()).contains("commit 2019");
    }

    @Test
    void aCountIsRefusedOnTheVerbThatMovesFilesRatherThanQuietlyIgnored() {
        final Refusal refusal = refusalOf(() -> new ScopeArguments("2019", null, 30).commitScope("commit"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_CONFLICTING);
        assertThat(refusal.sentence()).contains("never a count");
    }

    @Test
    void aValueThatIsNeitherAYearNorAllIsToldAboutBoth() {
        assertThat(refusalOf(() -> year("ALL").commitScope("commit")).sentence())
                .contains("four digits")
                .contains("commit all");
    }

    @Test
    void anEmptyValueIsNamedRatherThanLeavingAHoleInTheSentence() {
        assertThat(refusalOf(() -> year("").sortScope("sort")).sentence())
                .contains("an empty value")
                .doesNotContain("  ");
        assertThat(refusalOf(() -> new ScopeArguments("2019", "", null).cullScope("sift")).sentence())
                .contains("an empty value")
                .doesNotContain("  ");
    }

    @Test
    void aContiguousListNarrowsAsTheSpanItCovers() {
        assertThat(new ScopeArguments("2019", "6,7,8", null).sortScope("sort"))
                .isEqualTo(new SortScope.Year(2019, new MonthRange(6, 8)));
        assertThat(new ScopeArguments("2019", "6,7,8", null).commitScope("commit"))
                .isEqualTo(new CommitScope.Year(2019, new MonthRange(6, 8)));
    }

    @Test
    void allMovesEveryYearAndTakesNoMonthsToNarrow() {
        assertThat(year("all").commitScope("commit")).isEqualTo(new CommitScope.All());
        assertThat(refusalOf(() -> new ScopeArguments("all", "6-8", null).commitScope("commit")).kind())
                .isEqualTo(RefusalKind.SCOPE_CONFLICTING);
    }

    @Test
    void aNumberThatIsNotFourDigitsIsNotAYearOnAnyVerb() {
        assertThat(refusalOf(() -> year("20199").sortScope("sort")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> year("19").cullScope("sift")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> year("nineteen").commitScope("commit")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void aYearWhoseFolderNameCouldNotBeReadBackIsRefusedBeforeItReachesAScope() {
        assertThat(refusalOf(() -> year("0019").cullScope("sift")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> year("0019").sortScope("sort")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> year("0019").commitScope("commit")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void everyYearThisAcceptsSurvivesTheFolderNameItsSiftIsGiven() {
        final CullScope.Year scope = (CullScope.Year) year("2019").cullScope("sift");

        assertThat(CullScope.yearScopeOf(CullScope.tag(scope))).isEqualTo(scope);
    }

    @Test
    void aCountOfNoPhotosIsRefusedRatherThanRunAgainstNothing() {
        assertThat(refusalOf(() -> oldest(0).sortScope("sort")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> oldest(-1).cullScope("sift")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void aRefusalOverTheWholeScopeCarriesTheVerbAsAFieldAndNotOnlyInItsSentence() {
        assertThat(refusalOf(() -> nothing().cullScope("sift")).detail()).containsEntry("verb", "sift");
        assertThat(refusalOf(() -> nothing().commitScope("commit")).detail()).containsEntry("verb", "commit");
    }

    private static ScopeArguments nothing() {
        return new ScopeArguments(null, null, null);
    }

    private static ScopeArguments year(final String year) {
        return new ScopeArguments(year, null, null);
    }

    private static ScopeArguments oldest(final int count) {
        return new ScopeArguments(null, null, count);
    }

    private static Refusal refusalOf(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(ScopeRefusedException.class);
        return ((ScopeRefusedException) thrown).refusal();
    }
}
