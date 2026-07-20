package photos.sluice.domain.commit;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.commit.CommitScope.All;
import photos.sluice.domain.commit.CommitScope.Year;
import photos.sluice.domain.model.MonthRange;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Decides whether a Sorted-relative file path is included in a commit run. A dated path (matching
// a YYYY/MM segment pair anywhere in it, as produced by Photos/Videos) is scoped by year and
// month. An undated path (Funny, which carries no YYYY/MM segments) is included only when the run
// isn't narrowed to a specific year.
public final class CommitScopeSelector {

    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})/(\\d{2})/");

    public boolean isInScope(String relativePath, CommitScope scope) {
        return switch (scope) {
            case All() -> true;
            case Year(int year, MonthRange months) -> matchesYear(relativePath, year, months);
        };
    }

    private static boolean matchesYear(String relativePath, int year, @Nullable MonthRange months) {
        Matcher matcher = YEAR_MONTH.matcher(relativePath);
        if (!matcher.find()) {
            // No YYYY/MM segment at all (e.g. Funny) - only in scope via the All branch above,
            // never via a specific-year scope.
            return false;
        }
        int foundYear = Integer.parseInt(matcher.group(1));
        int foundMonth = Integer.parseInt(matcher.group(2));
        return foundYear == year && (months == null || months.includes(foundMonth));
    }
}
