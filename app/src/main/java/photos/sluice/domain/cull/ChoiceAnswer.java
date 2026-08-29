package photos.sluice.domain.cull;

import java.nio.file.Path;

/**
 * One answer a user gives to a damaged run, as a value rather than as a call.
 *
 * <p>It exists so both surfaces name the same options, and the command line serializes it directly.
 *
 * <p>Its variants are not only the ones whose {@link Finding} declares
 * {@link Finding.Remedy#CHOICE}. Both {@link SetAsideStrayShard} and throwing a run away are reachable for a
 * finding that declared {@link Finding.Remedy#AUTO} and whose repair then refused. A screen rendering only
 * the CHOICE ones would show an empty list for a run that is stuck.
 *
 * <p>Throwing a run away is the one answer that is not here. It moves files and reports what it
 * moved, so it stays a background job.
 */
public sealed interface ChoiceAnswer {

    /**
     * Gives up on a source file the run expected to find and did not.
     *
     * <p>The file itself is never touched. Should it reappear in Sorted, a later sift of that scope
     * meets it fresh.
     *
     * @param source {@link Path} the missing file, as the finding named it
     */
    record SkipMissingSource(Path source) implements ChoiceAnswer {}

    /**
     * Settles which of two conflicting listings wins for one file.
     *
     * @param file {@link Path} the file the overlap concerns
     * @param resolution {@link OverlapResolution} the listing that wins
     */
    record ResolveOverlap(Path file, OverlapResolution resolution) implements ChoiceAnswer {}

    /**
     * Settles a montage whose sidecar could not be read.
     *
     * <p>Keyed by montage rather than by file, which is the one disposition this app records for a
     * whole batch at once.
     *
     * @param montage {@link String} the montage id the finding named
     * @param resolution {@link CorruptSidecarResolution} which way the batch goes
     */
    record ResolveCorruptSidecar(String montage, CorruptSidecarResolution resolution) implements ChoiceAnswer {}

    /**
     * Files a stray shard away rather than guessing which montage it belongs to.
     *
     * <p>Carries the finding itself, because the repair reads more of it than a file path.
     *
     * @param strayShard {@link Finding.StrayShard} the finding being answered
     */
    record SetAsideStrayShard(Finding.StrayShard strayShard) implements ChoiceAnswer {}
}
