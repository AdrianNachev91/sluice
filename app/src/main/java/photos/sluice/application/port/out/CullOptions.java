package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

// Knobs passed to VisionCuller.cull(). allowPartial waives the one-shard-per-montage requirement so a
// run can proceed with some montages unculled. timeout is meant to bound how long an automated
// provider waits on its own work. Providers honor these unevenly and each documents its own take:
// the external culler ignores timeout and never blocks, and the anthropic culler wires neither yet.
public record CullOptions(boolean allowPartial, @Nullable Duration timeout) {
}
