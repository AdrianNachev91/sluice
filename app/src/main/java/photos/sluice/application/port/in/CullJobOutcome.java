package photos.sluice.application.port.in;

import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.job.WaitingCullJob;

// What one cull() or resume() call produced. Applied means a complete (or allowPartial-waived)
// shard set came back and apply ran to completion this call. Waiting means one of several things:
// the external-agent provider found the shard set still incomplete (the normal manual-mode
// pause). Or an automated provider's cull was cancelled mid-run once resumable state already
// existed (a shard on disk, or a completed prep with nothing dispatched yet). Cancel is
// effectively Pause for cull in both those cases: whatever was already written stays on disk, and
// resume() continues from there. Cancelled is the one case with nothing to resume. The run stopped
// before montage rendering ever finished, so no prep dir exists yet to derive a WaitingCullJob
// from. None of these three is a failure. Every case releases the run slot rather than holding it
// open.
public sealed interface CullJobOutcome {

    record Applied(CullReport cullReport, ApplyReport applyReport) implements CullJobOutcome {
    }

    record Waiting(WaitingCullJob job) implements CullJobOutcome {
    }

    record Cancelled() implements CullJobOutcome {
    }
}
