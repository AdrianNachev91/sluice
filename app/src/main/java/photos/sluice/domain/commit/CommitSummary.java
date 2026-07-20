package photos.sluice.domain.commit;

import java.util.Map;

// Outcome counters from one commit run. committed always equals the sum of byBucket's values -
// every in-scope file lands in exactly one LibraryBucket, and gets exactly one appended index row.
public record CommitSummary(int committed, Map<LibraryBucket, Integer> byBucket) {

    public CommitSummary {
        byBucket = Map.copyOf(byBucket);
    }
}
