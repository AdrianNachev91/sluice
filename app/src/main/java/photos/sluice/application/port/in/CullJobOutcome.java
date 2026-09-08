package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.util.List;

/**
 * What one {@code cull()} or {@code resume()} call produced.
 *
 * <p>{@link Applied} means a complete (or allowPartial-waived) shard set came back, and apply ran
 * to completion during this call.
 *
 * <p>{@link Waiting} and {@link Blocked} are the two non-terminal states, and they differ by whose
 * move comes next. Waiting means shards are still missing, so somebody else has work to do. The
 * external agent is still culling, or an automated run stopped part way. Blocked means every
 * montage has a shard and apply's validation refused anyway. Nothing further is coming on its own,
 * so the next move is the user's: troubleshoot, or repair by hand and resume.
 *
 * <p>{@link Cancelled} is the one case with nothing to resume. The run stopped before montage
 * rendering finished, so no prep dir exists yet to derive a {@link WaitingCullJob} from.
 *
 * <p>None of these four outcomes is a failure. Every case releases the run slot instead of holding
 * it open.
 *
 * <p>Every case also carries {@code archivedPriorRun}, non-null when starting this run archived a
 * completed run of the same scope into the graveyard. It sits on all four rather than just
 * {@link Applied} because the archive happens before montage rendering. So every later way the run
 * can end, cancellation included, is reachable with the archive already done.
 *
 * <p>Whether to report it is each surface's own decision rather than something this type asks for.
 * The command line names the folder, having no other way to show one. The desktop deliberately says
 * nothing about it: housekeeping that succeeded is not news, nothing was lost, and the folder is
 * one the reader never chose.
 *
 * <p>Every case carries {@code cullReport} for the same reason, one axis over. Three of the four
 * are reachable after the vision pass has already called a model, so a spend attaches to them.
 * {@link Cancelled} is reached only before anything is dispatched, so its report is a zero one.
 * It answers the question rather than being excused from it.
 */
public sealed interface CullJobOutcome {

    /**
     * The graveyard directory a completed run of this scope was archived into on the way in, or
     * null when the scope was free.
     *
     * @return {@link Path} the graveyard directory, or null
     */
    @Nullable Path archivedPriorRun();

    /**
     * What the vision pass judged and consumed before the run ended this way.
     *
     * @return {@link CullReport} the run's own cull report
     */
    CullReport cullReport();

    /**
     * A cull run that completed: a usable shard set came back and apply moved the resulting
     * files.
     *
     * <p>{@code cullReport} covers the call that ended the run, and {@code tokensAcrossEveryLeg}
     * covers the run. A sift stopped at its spending limit and then continued is billed once per
     * call. A continue that finds every sheet already judged is billed nothing at all. So the
     * report alone answers what the last call cost rather than what the run cost.
     *
     * @param cullReport {@link CullReport} what the vision pass decided
     * @param applyReport {@link ApplyReport} what applying those decisions actually did
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     * @param tokensAcrossEveryLeg every token this run spent, over all of its legs, or null where
     *     the ledger holding them could not be read
     */
    record Applied(CullReport cullReport, ApplyReport applyReport,
                   @Nullable Path archivedPriorRun,
                   @Nullable Long tokensAcrossEveryLeg) implements CullJobOutcome {
    }

    /**
     * A cull run that paused rather than finished.
     *
     * @param job {@link WaitingCullJob} the paused job's resumable state
     * @param reason {@link WaitingReason} why it paused
     * @param cullReport {@link CullReport} what the vision pass decided and consumed before pausing
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     * @param movedBeforeItPaused {@link ApplyReport} what apply had already moved, where it had
     *     begun at all. Null on every pause reached before apply starts, which is most of them.
     *     Nothing else on this outcome carries it
     */
    record Waiting(WaitingCullJob job, WaitingReason reason, CullReport cullReport,
                   @Nullable Path archivedPriorRun,
                   @Nullable ApplyReport movedBeforeItPaused) implements CullJobOutcome {

        /**
         * A pause that came before apply moved anything.
         *
         * @param job {@link WaitingCullJob} the paused job's resumable state
         * @param reason {@link WaitingReason} why it paused
         * @param cullReport {@link CullReport} what the vision pass decided and consumed
         * @param archivedPriorRun {@link Path} the graveyard a prior completed run went to, or null
         */
        public Waiting(final WaitingCullJob job, final WaitingReason reason, final CullReport cullReport,
                       final @Nullable Path archivedPriorRun) {
            this(job, reason, cullReport, archivedPriorRun, null);
        }
    }

    /**
     * A cull run whose shard set is complete but whose apply refused to carry it out.
     *
     * <p>The findings are the same typed list {@code ApplyException} carries. A run card, a
     * troubleshoot screen and the CLI shim all render from this one source rather than from parsed
     * message text.
     *
     * @param job {@link WaitingCullJob} the blocked job's own scope, prep dir and shard tally
     * @param findings a {@link List} of {@link Finding} every problem apply's validation refused on
     * @param cullReport {@link CullReport} what the vision pass decided and consumed before apply refused
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Blocked(WaitingCullJob job, List<Finding> findings, CullReport cullReport,
                   @Nullable Path archivedPriorRun) implements CullJobOutcome {

        /**
         * Defensively copies the findings, so a caller cannot mutate an outcome after the fact.
         *
         * @param job {@link WaitingCullJob} the blocked job's own scope, prep dir and shard tally
         * @param findings a {@link List} of {@link Finding} every problem apply's validation refused on
         * @param cullReport {@link CullReport} what the vision pass decided and consumed before apply refused
         * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
         */
        public Blocked {
            findings = List.copyOf(findings);
        }
    }

    /**
     * A cull run that was cancelled before montage rendering finished, leaving nothing on disk
     * to resume.
     *
     * @param cullReport {@link CullReport} the report for a run that reached no model
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Cancelled(CullReport cullReport, @Nullable Path archivedPriorRun) implements CullJobOutcome {
    }
}
