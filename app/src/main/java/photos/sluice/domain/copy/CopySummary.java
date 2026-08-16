package photos.sluice.domain.copy;

/**
 * What one tree copy did.
 *
 * <p>Cancellation is reported rather than thrown, because a cancelled copy is a normal outcome the
 * user asked for. What it is not is a completed one. A caller acting on completion has to tell the
 * two apart from the same return value.
 *
 * @param filesCopied int how many files were written at the destination
 * @param filesFound int how many files the source held when the copy started
 * @param cancelled boolean true when the copy stopped early because cancellation was requested
 */
public record CopySummary(int filesCopied, int filesFound, boolean cancelled) {
}
