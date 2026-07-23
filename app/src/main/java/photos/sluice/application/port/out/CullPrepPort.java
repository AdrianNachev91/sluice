package photos.sluice.application.port.out;

import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.nio.file.Path;
import java.util.List;

// Reads and writes the JSON artifacts a prep directory holds beyond the montage images themselves.
// That means the montage sidecars (this app's own prior output) and the vision step's decision
// shards, plus the merged decisions.json ApplyEngine writes once a run completes. Listing which
// decisions-*.json files exist (for stray-shard detection) stays on the already-generic MediaStore.
// This port only covers the structured JSON MediaStore cannot parse on its own.
public interface CullPrepPort {

    // Every photo entry montage's sidecar (montage-NNN.json) lists. Unchecked failure if unreadable
    // or malformed. The sidecar is this app's own prior output, so a broken one means the prep dir
    // itself is corrupt, not a fixable culling mistake.
    List<SidecarPhotoEntry> readSidecar(Path prepDir, String montage);

    // Whether montage's decisions-NNN.json shard is present. Callers check this before readShard.
    boolean hasShard(Path prepDir, String montage);

    // The decisions-NNN.json shard for montage. Throws UncheckedIOException if the file exists but
    // cannot be parsed as a shard - callers should check hasShard first.
    DecisionShard readShard(Path prepDir, String montage);

    // Writes the merged, human-readable record of a completed apply run. That is every non-keep
    // decision the prep directory held, including ones a prior crashed run already applied, plus
    // this run's own outcome summary.
    void writeMergedDecisions(Path prepDir, String scope, List<Decision> decisions, ApplyReport report);
}
