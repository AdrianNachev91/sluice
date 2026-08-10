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
    // Google's dup-numbering always lands at the very end of the sidecar's base name, after any
    // suffix. It reads "name.jpg(1)" in the older shape and "name.jpg.supplemental-metadata(1)" in
    // the newer one. So it is lifted off first, and put back once the media filename underneath is
    // known.
    private static final Pattern SIDECAR_DUP_NUMBERED = Pattern.compile("^(.*)\\((\\d+)\\)$");
    // An edited copy ("name-edited.jpg") isn't exported with its own sidecar. It shares the
    // original's, so this strips the suffix to recover the original's filename as a lookup key.
    private static final Pattern EDITED = Pattern.compile("^(.*?)-edited\\.([^.]+)$", Pattern.CASE_INSENSITIVE);
    // The media-side form of dup-numbering: "name(1).jpg".
    private static final Pattern MEDIA_DUP_NUMBERED = Pattern.compile("^(.*?)(\\(\\d+\\))(\\.[^.]+)$");
    // A dot followed by a run of word characters, which is the shape of a filename extension. It
    // stops at anything else, so it reads "jpg" out of both "photo.jpg" and "photo.jpg(1)".
    private static final Pattern EXTENSION_COMPONENT = Pattern.compile("\\.(\\w+)");

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
        final Map<Path, Map<String, List<Path>>> ownersByDir = new HashMap<>();
        jsonsByDir.forEach((dir, sidecars) -> {
            // Every sidecar deriving a given lowercased key is kept, not just the first. Two
            // sidecars can land on one key for two different reasons. Either they are genuinely
            // differently-suffixed sidecars for one photo, or two distinct media files whose
            // names differ only in case. Only bestOwnerMatch, which sees the queried filename's
            // own case, can tell those apart.
            final Map<String, List<Path>> owners = new LinkedHashMap<>();
            for (final Path json : sidecars) {
                owners.computeIfAbsent(ownerKeyOf(json).toLowerCase(Locale.ROOT), _ -> new ArrayList<>()).add(json);
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
        final Matcher dup = SIDECAR_DUP_NUMBERED.matcher(base);
        final boolean numbered = dup.matches();
        final String withoutDupNumber = numbered ? dup.group(1) : base;

        final Matcher supplemental = SUPPLEMENTAL.matcher(withoutDupNumber);
        final String mediaName = supplemental.matches() ? supplemental.group(1) : withoutDupNumber;
        return numbered ? withDupNumberBeforeExtension(mediaName, dup.group(2)) : mediaName;
    }

    /**
     * Whether a {@code .json} file names a media file at all. A per-photo sidecar's owner key is
     * built from a media filename, so somewhere in it sits a recognized media extension. A file
     * like {@code metadata.json} or {@code notes.json} derives an owner key with no such component,
     * and could never have described a photo.
     *
     * <p>The extension is looked for anywhere in the owner key rather than only at its end. A
     * sidecar carrying a non-standard suffix keeps its media extension in the middle
     * ({@code IMG_1234.jpg.someextra}), and that is a real sidecar the sweep is meant to reach.
     *
     * <p>The distinction matters because the orphan sweep deletes what it decides is a spent
     * sidecar. An export manifest or an unrelated app's JSON must never enter that decision at all.
     * A truncated name cut back past its media extension fails this check too, and is left on disk.
     * That is the safe direction: the cost is a stale sidecar, not a deleted file.
     *
     * @param json {@link Path} the JSON file to inspect
     * @return boolean true if the owner key it derives names a recognized media file
     */
    public static boolean looksLikeMediaSidecar(final Path json) {
        final Matcher components = EXTENSION_COMPONENT.matcher(ownerKeyOf(json));
        boolean found = false;
        while (!found && components.find()) {
            found = MediaTypeDetector.isRecognizedExtension(components.group(1));
        }
        return found;
    }

    /**
     * Moves a dup number to where the media file carries it. Google numbers the sidecar at the end
     * of its whole name, while the media file it describes is numbered before its extension.
     *
     * @param mediaName {@link String} the media filename recovered from the sidecar's base name
     * @param dupNumber {@link String} the duplicate counter's digits, without their brackets
     * @return {@link String} the media filename with the dup number in its own position
     */
    private static String withDupNumberBeforeExtension(final String mediaName, final String dupNumber) {
        final String numbering = "(" + dupNumber + ")";
        final int dot = mediaName.lastIndexOf('.');
        return dot < 0 ? mediaName + numbering
                : mediaName.substring(0, dot) + numbering + mediaName.substring(dot);
    }

    /**
     * Tries the media's own filename first, then its edited-suffix-stripped form - an edited
     * copy has no sidecar of its own, so it must be looked up under its original's key instead.
     *
     * @param owners a {@link Map} of {@link String} to {@link List} of {@link Path}, owner key to the sidecars
     * deriving it, for the media's directory
     * @param mediaFileName {@link String} the media file's own filename
     * @return {@link Path} the matching sidecar, if any
     */
    private static @Nullable Path matchByOwnerKey(final @Nullable Map<String, List<Path>> owners,
                                                  final String mediaFileName) {
        if (owners == null) {
            return null;
        }
        Path hit = bestOwnerMatch(owners.get(mediaFileName.toLowerCase(Locale.ROOT)), mediaFileName);
        if (hit != null) {
            return hit;
        }
        final Matcher edited = EDITED.matcher(mediaFileName);
        if (edited.matches()) {
            final String editedBase = edited.group(1) + "." + edited.group(2);
            hit = bestOwnerMatch(owners.get(editedBase.toLowerCase(Locale.ROOT)), editedBase);
        }
        return hit;
    }

    /**
     * Picks the right sidecar among those sharing one lowercased owner key. A lone candidate is
     * returned regardless of its own case - the existing tolerance for a sidecar whose casing
     * genuinely differs from its media's. Among several, the one whose raw owner key matches the
     * queried filename exactly wins, which is what tells two distinct case-variant media files
     * apart. Genuine ambiguity - several candidates, none matching exactly - returns null rather
     * than guessing, so the caller's prefix fallback gets a chance to resolve it instead.
     *
     * @param candidates a {@link List} of {@link Path} sidecars sharing one lowercased owner key, or null if none
     * @param queriedFileName {@link String} the exact filename being looked up (media name or its edited-stripped
     * form)
     * @return {@link Path} the matching sidecar, if any
     */
    private static @Nullable Path bestOwnerMatch(final @Nullable List<Path> candidates, final String queriedFileName) {
        if (candidates == null) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        for (final Path json : candidates) {
            if (ownerKeyOf(json).equals(queriedFileName)) {
                return json;
            }
        }
        return null;
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
     * happens to share the same leading characters. An exact-case match is preferred over a
     * case-folded one, so two sidecars for case-variant media in one directory each reach their
     * own. Case-folded matching still runs when nothing matches exactly, which is what keeps the
     * existing tolerance for a sidecar whose own casing differs from its media's.
     *
     * @param dirSidecars a {@link List} of {@link Path} sidecar paths in the media's directory
     * @param prefix {@link String} the prefix to match sidecar base names against
     * @return {@link Path} the shortest matching sidecar, if any
     */
    private static @Nullable Path shortestStartingWith(final List<Path> dirSidecars, final String prefix) {
        final Path exact = shortestStartingWith(dirSidecars, prefix, false);
        return exact != null ? exact : shortestStartingWith(dirSidecars, prefix, true);
    }

    /**
     * The single-pass search {@link #shortestStartingWith(List, String)} runs twice, once per
     * case-sensitivity setting.
     *
     * @param dirSidecars a {@link List} of {@link Path} sidecar paths in the media's directory
     * @param prefix {@link String} the prefix to match sidecar base names against
     * @param ignoreCase boolean whether the prefix comparison folds case
     * @return {@link Path} the shortest matching sidecar, if any
     */
    private static @Nullable Path shortestStartingWith(final List<Path> dirSidecars, final String prefix,
                                                        final boolean ignoreCase) {
        Path best = null;
        int bestLength = Integer.MAX_VALUE;
        for (final Path json : dirSidecars) {
            final String base = stripJsonExtension(json.getFileName().toString());
            if (base.length() < bestLength && base.regionMatches(ignoreCase, 0, prefix, 0, prefix.length())) {
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
