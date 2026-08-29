package photos.sluice.domain.rescue;

import java.util.List;

/**
 * Outcome counters from one rescue run.
 *
 * <p>A file with no plausible date is left in place and counted in {@code skipped} rather than
 * {@code rescued}. It takes no action the user didn't ask for.
 *
 * <p>{@code folderRemoved} is true only when every file rescue looked at was actually rescued, so
 * nothing was left behind to keep the folder alive.
 *
 * <p>{@code cancelled} is the run's own account of whether it stopped short.
 */
public record RescueSummary(int rescued, List<String> skipped, boolean folderRemoved, boolean cancelled) {

    /**
     * Defensively copies the skipped list.
     *
     * @param rescued int count of files rescued
     * @param skipped a {@link List} of {@link String} names of files left in place, with reasons
     * @param folderRemoved boolean true if the source folder was removed
     * @param cancelled boolean whether the run gave up before reaching every file
     */
    public RescueSummary {
        skipped = List.copyOf(skipped);
    }
}
