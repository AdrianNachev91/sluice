package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CommitUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitScopeSelector;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Moves every in-scope {@code Sorted} file into the library, preserving its relative folder
 * structure. Appends each moved file's hash to the library index and prunes {@code Sorted}
 * directories left empty afterward.
 */
@Component
public class CommitEngine implements CommitUseCase {

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final CommitScopeSelector scopeSelector = new CommitScopeSelector();

    /**
     * Creates a commit engine wired to its ports.
     *
     * @param pathsPort {@link PathsPort} resolves the sorted and library roots
     * @param mediaStore {@link MediaStore} moves files and prunes empty directories
     * @param sha256Port {@link Sha256Port} hashes moved files
     * @param hashIndexPort {@link HashIndexPort} records moved files in the library index
     */
    public CommitEngine(final PathsPort pathsPort, final MediaStore mediaStore, final Sha256Port sha256Port,
                        final HashIndexPort hashIndexPort) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
    }

    /**
     * Commits the given scope with no progress reporting or cancellation.
     *
     * @param scope {@link CommitScope} files to commit from Sorted
     * @return {@link CommitSummary} summary of what was committed
     */
    @Override
    public CommitSummary commit(final CommitScope scope) {
        return this.commit(scope, ProgressCallback.NO_OP, CancellationSignal.NEVER);
    }

    /**
     * Commits the given scope, reporting progress as files move.
     *
     * @param scope {@link CommitScope} files to commit from Sorted
     * @param progress {@link ProgressCallback} callback ticked per file processed
     * @return {@link CommitSummary} summary of what was committed
     */
    public CommitSummary commit(final CommitScope scope, final ProgressCallback progress) {
        return this.commit(scope, progress, CancellationSignal.NEVER);
    }

    /**
     * Moves every in-scope Sorted file into the library, updates the hash index, and prunes
     * directories left empty.
     *
     * @param scope {@link CommitScope} files to commit from Sorted
     * @param progress {@link ProgressCallback} callback ticked per file processed
     * @param cancellation {@link CancellationSignal} checked between files to allow a clean stop
     * @return {@link CommitSummary} summary of what was committed
     */
    public CommitSummary commit(final CommitScope scope, final ProgressCallback progress,
                                final CancellationSignal cancellation) {
        final Path sorted = this.pathsPort.sorted();
        final Path library = this.pathsPort.library();
        final Map<LibraryBucket, Integer> byBucket = new EnumMap<>(LibraryBucket.class);
        int committed = 0;

        // A transfer that never landed holds a prefix of a photo. Moving one into the library would
        // put it beyond reach of the next sort and index it as if it were whole.
        final List<Path> files = this.mediaStore.listFiles(sorted).stream()
                .filter(file -> !MediaStore.isIncompleteTransfer(file))
                .toList();
        final int total = files.size();
        // Counted separately from the walk, which ticks for every file so the bar reflects the work
        // of looking. Whether a stop left anything behind is a question about the chosen scope
        // alone, and a scoped run can walk a long out-of-scope tail after its last move.
        final long inScope = files.stream()
                .filter(file -> this.scopeSelector.isInScope(relativeTo(sorted, file), scope))
                .count();
        int current = 0;

        // One session for the whole move loop. Each moved file's index row is written and flushed
        // immediately, so a crash mid-run never leaves an already-moved file with no index row. The
        // header/leading-newline checks still only run once, instead of once per file.
        try (final HashIndexPort.Session session = this.hashIndexPort.openSession()) {
            // Checked after each file's move, so an in-flight file is never interrupted; already-
            // committed files stay committed, matching the no-undo model.
            while (current < total && !cancellation.isCancelled()) {
                final Path file = files.get(current);
                final String relativePath = relativeTo(sorted, file);
                if (this.scopeSelector.isInScope(relativePath, scope)) {
                    final String hash = this.sha256Port.hash(file);
                    final Path dest = this.mediaStore.move(file, library.resolve(relativePath).getParent(),
                            cancellation, TransferProgress.within(progress, current, total));
                    session.append(new IndexEntry(hash, dest));
                    // merge rather than a pre-seeded zero per bucket: a scoped commit (e.g. one
                    // year) never touches most buckets. byBucket should only ever report the
                    // ones this run actually populated.
                    byBucket.merge(LibraryBucket.ofFirstSegment(firstSegment(relativePath)), 1, Integer::sum);
                    committed++;
                }
                progress.tick(++current, total);
            }
        } catch (final TransferAbandonedException e) {
            // The abandoned file is still in Sorted and never reached the index, since its row is
            // appended after its own move. Every file before it is in the library and indexed, and
            // the session closes on the way out here, so the summary below is what actually landed.
        }

        // Runs regardless of whether the pass above was cancelled. It only ever removes
        // directories that are genuinely empty, so a partial run leaves nothing for it to do wrong.
        this.mediaStore.removeEmptyDirectories(sorted);

        return new CommitSummary(committed, (int) inScope - committed, byBucket, committed < inScope);
    }

    /**
     * A file's path relative to Sorted, in the forward-slash form the scope selector and the
     * library layout both read.
     *
     * @param sorted {@link Path} the Sorted root
     * @param file {@link Path} a file under it
     * @return {@link String} the relative path
     */
    private static String relativeTo(final Path sorted, final Path file) {
        return sorted.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Extracts the first path segment (top-level folder) from a relative path.
     *
     * @param relativePath {@link String} forward-slash relative path
     * @return {@link String} the first path segment, or the whole path if there's no slash
     */
    private static String firstSegment(final String relativePath) {
        final int slash = relativePath.indexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(0, slash);
    }
}
