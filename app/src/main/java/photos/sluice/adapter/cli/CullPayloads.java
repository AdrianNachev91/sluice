package photos.sluice.adapter.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes for everything a sift produces, and the readings that build them.
 *
 * <p>One class, because they are one contract. A caller reads a run against a run that produced it,
 * and both name the same scope, the same prep dir and the same spend.
 *
 * <p>Every reading below is a switch or a field read over a type the core already settled. Nothing
 * here parses a message or invents a value. So a caller acts on what the engine decided, rather
 * than on how this surface happened to word it.
 *
 * <p>A path leaves as text and an instant leaves in the format the rest of the world writes them
 * in. Both are what a caller needs to hand back, so neither may depend on how a library happens to
 * be configured.
 */
public final class CullPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private CullPayloads() {
    }

    /**
     * One sift run as it currently sits on disk.
     *
     * @param scope {@link String} the scope tag, which is also how a command addresses this run
     * @param prepDir {@link String} the run's own folder
     * @param state {@link PrepDirHealth.State} how far along, or how badly off, the run is
     * @param findings a {@link List} of {@link FindingPayload} every problem still open on it
     * @param shards {@link ShardsPayload} how many shards are in, or null when nothing could count them
     * @param since {@link String} when the run was last written to
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunPayload(String scope, String prepDir, PrepDirHealth.State state,
                             List<FindingPayload> findings, @Nullable ShardsPayload shards, String since) {
    }

    /**
     * How many of a run's shards are in.
     *
     * @param present int shard files found, whether or not they parse
     * @param valid int those of them that also pass validation
     * @param total int how many the run expects altogether
     */
    public record ShardsPayload(int present, int valid, int total) {
    }

    /**
     * What one sift consumed, and who consumed it.
     *
     * @param inputTokens long input tokens consumed
     * @param outputTokens long output tokens consumed
     * @param providerId {@link String} the provider that ran
     * @param modelId {@link String} the model that ran, or null for a provider calling none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpendPayload(long inputTokens, long outputTokens, String providerId, @Nullable String modelId) {
    }

    /**
     * What one sift judged and what it cost.
     *
     * @param montagesCulled int sheets this run got fresh judgement for
     * @param montagesSkipped int sheets it got none for
     * @param apiCalls int calls made against the provider's model
     * @param spend {@link SpendPayload} what those calls consumed
     * @param stoppedAtCeiling boolean whether the run's own spend ceiling ended it
     */
    public record ReportPayload(int montagesCulled, int montagesSkipped, int apiCalls, SpendPayload spend,
                                boolean stoppedAtCeiling) {
    }

    /**
     * What a sift is expected to consume, before it starts.
     *
     * <p>Both flags ride along because a bare pair of numbers cannot be told apart from a measured
     * one. A caller budgeting against a seeded figure is budgeting against a guess, and nothing
     * else on this record says so.
     *
     * @param inputTokens long input tokens expected
     * @param outputTokens long output tokens expected
     * @param exactInput boolean whether the input figure was counted against the real request
     * @param historicOutput boolean whether the output figure came from runs on this install
     */
    public record EstimatePayload(long inputTokens, long outputTokens, boolean exactInput, boolean historicOutput) {
    }

    /**
     * What applying a sift's decisions actually moved.
     *
     * @param reviewed int photos in the run's own scope
     * @param byCategory a {@link Map} of {@link String} to {@link Integer} files routed per category
     * @param unreviewable int photos left unjudged
     * @param nearDupGroups int near-duplicate groups resolved
     * @param nearDupRejects int near-duplicate rejects moved
     * @param heals a {@link List} of {@link String} paths corrected against a unique sheet basename
     */
    public record ApplyPayload(int reviewed, Map<String, Integer> byCategory, int unreviewable, int nearDupGroups,
                               int nearDupRejects, List<String> heals) {
    }

    /**
     * How one sift ended, and everything that ending carries.
     *
     * <p>One shape for every ending, so a caller reads {@code outcome} and then only the fields
     * that ending fills in. The report is on all of them: a run that was cancelled or stopped by
     * its ceiling was still billed for what it had already sent.
     *
     * @param outcome {@link String} which ending this was
     * @param report {@link ReportPayload} what the run judged and consumed
     * @param scope {@link String} the run's scope tag, or null when no run reached disk
     * @param prepDir {@link String} the run's own folder, or null when no run reached disk
     * @param shards {@link ShardsPayload} how many shards are in, or null when there is no run to count
     * @param reason {@link WaitingReason} why it paused, or null when it did not
     * @param findings a {@link List} of {@link FindingPayload} what refused the apply, or null when
     *        nothing did
     * @param applied {@link ApplyPayload} what was moved, or null when nothing was
     * @param archivedPriorRun {@link String} where a previous run of this scope was set aside, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutcomePayload(String outcome, ReportPayload report, @Nullable String scope,
                                 @Nullable String prepDir, @Nullable ShardsPayload shards,
                                 @Nullable WaitingReason reason, @Nullable List<FindingPayload> findings,
                                 @Nullable ApplyPayload applied, @Nullable String archivedPriorRun) {
    }

    /**
     * Reads one run onto the wire.
     *
     * @param run {@link CullRunSummary} the run as it sits on disk
     * @return {@link RunPayload} its machine-readable shape
     */
    public static RunPayload run(final CullRunSummary run) {
        return new RunPayload(run.scope(), text(run.prepDir()), run.health().state(),
                findings(run.health().findings()), shards(run.shards()), text(run.since()));
    }

    /**
     * Reads how one sift ended onto the wire.
     *
     * @param outcome {@link CullJobOutcome} how the run ended
     * @return {@link OutcomePayload} its machine-readable shape
     */
    public static OutcomePayload outcome(final CullJobOutcome outcome) {
        final ReportPayload report = report(outcome.cullReport());
        final String archived = text(outcome.archivedPriorRun());
        return switch (outcome) {
            case final CullJobOutcome.Applied done -> new OutcomePayload("Applied", report, null, null, null,
                    null, null, applied(done.applyReport()), archived);
            case final CullJobOutcome.Waiting waiting -> waiting(waiting, report, archived);
            case final CullJobOutcome.Blocked blocked -> new OutcomePayload("Blocked", report,
                    blocked.job().scope(), text(blocked.job().prepDir()), shards(blocked.job().shards()),
                    null, findings(blocked.findings()), null, archived);
            case final CullJobOutcome.Cancelled ignored -> new OutcomePayload("Cancelled", report, null, null,
                    null, null, null, null, archived);
        };
    }

    /**
     * Reads a paused run onto the wire, naming the job it paused in the middle of.
     *
     * @param waiting {@link CullJobOutcome.Waiting} the paused run
     * @param report {@link ReportPayload} what it consumed before pausing
     * @param archived {@link String} where a previous run of this scope was set aside, or null
     * @return {@link OutcomePayload} its machine-readable shape
     */
    private static OutcomePayload waiting(final CullJobOutcome.Waiting waiting, final ReportPayload report,
                                          final @Nullable String archived) {
        final WaitingCullJob job = waiting.job();
        return new OutcomePayload("Waiting", report, job.scope(), text(job.prepDir()), shards(job.shards()),
                waiting.reason(), null, null, archived);
    }

    /**
     * Reads what a run judged and consumed onto the wire.
     *
     * @param report {@link CullReport} the run's own report
     * @return {@link ReportPayload} its machine-readable shape
     */
    public static ReportPayload report(final CullReport report) {
        return new ReportPayload(report.montagesCulled(), report.montagesSkipped(), report.apiCalls(),
                spend(report.spend()), report.stoppedAtCeiling());
    }

    /**
     * Reads a spend onto the wire.
     *
     * @param spend {@link TokenSpend} what was consumed
     * @return {@link SpendPayload} its machine-readable shape
     */
    public static SpendPayload spend(final TokenSpend spend) {
        return new SpendPayload(spend.inputTokens(), spend.outputTokens(), spend.providerId(), spend.modelId());
    }

    /**
     * Reads an expectation onto the wire.
     *
     * @param estimate {@link SpendEstimate} what a run is expected to consume
     * @return {@link EstimatePayload} its machine-readable shape
     */
    public static EstimatePayload estimate(final SpendEstimate estimate) {
        return new EstimatePayload(estimate.inputTokens(), estimate.outputTokens(), estimate.exactInput(),
                estimate.historicOutput());
    }

    /**
     * Reads what an apply moved onto the wire.
     *
     * @param report {@link ApplyReport} what the apply did
     * @return {@link ApplyPayload} its machine-readable shape
     */
    public static ApplyPayload applied(final ApplyReport report) {
        return new ApplyPayload(report.reviewed(), report.byCategory(), report.unreviewable(),
                report.nearDupGroups(), report.nearDupRejects(), report.heals());
    }

    /**
     * Reads a list of problems onto the wire.
     *
     * @param findings a {@link List} of {@link Finding} the problems
     * @return a {@link List} of {@link FindingPayload} their machine-readable shapes
     */
    private static List<FindingPayload> findings(final List<Finding> findings) {
        return findings.stream().map(FindingPayload::of).toList();
    }

    /**
     * Reads a shard count onto the wire.
     *
     * @param shards {@link ShardTally} the count, or null when nothing could compute one
     * @return {@link ShardsPayload} its machine-readable shape, or null
     */
    private static @Nullable ShardsPayload shards(final @Nullable ShardTally shards) {
        return shards == null ? null : new ShardsPayload(shards.present(), shards.valid(), shards.total());
    }

    /**
     * Writes a path the way this machine spells it.
     *
     * @param path {@link Path} the path, or null
     * @return {@link String} the path as text, or null
     */
    private static @Nullable String text(final @Nullable Path path) {
        return path == null ? null : path.toString();
    }

    /**
     * Writes an instant the way the rest of the world writes them.
     *
     * @param instant {@link Instant} the moment
     * @return {@link String} it, in the format everything else reads
     */
    private static String text(final Instant instant) {
        return instant.toString();
    }
}
