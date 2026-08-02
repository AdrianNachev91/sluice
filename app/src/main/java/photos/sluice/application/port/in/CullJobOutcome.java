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
 * can end, cancellation included, is reachable with the archive already done. A result card that
 * did not mention it would leave the user's previous record looking like it vanished.
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
     * A cull run that completed: a usable shard set came back and apply moved the resulting
     * files.
     *
     * @param cullReport {@link CullReport} what the vision pass decided
     * @param applyReport {@link ApplyReport} what applying those decisions actually did
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Applied(CullReport cullReport, ApplyReport applyReport,
                   @Nullable Path archivedPriorRun) implements CullJobOutcome {
    }

    /**
     * A cull run that paused rather than finished.
     *
     * @param job {@link WaitingCullJob} the paused job's resumable state
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Waiting(WaitingCullJob job, @Nullable Path archivedPriorRun) implements CullJobOutcome {
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
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Blocked(WaitingCullJob job, List<Finding> findings,
                   @Nullable Path archivedPriorRun) implements CullJobOutcome {

        /**
         * Defensively copies the findings, so a caller cannot mutate an outcome after the fact.
         *
         * @param job {@link WaitingCullJob} the blocked job's own scope, prep dir and shard tally
         * @param findings a {@link List} of {@link Finding} every problem apply's validation refused on
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
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Cancelled(@Nullable Path archivedPriorRun) implements CullJobOutcome {
    }
}
