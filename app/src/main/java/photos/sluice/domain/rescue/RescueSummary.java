package photos.sluice.domain.rescue;

import java.util.List;

// Outcome counters from one rescue run. A file with no plausible date is left in place and
// counted in skipped rather than rescued - it takes no action a user didn't ask for. folderRemoved
// is true only when every file rescue looked at was actually rescued, so nothing was left behind
// to keep the folder alive.
public record RescueSummary(int rescued, List<String> skipped, boolean folderRemoved) {

    public RescueSummary {
        skipped = List.copyOf(skipped);
    }
}
