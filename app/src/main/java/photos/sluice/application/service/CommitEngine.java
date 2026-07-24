package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CommitUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitScopeSelector;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Moves every in-scope Sorted file into the library at the same relative structure it already
// has, appends the moved files' hashes to the library index, and prunes Sorted directories left
// empty afterward.
@Component
public class CommitEngine implements CommitUseCase {

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final CommitScopeSelector scopeSelector = new CommitScopeSelector();

    public CommitEngine(PathsPort pathsPort, MediaStore mediaStore, Sha256Port sha256Port,
            HashIndexPort hashIndexPort) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
    }

    @Override
    public CommitSummary commit(CommitScope scope) {
        return commit(scope, ProgressCallback.NO_OP);
    }

    public CommitSummary commit(CommitScope scope, ProgressCallback progress) {
        Path sorted = pathsPort.sorted();
        Path library = pathsPort.library();
        Map<LibraryBucket, Integer> byBucket = new EnumMap<>(LibraryBucket.class);
        int committed = 0;

        List<Path> files = mediaStore.listFiles(sorted);
        int total = files.size();
        int current = 0;

        // One session for the whole move loop. Each moved file's index row is written and flushed
        // immediately, so a crash mid-run never leaves an already-moved file with no index row. The
        // header/leading-newline checks still only run once, instead of once per file.
        try (HashIndexPort.Session session = hashIndexPort.openSession()) {
            for (Path file : files) {
                String relativePath = sorted.relativize(file).toString().replace('\\', '/');
                if (scopeSelector.isInScope(relativePath, scope)) {
                    String hash = sha256Port.hash(file);
                    Path dest = mediaStore.move(file, library.resolve(relativePath).getParent());
                    session.append(new IndexEntry(hash, dest));
                    // merge rather than a pre-seeded zero per bucket: a scoped commit (e.g. one
                    // year) never touches most buckets, so byBucket should only ever report the
                    // ones this run actually populated.
                    byBucket.merge(LibraryBucket.ofFirstSegment(firstSegment(relativePath)), 1, Integer::sum);
                    committed++;
                }
                progress.tick(++current, total);
            }
        }

        mediaStore.removeEmptyDirectories(sorted);

        return new CommitSummary(committed, byBucket);
    }

    private static String firstSegment(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(0, slash);
    }
}
