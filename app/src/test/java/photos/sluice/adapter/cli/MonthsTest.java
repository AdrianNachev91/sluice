package photos.sluice.adapter.cli;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import photos.sluice.adapter.ScopeMonthCases;
import photos.sluice.domain.model.MonthRange;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MonthsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("monthTextBothSurfacesRead")
    void thisSurfaceReadsTheMonthTextTheTableSays(final ScopeMonthCases.Case expected) {
        if (expected.refused()) {
            assertThat(catchThrowable(() -> Months.of(expected.typed()))).isNotNull();
        } else {
            assertThat(Months.of(expected.typed())).isEqualTo(expected.months());
        }
    }

    @Test
    void aSpanNamesEveryMonthBetweenItsEnds() {
        assertThat(Months.of("6-8")).containsExactly(6, 7, 8);
    }

    @Test
    void aListNamesOnlyTheMonthsWrittenInIt() {
        assertThat(Months.of("6,8,11")).containsExactly(6, 8, 11);
    }

    @Test
    void oneMonthOnItsOwnIsBothSpellingsWrittenShort() {
        assertThat(Months.of("6")).containsExactly(6);
        assertThat(Months.of("6-6")).containsExactly(6);
    }

    @Test
    void theMonthsComeBackSortedAndEachOfThemOnce() {
        assertThat(Months.of("11,6,6,8")).containsExactly(6, 8, 11);
    }

    @Test
    void aSpanRunningBackwardsIsRefusedRatherThanTurnedAround() {
        assertThat(refusalOf(() -> Months.of("8-6")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void aNumberTooLargeToHoldIsRefusedRatherThanOverflowingTheParse() {
        assertThat(refusalOf(() -> Months.of("99999999999")).kind())
                .isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void aNumberOutsideTheCalendarIsNotAMonth() {
        assertThat(refusalOf(() -> Months.of("0")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("13")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("6,13")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void somethingThatIsNotDigitsIsNotMonths() {
        assertThat(refusalOf(() -> Months.of("summer")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("6,")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void aListMayHoldSpans() {
        assertThat(Months.of("6-8,11")).containsExactly(6, 7, 8, 11);
        assertThat(Months.of("1,3-5,12")).containsExactly(1, 3, 4, 5, 12);
    }

    @Test
    void aValueThatIsNotMonthsIsQuotedBackWithBothSpellingsThatWouldWork() {
        final Refusal refusal = refusalOf(() -> Months.of("summer"));

        assertThat(refusal.sentence()).contains("summer").contains("6-8").contains("6,8,11");
        assertThat(refusal.detail()).containsEntry("option", "--months").containsEntry("value", "summer");
    }

    @Test
    void aSpanShapedValueThatIsNotTwoEndsIsNotASpan() {
        assertThat(refusalOf(() -> Months.of("6-8-10")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("-8")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
        assertThat(refusalOf(() -> Months.of("6-")).kind()).isEqualTo(RefusalKind.SCOPE_VALUE_REFUSED);
    }

    @Test
    void monthsWithNoGapInThemBecomeTheSpanFromTheFirstToTheLast() {
        assertThat(Months.spanOf("6-8", "sort")).isEqualTo(new MonthRange(6, 8));
        assertThat(Months.spanOf("6,7,8", "sort")).isEqualTo(new MonthRange(6, 8));
        assertThat(Months.spanOf("6", "sort")).isEqualTo(new MonthRange(6, 6));
    }

    @Test
    void monthsWithAGapAreRefusedRatherThanWidenedToCoverIt() {
        final Refusal refusal = refusalOf(() -> Months.spanOf("6,8,11", "sort"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.MONTHS_NOT_A_SPAN);
        assertThat(refusal.detail()).containsEntry("verb", "sort").containsEntry("months", List.of(6, 8, 11));
    }

    @Test
    void theRefusalForAGapNamesTheVerbThatCouldNotTakeIt() {
        assertThat(refusalOf(() -> Months.spanOf("6,8", "commit")).sentence())
                .contains("commit")
                .doesNotContain("sort");
    }

    @Test
    void anEmptyValueIsNamedRatherThanLeavingAHoleInTheSentence() {
        assertThat(refusalOf(() -> Months.of("")).sentence())
                .contains("an empty value")
                .doesNotContain("  ");
    }

    @Test
    void aGappedSetIsRefusedByTheVerbThatNeedsASpanRatherThanByTheReading() {
        assertThat(Months.of("6-8,11")).containsExactly(6, 7, 8, 11);
        assertThat(refusalOf(() -> Months.spanOf("6-8,11", "move")).kind())
                .isEqualTo(RefusalKind.MONTHS_NOT_A_SPAN);
    }

    private static Stream<ScopeMonthCases.Case> monthTextBothSurfacesRead() {
        return ScopeMonthCases.all().stream();
    }

    private static Refusal refusalOf(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(ScopeRefusedException.class);
        return ((ScopeRefusedException) thrown).refusal();
    }
}
