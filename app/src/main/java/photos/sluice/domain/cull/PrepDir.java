package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

/**
 * The receipt {@link photos.sluice.application.port.out.MontageRenderer#build} hands back to its
 * caller, mirroring {@code index.json}'s own field shape and order. The record is what travels in
 * memory: the caller reports a summary from it and hands it to the
 * {@link photos.sluice.application.port.out.VisionCuller} port. {@code index.json} makes the same
 * data durable, so a later session can rebuild the record without re-running prep. The record
 * carries no montage, sidecar, or shard content of its own, so those files are read from disk at
 * the point of use.
 *
 * <p>There is no source field: a cull run only ever reads Sorted, since the library is final once
 * committed and is never re-scanned by cull.
 *
 * <p>{@code unreviewable} lists every candidate this run found but could not render a judgeable
 * tile for, whether undecodable or with real pixels below the reviewable floor. Entries are
 * absolute paths, never mentioned in any montage or sidecar. Without this, a skipped file would
 * leave no trace anywhere on disk. {@link photos.sluice.application.service.ApplyEngine} routes
 * every entry here to {@code Unreviewable/<year>/<month>/}, alongside its decision-driven routing
 * (junk, scenery, food, near-dup) to their own dedicated folders. Montage generation itself only
 * reports the list; it never moves the files, keeping generation side-effect-free and
 * dry-run-safe.
 */
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
