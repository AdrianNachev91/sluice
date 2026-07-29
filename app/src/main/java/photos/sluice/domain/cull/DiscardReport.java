package photos.sluice.domain.cull;

import java.nio.file.Path;

/**
 * {@code PrepDirRemedies.discard()}'s outcome for one prep dir. graveyard is where every non-image
 * file - shards, sidecars, index.json, the move-record log, any disaster drawer - was filed for
 * its own 30-day retention window. shardsSetAside counts how many montage decision shards were
 * among them, so a caller can tell the user how many already-paid vision-model calls this discard
 * gives up on.
 *
 * @param graveyard {@link Path} the global graveyard directory everything worth keeping was filed into
 * @param shardsSetAside int how many montage decision shards were among the filed files
 */
public record DiscardReport(Path graveyard, int shardsSetAside) {
}
