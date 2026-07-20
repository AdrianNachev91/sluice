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
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.ArrayList;
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
        Path sorted = pathsPort.sorted();
        Path library = pathsPort.library();
        Map<LibraryBucket, Integer> byBucket = new EnumMap<>(LibraryBucket.class);
        List<IndexEntry> entries = new ArrayList<>();

        for (Path file : mediaStore.listFiles(sorted)) {
            String relativePath = sorted.relativize(file).toString().replace('\\', '/');
            if (scopeSelector.isInScope(relativePath, scope)) {
                String hash = sha256Port.hash(file);
                Path dest = mediaStore.move(file, library.resolve(relativePath).getParent());
                entries.add(new IndexEntry(hash, dest));
                // merge rather than a pre-seeded zero per bucket: a scoped commit (e.g. one year)
                // never touches most buckets, so byBucket should only ever report the ones this
                // run actually populated.
                byBucket.merge(LibraryBucket.ofFirstSegment(firstSegment(relativePath)), 1, Integer::sum);
            }
        }

        hashIndexPort.append(entries);
        mediaStore.removeEmptyDirectories(sorted);

        return new CommitSummary(entries.size(), byBucket);
    }

    private static String firstSegment(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(0, slash);
    }
}
