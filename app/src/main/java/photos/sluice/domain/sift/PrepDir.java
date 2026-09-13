package photos.sluice.domain.sift;

import photos.sluice.application.port.out.VisionSieve;

import java.nio.file.Path;
import java.util.List;

/**
 * The receipt {@link photos.sluice.application.port.out.MontageRenderer#build} hands back to its
 * caller. It mirrors {@code index.json}'s own field shape and order, but for {@code prepDir}. The
 * file does not carry that one at all, for the reason given below. The record is what travels in
 * memory, and {@code index.json} makes the same data durable, so a later session can rebuild the
 * record without re-running prep. It carries no montage, sidecar, or shard content of its own, so
 * those files are read from disk at the point of use.
 *
 * <p>There is no source field: a sift run only ever reads Sorted, since the library is final once
 * committed and is never re-scanned by sift.
 *
 * <p>{@code prepDir} is the one field with no counterpart in the file. A reader fills it from the
 * directory it read the file out of. It is a plugin's only handle on its own directory, since
 * {@link VisionSieve#sift} hands over this record and nothing
 * else. Recording it in the file would let a doctored copy in one directory point every downstream
 * step at another. The value would be overwritten on read anyway.
 *
 * <p>{@code categories} is the classification category set this run was prepped under, captured at
 * prep time. {@link ShardValidator} accepts only these names, via {@link #categoryNames()}. So a run
 * is judged against the rules it was sifted under, never against whatever config holds when its
 * shards finally arrive. Those two moments can be days apart while an external agent works. Without
 * this field, editing a category would fail every waiting run that named it, with cause and symptom
 * on different screens and different days.
 *
 * <p>Whole cards rather than names, because a name alone cannot render a sifting prompt. An
 * automated provider needs each card's "what belongs here" description. The set it judges a
 * response against has to be the set it asked the model to use. Names alone would leave the prompt
 * reading live config while validation read the run, so the two could disagree about what a
 * category even means.
 *
 * <p>{@code unreviewable} lists every candidate this run found but could not render a judgeable
 * tile for, whether undecodable or with real pixels below the reviewable floor. Entries are
 * absolute paths, never mentioned in any montage or sidecar. Without this, a skipped file would
 * leave no trace anywhere on disk. Prep only reports the list and never moves anything, which is
 * what keeps it side-effect-free and dry-run-safe. Where the entries eventually go is in
 * {@code app/docs/design/application/service/apply-engine.md}.
 */
public record PrepDir(
        String scope,
        List<SiftCategory> categories,
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
     * @param categories a {@link List} of {@link SiftCategory} the category cards this run was prepped under
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
        return this.categories.stream().map(SiftCategory::name).toList();
    }
}
