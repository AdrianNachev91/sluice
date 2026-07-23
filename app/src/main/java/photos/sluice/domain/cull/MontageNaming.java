package photos.sluice.domain.cull;

// The one place that knows a montage id's shard filename convention (montage-NNN -> decisions-NNN.json).
// Domain, not adapter: both an adapter (JsonCullPrepStore, the vision cullers) and the application
// layer (ApplyEngine, which cannot depend on an adapter package) need this same convention.
public final class MontageNaming {

    private MontageNaming() {
    }

    public static String shardFileFor(String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }
}
