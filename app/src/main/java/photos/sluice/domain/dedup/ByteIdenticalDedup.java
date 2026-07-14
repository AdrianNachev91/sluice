package photos.sluice.domain.dedup;

import photos.sluice.domain.model.HashedMedia;
import photos.sluice.domain.model.MediaFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Partitions a batch of hashed media into keepers to sort, files byte-identical to something
// already in the library, and byte-identical duplicates within the batch itself. Produces a plan
// only - no file is deleted here; the caller acts on redundantVsLibrary/withinBatchDuplicates.
public final class ByteIdenticalDedup {

    public record DedupPlan(List<MediaFile> toSort, List<MediaFile> redundantVsLibrary,
                             List<MediaFile> withinBatchDuplicates) {
        public DedupPlan {
            toSort = List.copyOf(toSort);
            redundantVsLibrary = List.copyOf(redundantVsLibrary);
            withinBatchDuplicates = List.copyOf(withinBatchDuplicates);
        }
    }

    public DedupPlan plan(List<HashedMedia> media, Set<String> libraryHashes) {
        List<MediaFile> toSort = new ArrayList<>();
        List<MediaFile> redundantVsLibrary = new ArrayList<>();
        List<MediaFile> withinBatchDuplicates = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();

        for (HashedMedia hashed : media) {
            // Library redundancy is checked before in-batch dedup: a hash already present in the
            // library goes to redundantVsLibrary even if it also repeats within the batch, so
            // every matching occurrence lands there rather than only the first.
            if (libraryHashes.contains(hashed.sha256())) {
                redundantVsLibrary.add(hashed.file());
            } else if (!seenInBatch.add(hashed.sha256())) {
                // Set.add returns false when the hash was already present, so this one call both
                // checks and records "have we seen this hash before" - reaching this branch means
                // it had.
                withinBatchDuplicates.add(hashed.file());
            } else {
                toSort.add(hashed.file());
            }
        }
        return new DedupPlan(toSort, redundantVsLibrary, withinBatchDuplicates);
    }
}
