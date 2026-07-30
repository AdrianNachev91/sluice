package photos.sluice.domain.dedup;

import photos.sluice.domain.model.HashedMedia;
import photos.sluice.domain.model.MediaFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Partitions a batch of hashed media into keepers to sort, files byte-identical to something
 * already in the library, and byte-identical duplicates within the batch itself.
 *
 * <p>Produces a {@link DedupPlan} only. No file is deleted here; the caller acts on
 * {@code redundantVsLibrary} and {@code withinBatchDuplicates}.
 */
public final class ByteIdenticalDedup {

    /**
     * The three-way split produced by {@link ByteIdenticalDedup#plan}: files to route to Sorted,
     * files redundant against the library, and duplicates found within the batch itself.
     */
    public record DedupPlan(List<MediaFile> toSort, List<MediaFile> redundantVsLibrary,
                            List<MediaFile> withinBatchDuplicates) {
        /**
         * Makes the three file lists immutable.
         *
         * @param toSort a {@link List} of {@link MediaFile} keeper files to route to Sorted
         * @param redundantVsLibrary a {@link List} of {@link MediaFile} files byte-identical to something already in
         * the library
         * @param withinBatchDuplicates a {@link List} of {@link MediaFile} byte-identical duplicates within this batch
         */
        public DedupPlan {
            toSort = List.copyOf(toSort);
            redundantVsLibrary = List.copyOf(redundantVsLibrary);
            withinBatchDuplicates = List.copyOf(withinBatchDuplicates);
        }
    }

    /**
     * Partitions hashed media into keepers, library-redundant files, and in-batch duplicates.
     *
     * @param media a {@link List} of {@link HashedMedia} the hashed media to partition
     * @param libraryHashes a {@link Set} of {@link String} hashes already present in the library
     * @return {@link DedupPlan} the resulting dedup plan
     */
    public DedupPlan plan(final List<HashedMedia> media, final Set<String> libraryHashes) {
        final List<MediaFile> toSort = new ArrayList<>();
        final List<MediaFile> redundantVsLibrary = new ArrayList<>();
        final List<MediaFile> withinBatchDuplicates = new ArrayList<>();
        final Set<String> seenInBatch = new HashSet<>();

        for (final HashedMedia hashed : media) {
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
