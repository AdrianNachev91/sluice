package photos.sluice.domain.commit;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.commit.CommitScope.All;
import photos.sluice.domain.commit.CommitScope.Undated;
import photos.sluice.domain.commit.CommitScope.Year;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.paths.SortFolderNames;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a Sorted-relative file path is included in a commit run.
 *
 * <p>A dated path, matching a {@code YYYY/MM} segment pair anywhere in it as produced by Photos and
 * Videos, is scoped by year and month. An undated path such as Funny, which carries no
 * {@code YYYY/MM} segments, is included only when the run isn't narrowed to a specific year.
 */
public final class CommitScopeSelector {

    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})/(\\d{2})/");

    /**
     * Decides whether a Sorted-relative path falls within the given commit scope.
     *
     * @param relativePath {@link String} the Sorted-relative file path
     * @param scope {@link CommitScope} the commit scope to check against
     * @return boolean true if the path is in scope
     */
    public boolean isInScope(final String relativePath, final CommitScope scope) {
        return switch (scope) {
            case All() -> true;
            case Year(final int year, final MonthRange months) -> matchesYear(relativePath, year, months);
            case Undated() -> isUndated(relativePath);
        };
    }

    /**
     * Whether a path sits in the undated folder a rescue fills.
     *
     * <p>Matched on the first segment alone. Something further down happening to carry that name is
     * a folder inside a year, which a year scope already reaches.
     *
     * <p>Case-insensitive, as {@link SortFolderNames#writtenByASort} is. Windows and a stock Mac
     * both hand a hand-made {@code unsorted} the very folder this app writes.
     *
     * @param relativePath {@link String} the Sorted-relative file path
     * @return boolean true where the undated folder directly or indirectly holds it
     */
    private static boolean isUndated(final String relativePath) {
        final int slash = relativePath.indexOf('/');
        return slash > 0 && SortFolderNames.UNDATED.equalsIgnoreCase(relativePath.substring(0, slash));
    }

    /**
     * Checks whether a dated path's year (and optional month range) matches the given scope.
     *
     * @param relativePath {@link String} the Sorted-relative file path
     * @param year int the year to match
     * @param months {@link MonthRange} optional month range narrowing the year
     * @return boolean true if the path's date falls within the year and month range
     */
    private static boolean matchesYear(final String relativePath, final int year, final @Nullable MonthRange months) {
        final Matcher matcher = YEAR_MONTH.matcher(relativePath);
        if (!matcher.find()) {
            // No YYYY/MM segment at all (e.g. Funny) - only in scope via the All branch above,
            // never via a specific-year scope.
            return false;
        }
        final int foundYear = Integer.parseInt(matcher.group(1));
        final int foundMonth = Integer.parseInt(matcher.group(2));
        return foundYear == year && (months == null || months.includes(foundMonth));
    }
}
