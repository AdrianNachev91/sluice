package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.adapter.ScopeMonthCases;
import photos.sluice.adapter.ui.RunScopeText.ParsedScope;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// The field this reads starts paid work, so what it accepts decides what a press spends. Every case
// below is about the boundary between a scope the reader typed and one they did not.
class RunScopeTextTest {

    @Test
    void aBareYearMeansTheWholeYear() {
        assertThat(RunScopeText.parse("2019")).isEqualTo(new ParsedScope.OfYear(2019, List.of()));
    }

    @Test
    void theMonthsAreSortedAndSaidOnlyOnce() {
        assertThat(RunScopeText.parse("2019 8,6,6-7"))
                .isEqualTo(new ParsedScope.OfYear(2019, List.of(6, 7, 8)));
    }

    // A comma with nothing after it is the case with money behind it. Read as the bare year, it
    // starts a sift of all twelve months from a keystroke the reader did not mean to leave there.
    @ParameterizedTest
    @ValueSource(strings = {"2019 ,", "2019 6,", "2019 ,6", "2019 6,,7", "2019 ,,"})
    void anEmptyMonthIsRefusedWhicheverSideOfTheCommaItIsOn(final String typed) {
        assertThat(RunScopeText.parse(typed)).isInstanceOf(ParsedScope.Refused.class);
    }

    @Test
    void aStrayCommaDoesNotQuietlyBecomeTheWholeYear() {
        assertThat(RunScopeText.parse("2019 ,")).isNotEqualTo(RunScopeText.parse("2019"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("monthTextBothSurfacesRead")
    void thisSurfaceReadsTheMonthTextTheTableSays(final ScopeMonthCases.Case expected) {
        final ParsedScope read = RunScopeText.parse("2019 " + expected.typed());

        if (expected.refused()) {
            assertThat(read).isInstanceOf(ParsedScope.Refused.class);
        } else {
            assertThat(read).isEqualTo(new ParsedScope.OfYear(2019, expected.months()));
        }
    }

    @Test
    void anEmptyFieldIsNotARefusal() {
        assertThat(RunScopeText.parse("   ")).isInstanceOf(ParsedScope.Blank.class);
    }

    @Test
    void theUndatedFolderIsNamedWhateverTheCase() {
        assertThat(RunScopeText.parse(RunScopeText.UNDATED.toUpperCase(Locale.ROOT)))
                .isInstanceOf(ParsedScope.Undated.class);
        assertThat(RunScopeText.parse(RunScopeText.UNDATED.toLowerCase(Locale.ROOT)))
                .isInstanceOf(ParsedScope.Undated.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"201", "20190", "twenty nineteen", "2019-06"})
    void anythingThatIsNotAFourDigitYearIsRefused(final String typed) {
        assertThat(RunScopeText.parse(typed)).isInstanceOf(ParsedScope.Refused.class);
    }

    @Test
    void monthsWithAGapAreNotAContiguousRun() {
        assertThat(RunScopeText.contiguous(List.of(6, 7, 8))).isTrue();
        assertThat(RunScopeText.contiguous(List.of(6, 8))).isFalse();
    }

    private static Stream<ScopeMonthCases.Case> monthTextBothSurfacesRead() {
        return ScopeMonthCases.all().stream();
    }
}
