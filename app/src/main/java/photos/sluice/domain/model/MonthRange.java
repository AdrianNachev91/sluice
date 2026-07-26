package photos.sluice.domain.model;

// A shared value type for narrowing a scoped run to a span of months within a year. Used by both
// SortScope.Year (Inbox selection) and CommitScope.Year (Sorted selection) - neither concept owns
// it, so it lives alongside them in domain/model rather than nested under either.
public record MonthRange(int from, int to) {

    /**
     * Creates a range spanning a single month.
     *
     * @param month int the month to wrap
     * @return {@link MonthRange} a range whose from and to are both month
     */
    public static MonthRange of(int month) {
        return new MonthRange(month, month);
    }

    /**
     * Checks whether the given month falls within this range.
     *
     * @param month int the month to check
     * @return boolean true if month is within from and to inclusive
     */
    public boolean includes(int month) {
        return month >= from && month <= to;
    }
}
