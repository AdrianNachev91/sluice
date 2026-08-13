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
 * <p>{@code categories} is the classification category set this run was prepped under, captured at
 * prep time. {@link ShardValidator} accepts only these names, via {@link #categoryNames()}. So a run
 * is judged against the rules it was culled under, never against whatever config holds when its
 * shards finally arrive. Those two moments can be days apart while an external agent works. Without
 * this field, editing a category would fail every waiting run that named it, with cause and symptom
 * on different screens and different days.
 *
 * <p>Whole cards rather than names, because a name alone cannot render a culling prompt. An
 * automated provider needs each card's "what belongs here" description. The set it judges a
 * response against has to be the set it asked the model to use. Names alone would leave the prompt
 * reading live config while validation read the run, so the two could disagree about what a
 * category even means.
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
        List<CullCategory> categories,
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
     * @param categories a {@link List} of {@link CullCategory} the category cards this run was prepped under
     * @param basePath {@link Path} the base path reported for this scope
     * @param photos int count of candidates found
     * @param unreviewable a {@link List} of {@link Path} candidates that couldn't render a judgeable tile
     * @param montages int count of montages generated
     * @param prepDir {@link Path} the prep directory path
     * @param entries a {@link List} of {@link String} the montage entry filenames
     */
    public PrepDir {
        categories = List.copyOf(categories);
        entries = List.copyOf(entries);
        unreviewable = List.copyOf(unreviewable);
    }

    /**
     * The recorded cards' names, in recorded order. Derived rather than stored, so the names a
     * decision is judged against and the cards a prompt is rendered from can never drift apart.
     *
     * @return a {@link List} of {@link String} the recorded category names
     */
    public List<String> categoryNames() {
        return this.categories.stream().map(CullCategory::name).toList();
    }
}
