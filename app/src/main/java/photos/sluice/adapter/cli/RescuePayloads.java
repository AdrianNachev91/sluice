package photos.sluice.adapter.cli;

import photos.sluice.domain.rescue.RescueSummary;

/**
 * The wire shape for what a rescue run did, and the reading that builds it.
 *
 * <p>Every field comes from what the engine answered. Nothing here parses a message or invents a
 * number. So a caller acts on what the rescue decided rather than on how this surface happened to
 * word it.
 */
public final class RescuePayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private RescuePayloads() {
    }

    /**
     * What one rescue run did.
     *
     * @param rescued int files moved into Sorted under a year and month
     * @param undated int files moved into the undated folder, nothing having dated them
     * @param alreadyInSorted int files deleted, their own bytes already at the destination
     * @param leftBehind int media files the run never reached, still in the folder
     * @param folderRemoved boolean whether the folder was removed
     * @param stopped boolean whether the run gave up before reaching every file
     */
    public record RescuedPayload(int rescued, int undated, int alreadyInSorted, int leftBehind,
                                 boolean folderRemoved, boolean stopped) {
    }

    /**
     * Reads what a rescue run did onto the wire.
     *
     * @param summary {@link RescueSummary} what the run did
     * @return {@link RescuedPayload} its machine-readable shape
     */
    public static RescuedPayload rescued(final RescueSummary summary) {
        return new RescuedPayload(summary.rescued(), summary.undated(), summary.alreadyInSorted(),
                summary.leftBehind(), summary.folderRemoved(), summary.cancelled());
    }
}
