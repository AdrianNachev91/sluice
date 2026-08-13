package photos.sluice.application.port.out;

import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The effect boundary application services use to read and write the JSON artifacts a prep
 * directory holds beyond the montage images themselves. That means {@code index.json}, the montage
 * sidecars, and the vision step's decision shards, all this app's own prior output. It also covers
 * the merged {@code decisions.json} {@code ApplyEngine} writes once a run completes.
 *
 * <p>Listing which {@code decisions-*.json} files exist (for stray-shard detection) stays on the
 * already-generic {@code MediaStore}. This port only covers the structured JSON {@code MediaStore}
 * cannot parse on its own.
 */
public interface CullPrepPort {

    /**
     * The prep directory's own index.json, describing the scope it covers and every montage it
     * expects a shard for. This is the app's own prior output. Content that is missing or cannot
     * parse means the prep dir itself is corrupt, not a fixable culling mistake, and throws
     * {@link MalformedPrepJsonException}. A read that merely failed while the file is intact (a
     * lock, a permission denial) throws a plain {@link UncheckedIOException} instead. That is how a
     * caller tells the two apart.
     *
     * <p>A caller may rely on the answer's own {@code prepDir} being the directory it asked about.
     * That value decides where a resume applies, which watcher is retired, and what a cleanup
     * deletes, so a caller acting on it is acting on a path it chose rather than one the file
     * named.
     *
     * @param prepDir {@link Path} the prep directory to read
     * @return {@link PrepDir} the parsed prep directory index, carrying prepDir as passed here
     */
    PrepDir readIndex(Path prepDir);

    /**
     * Writes index.json wholesale, replacing whatever was there before. No cull run calls this -
     * {@code CullMontageRenderer} writes the original via its own adapter. The one caller is
     * {@link photos.sluice.application.service.PrepDirRemedies#rebuildIndex}, to persist an index
     * reconstructed from surviving sidecars after the original was found corrupt or missing.
     *
     * @param prepDir {@link Path} the prep directory to write into
     * @param index {@link PrepDir} the index to persist
     */
    void writeIndex(Path prepDir, PrepDir index);

    /**
     * Every photo entry montage's sidecar (montage-NNN.json) lists. The sidecar is this app's own
     * prior output. Missing or unparseable content throws {@link MalformedPrepJsonException}, the
     * same distinction {@link #readIndex} makes against a plain {@link UncheckedIOException} for a
     * read that merely failed.
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
     * The decisions-NNN.json shard for montage. Callers should check {@link #hasShard} first.
     * Content that cannot be turned into decisions throws {@link MalformedPrepJsonException}, the
     * same distinction {@link #readIndex} makes against a plain {@link UncheckedIOException} for a
     * read that merely failed. A shard is the culling agent's own output, not this app's. So
     * damaged content here is reported as a finding, rather than meaning the prep dir is broken.
     *
     * @param prepDir {@link Path} the prep directory holding the shard
     * @param montage {@link String} the montage whose shard to read
     * @return {@link DecisionShard} the parsed decision shard
     */
    DecisionShard readShard(Path prepDir, String montage);

    /**
     * Reads a shard file by its own path, rather than a montage's canonical name. A stray shard's
     * filename names no real montage, so it cannot be looked up via {@link #readShard}. A
     * troubleshooter inspecting one before deciding whether to rename it needs this instead. It
     * throws the same way {@link #readShard} does.
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
