package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.TroubleshootReport;

import java.util.List;

/**
 * The wire shape for what a troubleshoot pass found, and the reading that builds it.
 */
public final class TroubleshootPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private TroubleshootPayloads() {
    }

    /**
     * One finding still open once the AUTO pass has run, with how a caller answers it.
     *
     * @param finding {@link FindingPayload} the finding itself
     * @param key {@link String} the key {@code answer} takes it by, or null when it carries no answer
     * @param options a {@link List} of {@link String} the option ids it accepts, empty when it carries none
     */
    public record OpenFindingPayload(FindingPayload finding, @Nullable String key, List<String> options) {
    }

    /**
     * What a troubleshoot pass found.
     *
     * @param state {@link PrepDirHealth.State} the run's state once the AUTO pass has run
     * @param indexRebuilt boolean whether a corrupt index was rebuilt
     * @param strayShardsRepaired a {@link List} of {@link String} stray shards renamed into place
     * @param open a {@link List} of {@link OpenFindingPayload} every finding still open
     */
    public record ReportPayload(PrepDirHealth.State state, boolean indexRebuilt,
                                List<String> strayShardsRepaired, List<OpenFindingPayload> open) {
    }

    /**
     * Reads a troubleshoot pass onto the wire.
     *
     * @param report {@link TroubleshootReport} what the pass found
     * @return {@link ReportPayload} its machine-readable shape
     */
    public static ReportPayload of(final TroubleshootReport report) {
        return new ReportPayload(report.after().state(), report.indexRebuilt(), report.strayShardsRepaired(),
                report.after().findings().stream().map(TroubleshootPayloads::openFindingPayload).toList());
    }

    /**
     * Reads one open finding onto the wire, alongside how a caller answers it.
     *
     * @param finding {@link Finding} the finding to report
     * @return {@link OpenFindingPayload} its machine-readable shape
     */
    private static OpenFindingPayload openFindingPayload(final Finding finding) {
        return new OpenFindingPayload(FindingPayload.of(finding), AnswerVocabulary.keyFor(finding),
                AnswerVocabulary.optionsFor(finding));
    }
}
