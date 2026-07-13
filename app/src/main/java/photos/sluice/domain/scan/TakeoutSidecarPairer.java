package photos.sluice.domain.scan;

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

// Pairs each media file with the Google Takeout JSON sidecar that describes it. Pairing is
// scoped per parent directory - a same-named sidecar in a different directory never cross-pairs.
// Flowchart + naming examples: app/docs/design/domain/scan/takeout-sidecar-pairing.md.
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

    public record PairingResult(boolean takeoutMode, Map<Path, Path> sidecarsByMedia) {}

    public PairingResult pair(List<Path> mediaPaths, List<Path> jsonPaths) {
        boolean takeoutMode = !jsonPaths.isEmpty();

        // Two passes: first index every sidecar in a directory by the media filename it
        // describes (built once, independent of how many media files there are), then look each
        // media file up against that index. This keeps pairing O(sidecars + media) instead of
        // O(sidecars x media), and lets every media file share the same per-directory index
        // rather than re-deriving owner keys per lookup.
        Map<Path, List<Path>> jsonsByDir = jsonPaths.stream()
                .collect(Collectors.groupingBy(TakeoutSidecarPairer::directoryKeyOf, LinkedHashMap::new, Collectors.toList()));
        Map<Path, Map<String, Path>> ownersByDir = new HashMap<>();
        jsonsByDir.forEach((dir, sidecars) -> {
            Map<String, Path> owners = new LinkedHashMap<>();
            for (Path json : sidecars) {
                // First sidecar to claim an owner key wins; a second sidecar deriving the same
                // key (rare, e.g. two differently-suffixed sidecars for one photo) is ignored
                // rather than overwriting the first match.
                owners.putIfAbsent(ownerKeyOf(json).toLowerCase(Locale.ROOT), json);
            }
            ownersByDir.put(dir, owners);
        });

        Map<Path, Path> sidecarsByMedia = new LinkedHashMap<>();
        for (Path media : mediaPaths) {
            Path dir = directoryKeyOf(media);
            String fileName = media.getFileName().toString();
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

    // A root-level path has no parent to scope pairing by; fall back to the path itself so it
    // groups with nothing rather than throwing (Collectors.groupingBy rejects a null key).
    private static Path directoryKeyOf(Path path) {
        Path parent = path.getParent();
        return parent != null ? parent : path;
    }

    // Derives the media filename a sidecar describes from its base name (json extension already stripped).
    private static String ownerKeyOf(Path json) {
        String base = stripJsonExtension(json.getFileName().toString());
        Matcher supplemental = SUPPLEMENTAL.matcher(base);
        if (supplemental.matches()) {
            return supplemental.group(1);
        }
        Matcher dup = SIDECAR_DUP_NUMBERED.matcher(base);
        if (dup.matches()) {
            return dup.group(1) + "(" + dup.group(3) + ")." + dup.group(2);
        }
        return base;
    }

    // Tries the media's own filename first, then its edited-suffix-stripped form - an edited
    // copy has no sidecar of its own, so it must be looked up under its original's key instead.
    private static Path matchByOwnerKey(Map<String, Path> owners, String mediaFileName) {
        if (owners == null) {
            return null;
        }
        Path hit = owners.get(mediaFileName.toLowerCase(Locale.ROOT));
        if (hit != null) {
            return hit;
        }
        Matcher edited = EDITED.matcher(mediaFileName);
        if (edited.matches()) {
            String editedBase = edited.group(1) + "." + edited.group(2);
            hit = owners.get(editedBase.toLowerCase(Locale.ROOT));
        }
        return hit;
    }

    // Fallback for when no owner key matches exactly: prefix-match the dir's sidecar base names
    // against the media name's candidate prefixes (plain name, then dup/edited variants), in
    // priority order, taking the first prefix with any hit and the shortest-matching sidecar
    // among that prefix's hits - covers non-standard sidecar naming the owner-key derivation
    // above doesn't land on exactly.
    private static Path prefixFallback(List<Path> dirSidecars, String mediaFileName) {
        if (dirSidecars == null) {
            return null;
        }
        for (String prefix : candidatePrefixes(mediaFileName)) {
            Path hit = shortestStartingWith(dirSidecars, prefix);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    // Same candidate identities as matchByOwnerKey (plain name, then edited-stripped), but each
    // also gets its dup-numbering-reversed form here: owner keys built by ownerKeyOf are already
    // normalized to this form, but a sidecar's base name on disk is not, so a prefix scan against
    // raw base names needs the reversal applied to the search term instead.
    private static List<String> candidatePrefixes(String mediaFileName) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add(mediaFileName);
        addDupReversedForm(prefixes, mediaFileName);
        Matcher edited = EDITED.matcher(mediaFileName);
        if (edited.matches()) {
            String editedBase = edited.group(1) + "." + edited.group(2);
            prefixes.add(editedBase);
            addDupReversedForm(prefixes, editedBase);
        }
        return prefixes;
    }

    private static void addDupReversedForm(List<String> prefixes, String mediaFileName) {
        Matcher dup = MEDIA_DUP_NUMBERED.matcher(mediaFileName);
        if (dup.matches()) {
            prefixes.add(dup.group(1) + dup.group(3) + dup.group(2));
        }
    }

    // Among sidecars whose base name starts with this prefix, the shortest is the closest match
    // to the prefix itself - a longer one is more likely to be an unrelated sidecar that merely
    // happens to share the same leading characters.
    private static Path shortestStartingWith(List<Path> dirSidecars, String prefix) {
        Path best = null;
        int bestLength = Integer.MAX_VALUE;
        for (Path json : dirSidecars) {
            String base = stripJsonExtension(json.getFileName().toString());
            if (base.length() < bestLength && base.regionMatches(true, 0, prefix, 0, prefix.length())) {
                best = json;
                bestLength = base.length();
            }
        }
        return best;
    }

    private static String stripJsonExtension(String name) {
        return name.length() >= 5 && name.regionMatches(true, name.length() - 5, ".json", 0, 5)
                ? name.substring(0, name.length() - 5)
                : name;
    }
}
