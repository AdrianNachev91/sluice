package photos.sluice.application.port.out;

import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.nio.file.Path;
import java.util.List;

// Reads and writes the JSON artifacts a prep directory holds beyond the montage images themselves.
// That means index.json, the montage sidecars, and the vision step's decision shards (all this
// app's own prior output), plus the merged decisions.json ApplyEngine writes once a run completes.
// Listing which decisions-*.json files exist (for stray-shard detection) stays on the
// already-generic MediaStore. This port only covers the structured JSON MediaStore cannot parse on
// its own.
public interface CullPrepPort {

    /**
     * The prep directory's own index.json, describing the scope it covers and every montage it
     * expects a shard for. Unchecked failure if missing or unreadable - the app's own prior output,
     * so a broken one means the prep dir itself is corrupt, not a fixable culling mistake.
     *
     * @param prepDir {@link Path} the prep directory to read
     * @return {@link PrepDir} the parsed prep directory index
     */
    PrepDir readIndex(Path prepDir);

    /**
     * Writes index.json wholesale, replacing whatever was there before. Used only by {@link
     * photos.sluice.application.service.ApplyEngine#rebuildIndex} to persist an index reconstructed
     * from surviving sidecars after the original was found corrupt or missing - a normal cull run
     * never calls this, since {@code CullMontageRenderer} writes the original via its own adapter.
     *
     * @param prepDir {@link Path} the prep directory to write into
     * @param index {@link PrepDir} the index to persist
     */
    void writeIndex(Path prepDir, PrepDir index);

    /**
     * Every photo entry montage's sidecar (montage-NNN.json) lists. Unchecked failure if unreadable
     * or malformed. The sidecar is this app's own prior output, so a broken one means the prep dir
     * itself is corrupt, not a fixable culling mistake.
     *
     * @param prepDir {@link Path} the prep directory holding the sidecar
     * @param montage {@link String} the montage whose sidecar to read
     * @return a {@link List} of {@link SidecarPhotoEntry} the sidecar's photo entries
     */
    List<SidecarPhotoEntry> readSidecar(Path prepDir, String montage);

    /**
     * Whether montage's decisions-NNN.json shard is present. Callers check this before readShard.
     *
     * @param prepDir {@link Path} the prep directory to check
     * @param montage {@link String} the montage to check for a shard
     * @return boolean true if the shard file exists
     */
    boolean hasShard(Path prepDir, String montage);

    /**
     * The decisions-NNN.json shard for montage. Throws UncheckedIOException if the file exists but
     * cannot be parsed as a shard - callers should check hasShard first.
     *
     * @param prepDir {@link Path} the prep directory holding the shard
     * @param montage {@link String} the montage whose shard to read
     * @return {@link DecisionShard} the parsed decision shard
     */
    DecisionShard readShard(Path prepDir, String montage);

    /**
     * Reads a shard file by its own path, rather than a montage's canonical name. A stray shard's
     * filename names no real montage, so it cannot be looked up via {@link #readShard} - a
     * troubleshooter inspecting one before deciding whether to rename it needs this instead.
     * Throws UncheckedIOException if the file cannot be parsed as a shard.
     *
     * @param shardFile {@link Path} the shard file's own path
     * @return {@link DecisionShard} the parsed decision shard
     */
    DecisionShard readShardFile(Path shardFile);

    /**
     * Writes the merged, human-readable record of a completed apply run. That is every non-keep
     * decision the prep directory held, including ones a prior crashed run already applied, plus
     * this run's own outcome summary.
     *
     * @param prepDir {@link Path} the prep directory to write into
     * @param scope {@link String} description of the scope this run covered
     * @param decisions a {@link List} of {@link Decision} every non-keep decision the run held
     * @param report {@link ApplyReport} this run's own outcome summary
     */
    void writeMergedDecisions(Path prepDir, String scope, List<Decision> decisions, ApplyReport report);
}
