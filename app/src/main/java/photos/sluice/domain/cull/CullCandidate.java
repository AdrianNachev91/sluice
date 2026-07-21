package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.time.Instant;

// A photo found within a cull scope, before its received flag is computed and it's turned into a
// SidecarPhotoEntry. path is absolute.
//
// mtime drives ordering, deliberately not the resolved date DateResolver would give (see CullScope's
// own doc comment). By the time a file reaches cull it already sits in a Sorted year/month folder
// chosen by that resolution chain at sort time. Ordering here only groups temporally-adjacent shots
// (bursts, near-duplicates) within that already-narrow window, not calendar accuracy across dating
// sources. mtime is also the only ordering signal cheap enough for OldestN, which spans the whole
// library without re-parsing every file's EXIF or Takeout sidecar.
public record CullCandidate(Path path, Instant mtime) {
}
