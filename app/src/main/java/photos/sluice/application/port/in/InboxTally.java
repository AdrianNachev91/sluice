package photos.sluice.application.port.in;

/**
 * How much is waiting in the Inbox, counted without dating anything.
 *
 * <p>Photos and videos only, which is the same population a sort would act on. A Takeout export
 * carries one JSON sidecar per photo. Counting every file would roughly double the figure, and
 * none of that second half is a photo anybody is waiting to have sorted.
 *
 * @param files int how many photos and videos the Inbox tree holds
 * @param bytes long what they come to on disk
 */
public record InboxTally(int files, long bytes) {
}
