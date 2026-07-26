package photos.sluice.domain.cull;

// The one place that knows a montage id's shard filename convention (montage-NNN -> decisions-NNN.json).
// Domain, not adapter: both an adapter (JsonCullPrepStore, the vision cullers) and the application
// layer (ApplyEngine, which cannot depend on an adapter package) need this same convention.
public final class MontageNaming {

    /**
     * Prevents instantiation of this utility class.
     */
    private MontageNaming() {
    }

    /**
     * Derives a montage id's shard filename.
     *
     * @param montage {@link String} the montage id (e.g. montage-003)
     * @return {@link String} the corresponding shard filename
     */
    public static String shardFileFor(String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }
}
