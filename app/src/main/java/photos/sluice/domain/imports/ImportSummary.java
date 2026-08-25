package photos.sluice.domain.imports;

/**
 * What an import did.
 *
 * <p>Each file walked is counted in exactly one of {@code broughtIn}, {@code alreadyThere},
 * {@code unverified} and {@code couldNotBeRead}. Cancelled, those four fall short of {@code found}
 * by whatever the import never reached.
 *
 * <p>{@code unreadablePlaces} counts folders and stands outside that sum. Nothing inside one of them
 * is in {@code found} at all.
 *
 * @param found int how many files the chosen folders and files came to
 * @param broughtIn int how many landed in the Inbox on this run
 * @param alreadyThere int how many the Inbox already held byte for byte
 * @param unverified int how many arrived with bytes that did not match the original, on a move
 * @param couldNotBeRead int how many the filesystem refused partway through
 * @param unreadablePlaces int how many folders the walk could not look inside at all
 * @param cancelled boolean whether it stopped before reaching every file
 */
public record ImportSummary(int found, int broughtIn, int alreadyThere, int unverified,
                            int couldNotBeRead, int unreadablePlaces, boolean cancelled) {
}
