package photos.sluice.application.port.in;

import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.job.WaitingCullJob;

/**
 * What one {@code cull()} or {@code resume()} call produced.
 *
 * <p>{@link Applied} means a complete (or allowPartial-waived) shard set came back, and apply ran
 * to completion during this call.
 *
 * <p>{@link Waiting} covers two situations. The external-agent provider found the shard set still
 * incomplete, the normal manual-mode pause. Or an automated provider's cull was cancelled mid-run
 * once resumable state already existed, either a shard already on disk or a completed prep with
 * nothing dispatched yet. In both cases whatever was already written stays on disk, and
 * {@code resume()} continues from there.
 *
 * <p>{@link Cancelled} is the one case with nothing to resume. The run stopped before montage
 * rendering finished, so no prep dir exists yet to derive a {@link WaitingCullJob} from.
 *
 * <p>None of these three outcomes is a failure. Every case releases the run slot instead of
 * holding it open.
 */
public sealed interface CullJobOutcome {

    /**
     * A cull run that completed: a usable shard set came back and apply moved the resulting
     * files.
     *
     * @param cullReport {@link CullReport} what the vision pass decided
     * @param applyReport {@link ApplyReport} what applying those decisions actually did
     */
    record Applied(CullReport cullReport, ApplyReport applyReport) implements CullJobOutcome {
    }

    /**
     * A cull run that paused rather than finished.
     *
     * @param job {@link WaitingCullJob} the paused job's resumable state
     */
    record Waiting(WaitingCullJob job) implements CullJobOutcome {
    }

    /**
     * A cull run that was cancelled before montage rendering finished, leaving nothing on disk
     * to resume.
     */
    record Cancelled() implements CullJobOutcome {
    }
}
