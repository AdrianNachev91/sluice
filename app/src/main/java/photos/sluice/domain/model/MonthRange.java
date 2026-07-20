package photos.sluice.domain.model;

// A shared value type for narrowing a scoped run to a span of months within a year. Used by both
// SortScope.Year (Inbox selection) and CommitScope.Year (Sorted selection) - neither concept owns
// it, so it lives alongside them in domain/model rather than nested under either.
public record MonthRange(int from, int to) {

    public static MonthRange of(int month) {
        return new MonthRange(month, month);
    }

    public boolean includes(int month) {
        return month >= from && month <= to;
    }
}
