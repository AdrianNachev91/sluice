package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

// The receipt MontageRenderer.build() hands back to its caller, mirroring index.json's own field
// shape and order. The record is what travels in memory: the caller reports a summary from it
// and hands it to the VisionCuller port. index.json makes the same data durable, so a later
// session can rebuild the record without re-running prep. The montage, sidecar, and shard files
// themselves are always re-read from disk by whoever consumes them. No source field: a cull run only
// ever reads Sorted - the library is final once committed, never re-scanned by cull.
//
// unreviewable lists every candidate this run found but couldn't render a judgeable tile for
// (undecodable, or real pixels below the reviewable floor). Entries are absolute paths, never
// mentioned in any montage or sidecar. Without this, a skipped file leaves no trace anywhere on
// disk. ApplyEngine routes every entry here to Unreviewable/<year>/<month>/, alongside its
// decision-driven routing (junk/scenery/food/near-dup) to their own dedicated folders. Montage
// generation itself only reports the list - it never moves the files, keeping generation
// side-effect-free and dry-run-safe.
public record PrepDir(
        String scope,
        Path basePath,
        int photos,
        List<Path> unreviewable,
        int montages,
        Path prepDir,
        List<String> entries) {

    /**
     * Defensively copies the mutable collection fields.
     *
     * @param scope {@link String} the on-disk tag identifying this prep dir's scope
     * @param basePath {@link Path} the base path reported for this scope
     * @param photos int count of candidates found
     * @param unreviewable a {@link List} of {@link Path} candidates that couldn't render a judgeable tile
     * @param montages int count of montages generated
     * @param prepDir {@link Path} the prep directory path
     * @param entries a {@link List} of {@link String} the montage entry filenames
     */
    public PrepDir {
        entries = List.copyOf(entries);
        unreviewable = List.copyOf(unreviewable);
    }
}
