package photos.sluice.application.port.in;

import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.job.WaitingCullJob;

// What one cull() or resume() call produced. Applied means a complete (or allowPartial-waived)
// shard set came back and apply ran to completion this call - an automated provider (Anthropic,
// OpenAI, Ollama) always resolves here or throws. Waiting means the external-agent provider found
// the shard set still incomplete: the normal manual-mode pause, not a failure, and the run slot
// was released rather than held open.
public sealed interface CullJobOutcome {

    record Applied(CullReport cullReport, ApplyReport applyReport) implements CullJobOutcome {
    }

    record Waiting(WaitingCullJob job) implements CullJobOutcome {
    }
}
