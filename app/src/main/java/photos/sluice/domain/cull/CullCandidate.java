package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A photo found within a cull scope, before its {@code received} flag is computed and it is turned
 * into a {@link SidecarPhotoEntry}. {@code path} is absolute.
 *
 * <p>{@code mtime} drives ordering, deliberately not the resolved date a
 * {@link photos.sluice.domain.dating.DateResolver} would give (see {@link CullScope}'s own class
 * comment). By the time a file reaches cull it already sits in a Sorted year/month folder chosen by
 * that resolution chain at sort time. Ordering here only groups temporally-adjacent shots (bursts,
 * near-duplicates) within that already-narrow window, not calendar accuracy across dating sources.
 * It is also the only ordering signal cheap enough for {@link CullScope.OldestN}, which spans the
 * whole library without re-parsing every file's EXIF or Takeout sidecar.
 */
public record CullCandidate(Path path, Instant mtime) {
}
