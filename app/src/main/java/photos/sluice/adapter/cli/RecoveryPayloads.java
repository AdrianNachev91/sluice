package photos.sluice.adapter.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import photos.sluice.domain.sift.DiscardReport;
import photos.sluice.domain.sift.PrepDirHealth;
import photos.sluice.domain.sift.PurgeReport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes for giving up on a run and for clearing the finished ones, and the readings that
 * build them.
 *
 * <p>A path leaves as text spelled the way this machine spells it. Handing a serializer a
 * {@link Path} writes it as a URI instead. No other field on this surface reads that way, and a
 * caller cannot hand one back as an argument.
 */
public final class RecoveryPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private RecoveryPayloads() {
    }

    /**
     * What a discard filed away.
     *
     * @param graveyard {@link String} where the run's records were filed
     * @param shardsSetAside int how many judged sheets went with them
     */
    public record DiscardPayload(String graveyard, int shardsSetAside) {
    }

    /**
     * What one sweep of the finished runs cleared.
     *
     * @param purged a {@link List} of {@link String} scope tags cleared this sweep
     * @param skipped a {@link Map} of {@link String} to {@link PrepDirHealth.State} scopes left
     *        alone, and the state that kept each
     * @param unreadable a {@link Map} of {@link String} to {@link String} scopes the sweep could
     *        not reason about, and why
     * @param unlistableRoot {@link String} the root that could not be read at all, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PurgePayload(List<String> purged, Map<String, PrepDirHealth.State> skipped,
                               Map<String, String> unreadable, @Nullable String unlistableRoot) {
    }

    /**
     * Reads a discard's own report into the shape a caller receives.
     *
     * @param report {@link DiscardReport} what the discard filed away
     * @return {@link DiscardPayload} the payload
     */
    static DiscardPayload discarded(final DiscardReport report) {
        return new DiscardPayload(report.graveyard().toString(), report.shardsSetAside());
    }

    /**
     * Reads a sweep's own report into the shape a caller receives.
     *
     * @param report {@link PurgeReport} what the sweep cleared
     * @return {@link PurgePayload} the payload
     */
    static PurgePayload purged(final PurgeReport report) {
        final Path root = report.unlistableRoot();
        return new PurgePayload(report.purged(), report.skipped(), report.unreadable(),
                root == null ? null : root.toString());
    }
}
