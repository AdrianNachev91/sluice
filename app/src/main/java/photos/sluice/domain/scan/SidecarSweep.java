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
 * directory owns it. Runs after the per-file routing pass, independently of it.
 *
 * <p>A sidecar counts as spent here purely because its media is gone, regardless of which
 * date-resolution source actually won for that file.
 *
 * <p>Everything returned here gets hard-deleted by the caller, so the decision is deliberately
 * one-sided. Three things keep a sidecar alive, and any one of them is enough. A remaining media
 * file is paired to it, a remaining media file's name claims it, or it could never have been a
 * per-photo sidecar at all. Only a sidecar none of those claims is swept.
 *
 * <p>Flowchart: {@code app/docs/design/domain/scan/sidecar-sweep.md}.
 */
public final class SidecarSweep {

    // Google truncates a sidecar's name to this length. Measured, not guessed. A real export of
    // 14,789 sidecars held 773 cut names, every one of them exactly 46 characters. Every one had
    // media beside it whose own name started with that cut. Community sources describe the same cap
    // (GooglePhotosTakeoutHelper issue #353, metadatafixer.com). Treat it as approximate.
    //
    // The length is doing two jobs below, both of them about a name too mangled to read normally. A
    // cut name is a raw prefix of the media filename, so a prefix match at this length or beyond is
    // evidence of truncation rather than coincidence. And a cut name usually loses its media
    // extension, which is otherwise the signal that a .json was ever a sidecar at all. Reaching
    // this length is what separates such a name from an album descriptor.
    //
    // Public: a test reuses this exact threshold rather than hard-coding a copy that could drift
    // out of sync.
    public static final int MIN_TRUNCATED_OWNER_KEY_LENGTH = 46;

    /**
     * remainingMedia and remainingJsonPaths describe the Inbox as it stands right now. This
     * method has no opinion on how the caller derived "remaining," and does no scanning itself.
     * Returns the subset of remainingJsonPaths that no remaining media file owns.
     *
     * <p>sidecarsByMedia is the pairing the caller already computed when it scanned. It is the
     * authoritative answer to which sidecar a media file reads its date from. A sidecar it still
     * maps a remaining media file to is by definition still needed. Passing it in is what keeps
     * the sweep from re-deriving that relationship and getting a different answer.
     *
     * <p>Its sidecar paths must be the same {@link Path} values remainingJsonPaths holds, since
     * membership is decided by equality. Both come from one scan of the same tree, so they are.
     *
     * @param remainingMedia a {@link List} of {@link Path} media files still present in the Inbox
     * @param remainingJsonPaths a {@link List} of {@link Path} sidecar JSON files still present in the Inbox
     * @param sidecarsByMedia a {@link Map} of {@link Path} to {@link Path} sidecar path keyed by the media path it
     * was paired to at scan time
     * @return a {@link List} of {@link Path} orphaned sidecar paths that no remaining media owns
     */
    public List<Path> findOrphaned(final List<Path> remainingMedia, final List<Path> remainingJsonPaths,
                                   final Map<Path, Path> sidecarsByMedia) {
        final Map<Path, Set<String>> mediaNamesByDir = new HashMap<>();
        final Set<Path> pairedToRemainingMedia = new HashSet<>();
        for (final Path media : remainingMedia) {
            mediaNamesByDir.computeIfAbsent(TakeoutSidecarPairer.directoryKeyOf(media), _ -> new HashSet<>())
                    .add(media.getFileName().toString().toLowerCase(Locale.ROOT));
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
     * The keep rules, cheapest first. Each covers a case the others miss, so the sweep asks all of
     * them before giving up on a sidecar.
     *
     * @param json {@link Path} the sidecar being judged
     * @param pairedToRemainingMedia a {@link Set} of {@link Path} sidecar paths some remaining media file is paired to
     * @param mediaNamesByDir a {@link Map} of {@link Path} to {@link Set} of {@link String} lowercased remaining
     * media file names, keyed by their directory
     * @return boolean true if nothing left in the Inbox owns this sidecar
     */
    private static boolean isOrphaned(final Path json, final Set<Path> pairedToRemainingMedia,
                                      final Map<Path, Set<String>> mediaNamesByDir) {
        if (pairedToRemainingMedia.contains(json)) {
            return false;
        }
        final String ownerKeyLower = TakeoutSidecarPairer.ownerKeyOf(json).toLowerCase(Locale.ROOT);
        if (!couldHaveBeenASidecar(json, ownerKeyLower)) {
            return false;
        }
        return mediaNamesByDir.getOrDefault(TakeoutSidecarPairer.directoryKeyOf(json), Set.of()).stream()
                .noneMatch(mediaName -> ownedByName(mediaName, ownerKeyLower));
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
    private static boolean couldHaveBeenASidecar(final Path json, final String ownerKeyLower) {
        return TakeoutSidecarPairer.looksLikeMediaSidecar(json)
                || ownerKeyLower.length() >= MIN_TRUNCATED_OWNER_KEY_LENGTH;
    }

    /**
     * Whether one remaining media file's name claims this sidecar. An exact owner-key match always
     * counts. A prefix match counts only from the truncation length up, where a raw cut of the
     * media filename is the likely explanation. Below it, a shared opening is coincidence.
     *
     * @param mediaNameLower {@link String} one lowercased media file name from the sidecar's own directory
     * @param ownerKeyLower {@link String} the lowercased owner key derived from the sidecar
     * @return boolean true if that media file owns this sidecar
     */
    private static boolean ownedByName(final String mediaNameLower, final String ownerKeyLower) {
        return mediaNameLower.equals(ownerKeyLower)
                || (ownerKeyLower.length() >= MIN_TRUNCATED_OWNER_KEY_LENGTH
                        && mediaNameLower.startsWith(ownerKeyLower));
    }
}
