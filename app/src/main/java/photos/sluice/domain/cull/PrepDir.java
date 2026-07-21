package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

// The receipt MontageRenderer.build() hands back to its caller, mirroring index.json's own field
// shape and order. Later phases (the culler, apply) re-read the prep directory from disk
// themselves rather than consuming this value directly - it exists for the calling engine to
// report a summary without re-reading the file it just wrote. No source field: a cull run only
// ever reads Sorted - the library is final once committed, never re-scanned by cull.
public record PrepDir(
        String scope,
        Path basePath,
        int photos,
        int montages,
        Path prepDir,
        List<String> entries) {

    public PrepDir {
        entries = List.copyOf(entries);
    }
}
