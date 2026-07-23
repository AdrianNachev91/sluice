package photos.sluice.adapter.vision;

// The one place that knows a montage id's shard filename convention (montage-NNN -> decisions-NNN.json).
final class MontageNaming {

    private MontageNaming() {
    }

    static String shardFileFor(String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }
}
