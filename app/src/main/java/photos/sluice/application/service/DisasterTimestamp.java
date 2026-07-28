package photos.sluice.application.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one shared {@code yyyy-MM-dd_HH-mm-ss} (UTC, colon-free, Windows-safe) timestamp convention
 * every disaster-recovery artifact embeds in its own name - {@link DisasterDrawer}'s per-prep-dir
 * entries and {@link ApplyEngine#discard}'s global graveyard folders alike. Centralized so both stay
 * parseable by the same future retention sweep.
 */
final class DisasterTimestamp {

    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    /**
     * Prevents instantiation of this utility class.
     */
    private DisasterTimestamp() {
    }

    /**
     * Formats the current instant using the shared convention.
     *
     * @return {@link String} the formatted timestamp
     */
    static String now() {
        return FORMAT.format(Instant.now());
    }
}
