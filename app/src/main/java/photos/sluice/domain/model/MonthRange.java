package photos.sluice.domain.model;

import photos.sluice.domain.commit.CommitScope;

/**
 * A shared value type for narrowing a scoped run to a span of months within a year. Both
 * {@link SortScope.Year} (Inbox selection) and {@link CommitScope.Year} (Sorted selection) use it.
 * Neither concept owns it, so it lives alongside them in {@code domain.model} rather than nested
 * under either.
 */
public record MonthRange(int from, int to) {

    /**
     * Creates a range spanning a single month.
     *
     * @param month int the month to wrap
     * @return {@link MonthRange} a range whose from and to are both month
     */
    public static MonthRange of(final int month) {
        return new MonthRange(month, month);
    }

    /**
     * Checks whether the given month falls within this range.
     *
     * @param month int the month to check
     * @return boolean true if month is within from and to inclusive
     */
    public boolean includes(final int month) {
        return month >= from && month <= to;
    }
}
