package photos.sluice.domain.scan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds Takeout JSON sidecars that are now orphaned: their owning media file no longer exists
 * anywhere in the sidecar's own directory. Runs after the per-file routing pass, independently of
 * it.
 *
 * <p>A sidecar counts as spent here purely because its media is gone, regardless of which
 * date-resolution source actually won for that file.
 *
 * <p>Flowchart: {@code app/docs/design/domain/scan/sidecar-sweep.md}.
 */
public final class SidecarSweep {

    // Google's ".supplemental-metadata" JSON suffix (introduced late 2024) gets truncated once
    // the original filename plus that suffix would exceed roughly 46 characters. This is
    // community-documented (GooglePhotosTakeoutHelper issue #353, metadatafixer.com), not an
    // official Google spec. Treat 46 as approximate, not exact.
    //
    // As long as any part of ".supplemental-metadata" still fits, ownerKeyOf's regex recovers the
    // real filename exactly. The exact-match branch below already handles that case. This guard
    // only matters for the case where the original filename alone is already near the cap.
    // Then none of the suffix survives, and the JSON base is a raw truncated cut of the filename
    // itself. A genuine truncation of that kind lands close to 46 characters. A shorter owner key
    // hitting the prefix-fallback path is far more likely to be an accidental collision with an
    // unrelated file. So only an owner key at least this long is trusted as prefix evidence.
    // Public: a test reuses this exact threshold rather than hard-coding a copy that could drift
    // out of sync.
    public static final int MIN_TRUNCATED_OWNER_KEY_LENGTH = 46;

    /**
     * remainingMedia and remainingJsonPaths describe the Inbox as it stands right now. This
     * method has no opinion on how the caller derived "remaining," and does no scanning itself.
     * Returns the subset of remainingJsonPaths with no owning media left in the same directory.
     *
     * @param remainingMedia a {@link List} of {@link Path} media files still present in the Inbox
     * @param remainingJsonPaths a {@link List} of {@link Path} sidecar JSON files still present in the Inbox
     * @return a {@link List} of {@link Path} orphaned sidecar paths with no owning media left
     */
    public List<Path> findOrphaned(final List<Path> remainingMedia, final List<Path> remainingJsonPaths) {
        final Map<Path, List<String>> mediaNamesByDir = new HashMap<>();
        for (final Path media : remainingMedia) {
            mediaNamesByDir.computeIfAbsent(TakeoutSidecarPairer.directoryKeyOf(media), _ -> new ArrayList<>())
                    .add(media.getFileName().toString().toLowerCase(Locale.ROOT));
        }

        final List<Path> orphaned = new ArrayList<>();
        for (final Path json : remainingJsonPaths) {
            final String ownerKeyLower = TakeoutSidecarPairer.ownerKeyOf(json).toLowerCase(Locale.ROOT);
            final List<String> mediaNames = mediaNamesByDir.getOrDefault(TakeoutSidecarPairer.directoryKeyOf(json), List.of());
            if (!stillNeeded(ownerKeyLower, mediaNames)) {
                orphaned.add(json);
            }
        }
        return orphaned;
    }

    /**
     * A sidecar name can be a truncated prefix of the media name it describes (Google truncates
     * long sidecar names). So an exact match isn't the only way a sidecar is still needed - a
     * long enough owner key matching as a prefix also counts. A short owner key only counts on an
     * exact match, to avoid mistaking an accidental prefix collision for a truncated name.
     *
     * @param ownerKeyLower {@link String} the lowercased owner key derived from the sidecar
     * @param mediaNames a {@link List} of {@link String} lowercased media file names in the same directory
     * @return boolean true if the sidecar is still needed by some media file
     */
    private static boolean stillNeeded(final String ownerKeyLower, final List<String> mediaNames) {
        final boolean allowPrefixMatch = ownerKeyLower.length() >= MIN_TRUNCATED_OWNER_KEY_LENGTH;
        return mediaNames.stream().anyMatch(name -> name.equals(ownerKeyLower) || (allowPrefixMatch && name.startsWith(ownerKeyLower)));
    }
}
