package photos.sluice.domain.sift;

import photos.sluice.domain.model.Numerals;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows a prep directory's own filename conventions. It maps a montage id to its
 * shard file and back. It also tells a montage's contact-sheet image apart from the sidecar that
 * shares its prefix.
 *
 * <p>Domain, not adapter. An adapter (JsonSiftPrepStore, the vision sieves) and the application
 * layer both need these same conventions. The application layer cannot depend on an adapter
 * package, so they live here. Pure string logic throughout, with no filesystem access.
 */
public final class MontageNaming {

    // Recovers a montage id's number from its own sidecar filename. That direction exists because
    // surviving sidecars are the only remaining record of which montages a prep dir held.
    private static final Pattern SIDECAR_NAME = Pattern.compile("^montage-(\\d+)\\.json$");

    // The shape an id read off disk is held to. Deliberately wider than what montageIdFor()
    // produces, which pads to at least three digits. An id becomes a filename under the prep dir
    // through shardFileFor(). One of those names a move destination, so an id is a path segment as
    // much as an identifier.
    private static final Pattern MONTAGE_ID = Pattern.compile("montage-\\d+");

    /**
     * Prevents instantiation of this utility class.
     */
    private MontageNaming() {
    }

    /**
     * Whether a string is a montage id this app could have produced.
     *
     * @param montage {@link String} the candidate montage id
     * @return boolean true if montage is a well-formed id
     */
    public static boolean isMontageId(final String montage) {
        return MONTAGE_ID.matcher(montage).matches();
    }

    /**
     * Derives a montage id's shard filename.
     *
     * @param montage {@link String} the montage id (e.g. montage-003)
     * @return {@link String} the corresponding shard filename
     */
    public static String shardFileFor(final String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }

    /**
     * Derives a montage id from its 1-based number, zero-padded to the usual three digits.
     *
     * @param number int the montage's 1-based number
     * @return {@link String} the montage id (e.g. montage-003)
     */
    public static String montageIdFor(final int number) {
        return "montage-" + Numerals.padded(number, 3);
    }

    /**
     * Reads a sidecar filename's own montage number back out of it.
     *
     * @param fileName {@link String} a file's own leaf name
     * @return {@link OptionalInt} the montage number, or empty if fileName is not a sidecar
     */
    public static OptionalInt sidecarMontageNumber(final String fileName) {
        final Matcher matcher = SIDECAR_NAME.matcher(fileName);
        return matcher.matches() ? OptionalInt.of(Integer.parseInt(matcher.group(1))) : OptionalInt.empty();
    }

    /**
     * Whether name is a montage contact-sheet or tile image, as opposed to a montage's own sidecar.
     * The sidecar shares the "montage-" prefix but is JSON, so the extension has to be checked too.
     *
     * @param name {@link String} a file's own leaf name
     * @return boolean true if name is a montage/tile image
     */
    public static boolean isMontageImage(final String name) {
        return name.startsWith("tile-") || (name.startsWith("montage-") && !name.endsWith(".json"));
    }

    /**
     * Whether name is one montage's own decision shard ({@code decisions-NNN.json}). The merged
     * {@code decisions.json} a completed apply writes has no hyphen before the extension, so it
     * never matches.
     *
     * @param name {@link String} a file's own leaf name
     * @return boolean true if name is a montage decision shard
     */
    public static boolean isShardFile(final String name) {
        return name.startsWith("decisions-") && name.endsWith(".json");
    }
}
