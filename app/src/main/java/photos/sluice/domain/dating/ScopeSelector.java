package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.DatedMedia;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortScope.MonthRange;
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

    public List<DatedMedia> select(List<DatedMedia> allDated, SortScope scope) {
        return switch (scope) {
            case Year(int year, MonthRange months) -> selectYear(allDated, year, months);
            case OldestN(int n) -> selectOldestN(allDated, n);
            case OldestYear() -> selectOldestYear(allDated);
        };
    }

    private static List<DatedMedia> selectYear(List<DatedMedia> allDated, int year, @Nullable MonthRange months) {
        return allDated.stream()
                .filter(dated -> dated.date().when().getYear() == year)
                .filter(dated -> months == null || months.includes(dated.date().when().getMonthValue()))
                .toList();
    }

    private static List<DatedMedia> selectOldestN(List<DatedMedia> allDated, int n) {
        return allDated.stream()
                .sorted(Comparator.comparing(dated -> dated.date().when()))
                .limit(n)
                .toList();
    }

    private static List<DatedMedia> selectOldestYear(List<DatedMedia> allDated) {
        OptionalInt oldestYear = allDated.stream().mapToInt(dated -> dated.date().when().getYear()).min();
        return oldestYear.isPresent() ? selectYear(allDated, oldestYear.getAsInt(), null) : List.of();
    }
}
