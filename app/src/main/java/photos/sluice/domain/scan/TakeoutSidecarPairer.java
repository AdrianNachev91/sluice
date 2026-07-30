package photos.sluice.domain.scan;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pairs each media file with the Google Takeout JSON sidecar that describes it. Pairing is scoped
 * per parent directory: a same-named sidecar in a different directory never cross-pairs.
 *
 * <p>Flowchart and naming examples: {@code app/docs/design/domain/scan/takeout-sidecar-pairing.md}.
 */
public final class TakeoutSidecarPairer {

    // Google appends this suffix to some sidecar names ("name.jpg.supplemental-metadata.json");
    // the media it describes is everything before the suffix.
    private static final Pattern SUPPLEMENTAL = Pattern.compile("^(.+?)\\.supplemental.*$", Pattern.CASE_INSENSITIVE);
    // Reverses Google's dup-numbering: sidecar base "name.jpg(1)" describes media "name(1).jpg".
    private static final Pattern SIDECAR_DUP_NUMBERED = Pattern.compile("^(.+)\\.([^.]+)\\((\\d+)\\)$");
    // An edited copy ("name-edited.jpg") isn't exported with its own sidecar - it shares the
    // original's, so this strips the suffix to recover the original's filename as a lookup key.
    private static final Pattern EDITED = Pattern.compile("^(.*?)-edited\\.([^.]+)$", Pattern.CASE_INSENSITIVE);
    // The media-side form of dup-numbering: "name(1).jpg".
    private static final Pattern MEDIA_DUP_NUMBERED = Pattern.compile("^(.*?)(\\(\\d+\\))(\\.[^.]+)$");

    /**
     * The outcome of one {@link TakeoutSidecarPairer#pair} call.
     *
     * <p>{@code takeoutMode} is true if any sidecar JSON was found at all. {@code sidecarsByMedia}
     * holds the sidecar path resolved for each media path that got one.
     */
    public record PairingResult(boolean takeoutMode, Map<Path, Path> sidecarsByMedia) {
        /**
         * Defensively copies the sidecar-by-media map.
         *
         * @param takeoutMode boolean true if any sidecar JSON was found at all
         * @param sidecarsByMedia a {@link Map} of {@link Path} to {@link Path} sidecar path keyed by the media path
         * it describes
         */
        public PairingResult {
            sidecarsByMedia = Map.copyOf(sidecarsByMedia);
        }
    }

    /**
     * Pairs each media file with the Takeout JSON sidecar that describes it, scoped per directory.
     *
     * @param mediaPaths a {@link List} of {@link Path} media file paths to pair
     * @param jsonPaths a {@link List} of {@link Path} sidecar JSON paths available for pairing
     * @return {@link PairingResult} the pairing result
     */
    public PairingResult pair(final List<Path> mediaPaths, final List<Path> jsonPaths) {
        final boolean takeoutMode = !jsonPaths.isEmpty();

        // Two passes: first index every sidecar in a directory by the media filename it
        // describes (built once, independent of how many media files there are), then look each
        // media file up against that index. This keeps pairing O(sidecars + media) instead of
        // O(sidecars x media), and lets every media file share the same per-directory index
        // rather than re-deriving owner keys per lookup.
        final Map<Path, List<Path>> jsonsByDir = jsonPaths.stream()
                .collect(Collectors.groupingBy(TakeoutSidecarPairer::directoryKeyOf, LinkedHashMap::new,
                        Collectors.toList()));
        final Map<Path, Map<String, Path>> ownersByDir = new HashMap<>();
        jsonsByDir.forEach((dir, sidecars) -> {
            final Map<String, Path> owners = new LinkedHashMap<>();
            for (final Path json : sidecars) {
                // First sidecar to claim an owner key wins; a second sidecar deriving the same
                // key (rare, e.g. two differently-suffixed sidecars for one photo) is ignored
                // rather than overwriting the first match.
                owners.putIfAbsent(ownerKeyOf(json).toLowerCase(Locale.ROOT), json);
            }
            ownersByDir.put(dir, owners);
        });

        final Map<Path, Path> sidecarsByMedia = new LinkedHashMap<>();
        for (final Path media : mediaPaths) {
            final Path dir = directoryKeyOf(media);
            final String fileName = media.getFileName().toString();
            // Try the fast exact index lookup first; only fall back to scanning every sidecar in
            // the directory by prefix when the index has no entry for this filename at all.
            Path matched = matchByOwnerKey(ownersByDir.get(dir), fileName);
            if (matched == null) {
                matched = prefixFallback(jsonsByDir.get(dir), fileName);
            }
            if (matched != null) {
                sidecarsByMedia.put(media, matched);
            }
        }
        return new PairingResult(takeoutMode, sidecarsByMedia);
    }

    /**
     * A root-level path has no parent to scope pairing by; fall back to the path itself so it
     * groups with nothing rather than throwing (Collectors.groupingBy rejects a null key).
     * Package-visible: SidecarSweep reuses this to scope its own orphan check per directory.
     *
     * @param path {@link Path} the path to derive a directory scope key from
     * @return {@link Path} the parent directory, or the path itself if it has none
     */
    static Path directoryKeyOf(final Path path) {
        final Path parent = path.getParent();
        return parent != null ? parent : path;
    }

    /**
     * Derives the media filename a sidecar describes from its base name (json extension already
     * stripped). Public: SidecarSweep reuses this so the sweep's "which media does this sidecar
     * belong to" derivation never drifts from the pairer's, and a test reuses it too, for the same
     * reason - a hand-duplicated copy already drifted out of sync once.
     *
     * @param json {@link Path} the sidecar JSON path
     * @return {@link String} the owner key identifying the media file it describes
     */
    public static String ownerKeyOf(final Path json) {
        final String base = stripJsonExtension(json.getFileName().toString());
        final Matcher supplemental = SUPPLEMENTAL.matcher(base);
        if (supplemental.matches()) {
            return supplemental.group(1);
        }
        final Matcher dup = SIDECAR_DUP_NUMBERED.matcher(base);
        if (dup.matches()) {
            return dup.group(1) + "(" + dup.group(3) + ")." + dup.group(2);
        }
        return base;
    }

    /**
     * Tries the media's own filename first, then its edited-suffix-stripped form - an edited
     * copy has no sidecar of its own, so it must be looked up under its original's key instead.
     *
     * @param owners a {@link Map} of {@link String} to {@link Path} owner key to sidecar path index for the media's
     * directory
     * @param mediaFileName {@link String} the media file's own filename
     * @return {@link Path} the matching sidecar, if any
     */
    private static @Nullable Path matchByOwnerKey(final @Nullable Map<String, Path> owners,
                                                  final String mediaFileName) {
        if (owners == null) {
            return null;
        }
        Path hit = owners.get(mediaFileName.toLowerCase(Locale.ROOT));
        if (hit != null) {
            return hit;
        }
        final Matcher edited = EDITED.matcher(mediaFileName);
        if (edited.matches()) {
            final String editedBase = edited.group(1) + "." + edited.group(2);
            hit = owners.get(editedBase.toLowerCase(Locale.ROOT));
        }
        return hit;
    }

    /**
     * Fallback for when no owner key matches exactly: prefix-match the dir's sidecar base names
     * against the media name's candidate prefixes (plain name, then dup/edited variants), in
     * priority order, taking the first prefix with any hit and the shortest-matching sidecar
     * among that prefix's hits - covers non-standard sidecar naming the owner-key derivation
     * above doesn't land on exactly.
     *
     * @param dirSidecars a {@link List} of {@link Path} sidecar paths in the media's directory
     * @param mediaFileName {@link String} the media file's own filename
     * @return {@link Path} the matching sidecar, if any
     */
    private static @Nullable Path prefixFallback(final @Nullable List<Path> dirSidecars, final String mediaFileName) {
        if (dirSidecars == null) {
            return null;
        }
        for (final String prefix : candidatePrefixes(mediaFileName)) {
            final Path hit = shortestStartingWith(dirSidecars, prefix);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Same candidate identities as matchByOwnerKey (plain name, then edited-stripped), but each
     * also gets its dup-numbering-reversed form here: owner keys built by ownerKeyOf are already
     * normalized to this form, but a sidecar's base name on disk is not, so a prefix scan against
     * raw base names needs the reversal applied to the search term instead.
     *
     * @param mediaFileName {@link String} the media file's own filename
     * @return a {@link List} of {@link String} the candidate prefixes to try against sidecar base names, in priority
     * order
     */
    private static List<String> candidatePrefixes(final String mediaFileName) {
        final List<String> prefixes = new ArrayList<>();
        prefixes.add(mediaFileName);
        addDupReversedForm(prefixes, mediaFileName);
        final Matcher edited = EDITED.matcher(mediaFileName);
        if (edited.matches()) {
            final String editedBase = edited.group(1) + "." + edited.group(2);
            prefixes.add(editedBase);
            addDupReversedForm(prefixes, editedBase);
        }
        return prefixes;
    }

    /**
     * Appends the dup-numbering-reversed form of a filename to the candidate list, if it matches.
     *
     * @param prefixes a {@link List} of {@link String} the candidate list to append to
     * @param mediaFileName {@link String} the filename to reverse the dup-numbering of
     */
    private static void addDupReversedForm(final List<String> prefixes, final String mediaFileName) {
        final Matcher dup = MEDIA_DUP_NUMBERED.matcher(mediaFileName);
        if (dup.matches()) {
            prefixes.add(dup.group(1) + dup.group(3) + dup.group(2));
        }
    }

    /**
     * Among sidecars whose base name starts with this prefix, the shortest is the closest match
     * to the prefix itself - a longer one is more likely to be an unrelated sidecar that merely
     * happens to share the same leading characters.
     *
     * @param dirSidecars a {@link List} of {@link Path} sidecar paths in the media's directory
     * @param prefix {@link String} the prefix to match sidecar base names against
     * @return {@link Path} the shortest matching sidecar, if any
     */
    private static @Nullable Path shortestStartingWith(final List<Path> dirSidecars, final String prefix) {
        Path best = null;
        int bestLength = Integer.MAX_VALUE;
        for (final Path json : dirSidecars) {
            final String base = stripJsonExtension(json.getFileName().toString());
            if (base.length() < bestLength && base.regionMatches(true, 0, prefix, 0, prefix.length())) {
                best = json;
                bestLength = base.length();
            }
        }
        return best;
    }

    /**
     * Strips a trailing ".json" extension, case-insensitively.
     *
     * @param name {@link String} the filename to strip
     * @return {@link String} the name without its ".json" extension, or unchanged if it has none
     */
    private static String stripJsonExtension(final String name) {
        return name.length() >= 5 && name.regionMatches(true, name.length() - 5, ".json", 0, 5)
                ? name.substring(0, name.length() - 5)
                : name;
    }
}
