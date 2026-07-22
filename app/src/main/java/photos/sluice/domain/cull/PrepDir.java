package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

// The receipt MontageRenderer.build() hands back to its caller, mirroring index.json's own field
// shape and order. Downstream consumers (the culler, apply) re-read the prep directory from disk
// themselves rather than consuming this value directly - it exists for the calling engine to
// report a summary without re-reading the file it just wrote. No source field: a cull run only
// ever reads Sorted - the library is final once committed, never re-scanned by cull.
//
// unreviewable lists every candidate this run found but couldn't render a judgeable tile for
// (undecodable, or real pixels below the reviewable floor). Entries are absolute paths, never
// mentioned in any montage or sidecar. Without this, a skipped file leaves no trace anywhere on
// disk. The ApplyEngine is the intended future consumer. It already routes decision-driven
// files (junk/scenery/food/near-dup) to dedicated folders, and this is one more category for it to
// route, to Unreviewable/<year>/<month>/. This chunk only reports the list. It never moves the
// files itself, keeping montage generation side-effect-free and dry-run-safe.
public record PrepDir(
        String scope,
        Path basePath,
        int photos,
        List<Path> unreviewable,
        int montages,
        Path prepDir,
        List<String> entries) {

    public PrepDir {
        entries = List.copyOf(entries);
        unreviewable = List.copyOf(unreviewable);
    }
}
