package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.job.WaitingSiftJob;

import java.nio.file.Path;
import java.util.List;

/**
 * What one {@code sift()} or {@code resume()} call produced.
 *
 * <p>{@link Applied} means a complete (or allowPartial-waived) shard set came back, and apply ran
 * to completion during this call.
 *
 * <p>{@link Waiting} and {@link Blocked} are the non-terminal states, and they differ by whose move
 * comes next rather than by severity. Which run reaches which, and what each leaves to do:
 * {@code app/docs/design/application/service/sift-engine.md}.
 *
 * <p>{@link Cancelled} is the one case with nothing to resume. The run stopped before montage
 * rendering finished, so no prep dir exists yet to derive a {@link WaitingSiftJob} from.
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
 *
 * <p>Every case carries {@code siftReport} for the same reason, one axis over. Three of the four
 * are reachable after the vision pass has already called a model, so a spend attaches to them.
 * {@link Cancelled} is reached only before anything is dispatched, so its report is a zero one
 * rather than an absent one.
 */
public sealed interface SiftJobOutcome {

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
     * @return {@link SiftReport} the run's own sift report
     */
    SiftReport siftReport();

    /**
     * A sift run that completed: a usable shard set came back and apply moved the resulting
     * files.
     *
     * <p>{@code siftReport} covers the call that ended the run, and {@code totalTokens}
     * covers the run. A sift stopped at its spending limit and then continued is billed once per
     * call, and a continue that finds every sheet already judged is billed nothing at all.
     *
     * @param siftReport {@link SiftReport} what the vision pass decided
     * @param applyReport {@link ApplyReport} what applying those decisions actually did
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     * @param totalTokens every token this run spent, over all of its legs, or null where
     *     the ledger holding them could not be read
     */
    record Applied(SiftReport siftReport, ApplyReport applyReport,
                   @Nullable Path archivedPriorRun,
                   @Nullable Long totalTokens) implements SiftJobOutcome {
    }

    /**
     * A sift run that paused rather than finished.
     *
     * @param job {@link WaitingSiftJob} the paused job's resumable state
     * @param reason {@link WaitingReason} why it paused
     * @param siftReport {@link SiftReport} what the vision pass decided and consumed before pausing
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     * @param movedBeforeItPaused {@link ApplyReport} what apply had already moved, where it had
     *     begun at all. Null on every pause reached before apply starts, which is most of them.
     *     Nothing else on this outcome carries it
     */
    record Waiting(WaitingSiftJob job, WaitingReason reason, SiftReport siftReport,
                   @Nullable Path archivedPriorRun,
                   @Nullable ApplyReport movedBeforeItPaused) implements SiftJobOutcome {

        /**
         * A pause that came before apply moved anything.
         *
         * @param job {@link WaitingSiftJob} the paused job's resumable state
         * @param reason {@link WaitingReason} why it paused
         * @param siftReport {@link SiftReport} what the vision pass decided and consumed
         * @param archivedPriorRun {@link Path} the graveyard a prior completed run went to, or null
         */
        public Waiting(final WaitingSiftJob job, final WaitingReason reason, final SiftReport siftReport,
                       final @Nullable Path archivedPriorRun) {
            this(job, reason, siftReport, archivedPriorRun, null);
        }
    }

    /**
     * A sift run whose shard set is complete but whose apply refused to carry it out.
     *
     * <p>The findings are the same typed list {@code ApplyException} carries, so a surface renders
     * from them rather than from parsed message text.
     *
     * @param job {@link WaitingSiftJob} the blocked job's own scope, prep dir and shard tally
     * @param findings a {@link List} of {@link Finding} every problem apply's validation refused on
     * @param siftReport {@link SiftReport} what the vision pass decided and consumed before apply refused
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Blocked(WaitingSiftJob job, List<Finding> findings, SiftReport siftReport,
                   @Nullable Path archivedPriorRun) implements SiftJobOutcome {

        /**
         * Defensively copies the findings, so a caller cannot mutate an outcome after the fact.
         *
         * @param job {@link WaitingSiftJob} the blocked job's own scope, prep dir and shard tally
         * @param findings a {@link List} of {@link Finding} every problem apply's validation refused on
         * @param siftReport {@link SiftReport} what the vision pass decided and consumed before apply refused
         * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
         */
        public Blocked {
            findings = List.copyOf(findings);
        }
    }

    /**
     * A sift run that was cancelled before montage rendering finished, leaving nothing on disk
     * to resume.
     *
     * @param siftReport {@link SiftReport} the report for a run that reached no model
     * @param archivedPriorRun {@link Path} the graveyard directory a prior completed run was archived into, or null
     */
    record Cancelled(SiftReport siftReport, @Nullable Path archivedPriorRun) implements SiftJobOutcome {
    }
}
