package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.DatedMedia;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortScope.OldestN;
import photos.sluice.domain.model.SortScope.OldestYear;
import photos.sluice.domain.model.SortScope.Year;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

// Selects the subset of already-dated media a sort run should process. Uses every record's date
// regardless of confidence - even an UNSORTABLE one still carries a date. Plausibility only
// affects routing later, not which files are in scope.
public final class ScopeSelector {

    /**
     * Selects the subset of dated media in scope for a sort run.
     *
     * @param allDated a {@link List} of {@link DatedMedia} every dated media file under consideration
     * @param scope {@link SortScope} the sort scope to select against
     * @return a {@link List} of {@link DatedMedia} the media files selected by the scope
     */
    public List<DatedMedia> select(List<DatedMedia> allDated, SortScope scope) {
        return switch (scope) {
            case Year(int year, MonthRange months) -> selectYear(allDated, year, months);
            case OldestN(int n) -> selectOldestN(allDated, n);
            case OldestYear() -> selectOldestYear(allDated);
        };
    }

    /**
     * Selects the media dated within a given year, optionally narrowed to a month range.
     *
     * @param allDated a {@link List} of {@link DatedMedia} every dated media file under consideration
     * @param year int the year to select
     * @param months {@link MonthRange} the month range to narrow to, or null for the whole year
     * @return a {@link List} of {@link DatedMedia} the media files in scope
     */
    private static List<DatedMedia> selectYear(List<DatedMedia> allDated, int year, @Nullable MonthRange months) {
        return allDated.stream()
                .filter(dated -> dated.date().when().getYear() == year)
                .filter(dated -> months == null || months.includes(dated.date().when().getMonthValue()))
                .toList();
    }

    /**
     * Selects the n oldest-dated media files.
     *
     * @param allDated a {@link List} of {@link DatedMedia} every dated media file under consideration
     * @param n int how many of the oldest files to select
     * @return a {@link List} of {@link DatedMedia} the n oldest media files
     */
    private static List<DatedMedia> selectOldestN(List<DatedMedia> allDated, int n) {
        return allDated.stream()
                .sorted(Comparator.comparing(dated -> dated.date().when()))
                .limit(n)
                .toList();
    }

    /**
     * Selects every media file dated within the oldest year present in the set.
     *
     * @param allDated a {@link List} of {@link DatedMedia} every dated media file under consideration
     * @return a {@link List} of {@link DatedMedia} the media files in the oldest year, or an empty list if none are dated
     */
    private static List<DatedMedia> selectOldestYear(List<DatedMedia> allDated) {
        OptionalInt oldestYear = allDated.stream().mapToInt(dated -> dated.date().when().getYear()).min();
        return oldestYear.isPresent() ? selectYear(allDated, oldestYear.getAsInt(), null) : List.of();
    }
}
