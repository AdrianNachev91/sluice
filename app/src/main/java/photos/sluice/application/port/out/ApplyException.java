package photos.sluice.application.port.out;

import photos.sluice.domain.cull.Finding;

import java.util.List;

// Thrown by an apply-side engine when the prep directory's shards do not validate cleanly from
// scratch. That means a missing shard, an off-contract decision, or a decision whose file is
// neither on disk nor already recorded as applied.
// The message carries every problem found, aggregated, so a bad run
// is fixed in one pass instead of one error per re-run. findings() carries the same problems
// structured, so a failed apply and PrepDirDoctor's own proactive diagnosis feed the identical UI
// panel. Checked, because this is an expected, recoverable outcome: fix the shards (or re-run the
// culler) and apply again. Zero files are ever moved when this is thrown.
public class ApplyException extends Exception {

    private final List<Finding> findings;

    /**
     * Creates the exception with an aggregated problem message and the structured findings behind it.
     *
     * @param message {@link String} the aggregated validation problems
     * @param findings a {@link List} of {@link Finding} the structured findings behind the message
     */
    public ApplyException(String message, List<Finding> findings) {
        super(message);
        this.findings = List.copyOf(findings);
    }

    /**
     * The structured findings behind this exception's aggregated message.
     *
     * @return a {@link List} of {@link Finding} the findings
     */
    public List<Finding> findings() {
        return findings;
    }
}
