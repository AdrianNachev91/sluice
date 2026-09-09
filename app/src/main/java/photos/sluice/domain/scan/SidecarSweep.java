package photos.sluice.domain.scan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds Takeout JSON sidecars that are now orphaned: no media file left in the sidecar's own
 * directory owns it.
 *
 * <p>A sidecar counts as spent here purely because its media is gone, regardless of which
 * date-resolution source actually won for that file.
 *
 * <p>Everything returned here gets hard-deleted by the caller, so the decision is deliberately
 * one-sided. Three things keep a sidecar alive, and any one of them is enough. A remaining media
 * file is paired to it, a remaining media file's name claims it, or it could never have been a
 * per-photo sidecar at all.
 *
 * <p>Flowchart: {@code app/docs/design/domain/scan/sidecar-sweep.md}.
 */
public final class SidecarSweep {

    // Google truncates a sidecar's name to this length. Measured, not guessed. A real export of
    // 14,789 sidecars held 773 cut names, every one of them exactly 46 characters. Each had media
    // beside it whose own name started with that cut. Community sources describe the same cap
    // (GooglePhotosTakeoutHelper issue #353, metadatafixer.com). Treat it as approximate.
    //
    // The length does two jobs below, both about a name too mangled to read normally. A cut name is
    // a raw prefix of the media filename, so a prefix match at this length or beyond is evidence of
    // truncation rather than coincidence. And a cut name usually loses its media extension, which is
    // otherwise the only signal that a .json was ever a sidecar at all.
    public static final int MIN_TRUNCATED_OWNER_KEY_LENGTH = 46;

    /**
     * remainingMedia and remainingJsonPaths describe the Inbox as it stands right now. This
     * method has no opinion on how the caller derived "remaining," and does no scanning itself.
     *
     * <p>sidecarsByMedia is the pairing already computed at scan time, and the authoritative answer
     * to which sidecar a media file reads its date from. Taking it rather than re-deriving it is
     * what stops two derivations of the same relationship disagreeing over a delete.
     *
     * <p>Its sidecar paths must be the same {@link Path} values remainingJsonPaths holds, since
     * membership is decided by equality.
     *
     * @param remainingMedia a {@link List} of {@link Path} media files still present in the Inbox
     * @param remainingJsonPaths a {@link List} of {@link Path} sidecar JSON files still present in the Inbox
     * @param sidecarsByMedia a {@link Map} of {@link Path} to {@link Path} sidecar path keyed by the media path it
     * was paired to at scan time
     * @return a {@link List} of {@link Path} orphaned sidecar paths that no remaining media owns
     */
    public List<Path> findOrphaned(final List<Path> remainingMedia, final List<Path> remainingJsonPaths,
                                   final Map<Path, Path> sidecarsByMedia) {
        // Raw names are grouped by their own lowercased form, not collapsed into it. A genuine
        // case-variant collision between two remaining media files then stays visible to
        // ownedByName, the same reason TakeoutSidecarPairer groups sidecar candidates this way.
        final Map<Path, Map<String, List<String>>> mediaNamesByDir = new HashMap<>();
        final Set<Path> pairedToRemainingMedia = new HashSet<>();
        for (final Path media : remainingMedia) {
            final String fileName = media.getFileName().toString();
            mediaNamesByDir.computeIfAbsent(TakeoutSidecarPairer.directoryKeyOf(media), _ -> new HashMap<>())
                    .computeIfAbsent(fileName.toLowerCase(Locale.ROOT), _ -> new ArrayList<>())
                    .add(fileName);
            final Path paired = sidecarsByMedia.get(media);
            if (paired != null) {
                pairedToRemainingMedia.add(paired);
            }
        }

        final List<Path> orphaned = new ArrayList<>();
        for (final Path json : remainingJsonPaths) {
            if (isOrphaned(json, pairedToRemainingMedia, mediaNamesByDir)) {
                orphaned.add(json);
            }
        }
        return orphaned;
    }

    /**
     * The keep rules, cheapest first. Each covers a case the others miss.
     *
     * @param json {@link Path} the sidecar being judged
     * @param pairedToRemainingMedia a {@link Set} of {@link Path} sidecar paths some remaining media file is paired to
     * @param mediaNamesByDir a {@link Map} of {@link Path} to {@link Map} of {@link String} to {@link List} of
     * {@link String}, each directory's remaining media file names grouped by their own lowercased form
     * @return boolean true if nothing left in the Inbox owns this sidecar
     */
    private static boolean isOrphaned(final Path json, final Set<Path> pairedToRemainingMedia,
                                      final Map<Path, Map<String, List<String>>> mediaNamesByDir) {
        if (pairedToRemainingMedia.contains(json)) {
            return false;
        }
        final String ownerKey = TakeoutSidecarPairer.ownerKeyOf(json);
        final String ownerKeyLower = ownerKey.toLowerCase(Locale.ROOT);
        if (!hasSidecarShape(json, ownerKeyLower)) {
            return false;
        }
        final Map<String, List<String>> namesInDir =
                mediaNamesByDir.getOrDefault(TakeoutSidecarPairer.directoryKeyOf(json), Map.of());
        return !ownedByName(namesInDir, ownerKey, ownerKeyLower);
    }

    /**
     * Whether this {@code .json} was ever a per-photo sidecar, judged from its name alone. Two
     * signals, either one enough. Its owner key names a media file, or the key is long enough to be
     * a name Google cut short.
     *
     * <p>A file failing both is something like {@code metadata.json} or {@code notes.json}, which
     * could not have described a photo and is not this mechanism's to delete.
     *
     * @param json {@link Path} the sidecar being judged
     * @param ownerKeyLower {@link String} the lowercased owner key derived from it
     * @return boolean true if the name is consistent with a per-photo sidecar
     */
    private static boolean hasSidecarShape(final Path json, final String ownerKeyLower) {
        return TakeoutSidecarPairer.looksLikeMediaSidecar(json)
                || ownerKeyLower.length() >= MIN_TRUNCATED_OWNER_KEY_LENGTH;
    }

    /**
     * Whether some remaining media file's name claims this sidecar. An exact match always counts.
     * A lone name sharing the owner key's lowercased form is trusted regardless of its own case.
     * Among several such names, only one matching exactly counts - the same rule
     * {@code TakeoutSidecarPairer.bestOwnerMatch} applies on the sidecar side of a collision. A
     * prefix match counts only from the truncation length up. It is checked across every
     * remaining name in the directory, regardless of its own lowercased bucket. A raw cut of any
     * of them is an equally likely explanation.
     *
     * @param namesInDir a {@link Map} of {@link String} to {@link List} of {@link String}, this directory's
     * remaining media file names grouped by their own lowercased form
     * @param ownerKey {@link String} the raw owner key derived from the sidecar
     * @param ownerKeyLower {@link String} the lowercased owner key derived from the sidecar
     * @return boolean true if some remaining media file owns this sidecar
     */
    private static boolean ownedByName(final Map<String, List<String>> namesInDir, final String ownerKey,
                                       final String ownerKeyLower) {
        final List<String> candidates = namesInDir.getOrDefault(ownerKeyLower, List.of());
        if (candidates.size() == 1 || candidates.contains(ownerKey)) {
            return true;
        }
        return ownerKeyLower.length() >= MIN_TRUNCATED_OWNER_KEY_LENGTH
                && namesInDir.values().stream().flatMap(List::stream)
                        .anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith(ownerKeyLower));
    }
}
