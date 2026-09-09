package photos.sluice.adapter.cli;

import photos.sluice.domain.imports.ImportSummary;

/**
 * The wire shape for what an import did, and the reading that builds it.
 *
 * <p>Every field comes from what the engine answered. Nothing here parses a message or invents a
 * number. So a caller acts on what the import decided rather than on how this surface happened to
 * word it.
 */
public final class ImportPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private ImportPayloads() {
    }

    /**
     * What one import did.
     *
     * @param found int how many files the chosen folders and files came to
     * @param broughtIn int how many landed in the Inbox on this run
     * @param alreadyThere int how many the Inbox already held byte for byte
     * @param unverified int how many arrived with bytes that did not match the original, on a move
     * @param couldNotBeRead int how many the filesystem refused partway through
     * @param unreadablePlaces int how many folders the walk could not look inside at all
     * @param stopped boolean whether the run gave up before reaching every file
     */
    public record ImportedPayload(int found, int broughtIn, int alreadyThere, int unverified,
                                  int couldNotBeRead, int unreadablePlaces, boolean stopped) {
    }

    /**
     * Reads what an import did onto the wire.
     *
     * @param summary {@link ImportSummary} what the run did
     * @return {@link ImportedPayload} its machine-readable shape
     */
    public static ImportedPayload imported(final ImportSummary summary) {
        return new ImportedPayload(summary.found(), summary.imported(), summary.alreadyInInbox(),
                summary.unverified(), summary.unreadableFiles(), summary.unreadableFolders(), summary.cancelled());
    }
}
