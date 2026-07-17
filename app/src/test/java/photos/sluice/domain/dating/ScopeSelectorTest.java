package photos.sluice.domain.dating;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.Confidence;
import photos.sluice.domain.model.DatedMedia;
import photos.sluice.domain.model.DateResult;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortScope.MonthRange;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeSelectorTest {

    private final ScopeSelector selector = new ScopeSelector();

    @Test
    void yearWithoutMonthsSelectsWholeYear() {
        DatedMedia jan = dated("jan.jpg", 2019, 1, 15);
        DatedMedia dec = dated("dec.jpg", 2019, 12, 1);
        DatedMedia otherYear = dated("other.jpg", 2020, 1, 1);

        List<DatedMedia> result = selector.select(List.of(jan, dec, otherYear), new SortScope.Year(2019, null));

        assertThat(result).containsExactly(jan, dec);
    }

    @Test
    void yearWithMonthRangeNarrowsFurther() {
        DatedMedia inRange = dated("june.jpg", 2019, 6, 15);
        DatedMedia outOfRange = dated("sept.jpg", 2019, 9, 1);
        var scope = new SortScope.Year(2019, new MonthRange(6, 8));

        List<DatedMedia> result = selector.select(List.of(inRange, outOfRange), scope);

        assertThat(result).containsExactly(inRange);
    }

    @Test
    void yearWithSingleMonthViaMonthRangeOf() {
        DatedMedia june = dated("june.jpg", 2019, 6, 15);
        DatedMedia july = dated("july.jpg", 2019, 7, 1);
        var scope = new SortScope.Year(2019, MonthRange.of(6));

        List<DatedMedia> result = selector.select(List.of(june, july), scope);

        assertThat(result).containsExactly(june);
    }

    @Test
    void oldestYearPicksMinYearAcrossMixedYears() {
        DatedMedia oldest = dated("old.jpg", 2015, 3, 1);
        DatedMedia middle = dated("mid.jpg", 2018, 1, 1);
        DatedMedia newest = dated("new.jpg", 2021, 1, 1);

        List<DatedMedia> result = selector.select(List.of(newest, oldest, middle), new SortScope.OldestYear());

        assertThat(result).containsExactly(oldest);
    }

    @Test
    void oldestYearOnEmptyInputReturnsEmpty() {
        List<DatedMedia> result = selector.select(List.of(), new SortScope.OldestYear());

        assertThat(result).isEmpty();
    }

    @Test
    void oldestNTruncatesToNEarliestPreservingStableOrderOnTies() {
        DatedMedia earliest = dated("a.jpg", 2019, 1, 1);
        DatedMedia tiedFirst = dated("b.jpg", 2019, 6, 1);
        DatedMedia tiedSecond = dated("c.jpg", 2019, 6, 1);
        DatedMedia latest = dated("d.jpg", 2020, 1, 1);

        List<DatedMedia> result =
                selector.select(List.of(latest, tiedSecond, earliest, tiedFirst), new SortScope.OldestN(3));

        assertThat(result).containsExactly(earliest, tiedSecond, tiedFirst);
    }

    @Test
    void oldestNLargerThanAvailableInputReturnsAll() {
        DatedMedia only = dated("only.jpg", 2019, 1, 1);

        List<DatedMedia> result = selector.select(List.of(only), new SortScope.OldestN(50));

        assertThat(result).containsExactly(only);
    }

    private static DatedMedia dated(String name, int year, int month, int day) {
        var file = new MediaFile(Path.of(name));
        var date = new DateResult(LocalDateTime.of(year, month, day, 0, 0), Confidence.TRUSTED, "filename");
        return new DatedMedia(file, date);
    }
}
