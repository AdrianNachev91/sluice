package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RunWordsTest {

    @Test
    void somethingNobodyCouldStatIsNotKnownRatherThanADateIn1970() {
        assertThat(RunWords.howLongAgo(Instant.EPOCH)).isEqualTo("not known");
    }

    @Test
    void withinTheHourIsSaidWithoutANumber() {
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofMinutes(59)))).isEqualTo("less than an hour ago");
    }

    @Test
    void anHourInIsCountedInHoursUntilTheDayTurns() {
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofHours(1)))).isEqualTo("1 hour ago");
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofHours(23)))).isEqualTo("23 hours ago");
    }

    @Test
    void aDayInIsCountedInDaysUpToTwoMonths() {
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(1)))).isEqualTo("1 day ago");
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(59)))).isEqualTo("59 days ago");
    }

    @Test
    void twoMonthsInIsCountedInMonthsUpToTwoYears() {
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(60)))).isEqualTo("2 months ago");
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(729)))).isEqualTo("24 months ago");
    }

    @Test
    void twoYearsInIsCountedInYears() {
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(730)))).isEqualTo("2 years ago");
        assertThat(RunWords.howLongAgo(agoBy(Duration.ofDays(2555)))).isEqualTo("7 years ago");
    }

    // Half a minute back from the boundary, so the elapsed time this reads has not crossed it by
    // the time howLongAgo asks. Every case here is far enough from its neighbour to absorb that.
    private static Instant agoBy(final Duration elapsed) {
        return Instant.now().minus(elapsed).minusSeconds(30);
    }
}
