package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One entry in a montage sidecar's {@code photos[]} array.
 *
 * <p>{@code time} is the file's raw mtime, not a resolved local date. A cull run orders by
 * filesystem mtime, deliberately not routed through
 * {@link photos.sluice.domain.dating.DateResolver}. {@code received} is a carried flag, not
 * computed here: matching the WhatsApp-received filename pattern is the assembling caller's job
 * ({@link photos.sluice.application.port.out.MontageRenderer}), before this entry is built.
 */
public record SidecarPhotoEntry(Path src, String name, Instant time, boolean received) {
}
