package photos.sluice.adapter.cli;

import photos.sluice.domain.rescue.RescueSummary;

import java.util.List;

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
     * @param rescued int files promoted into the library or Sorted
     * @param skipped a {@link List} of {@link String} files left in place, with reasons
     * @param folderRemoved boolean whether the Review folder was removed
     * @param stopped boolean whether the run gave up before reaching every file
     */
    public record RescuedPayload(int rescued, List<String> skipped, boolean folderRemoved, boolean stopped) {
    }

    /**
     * Reads what a rescue run did onto the wire.
     *
     * @param summary {@link RescueSummary} what the run did
     * @return {@link RescuedPayload} its machine-readable shape
     */
    public static RescuedPayload rescued(final RescueSummary summary) {
        return new RescuedPayload(summary.rescued(), summary.skipped(), summary.folderRemoved(),
                summary.cancelled());
    }
}
