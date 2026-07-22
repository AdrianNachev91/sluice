package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

// Knobs passed to VisionCuller.cull(). allowPartial waives the one-shard-per-montage requirement so a
// run can proceed with some montages unculled. timeout bounds how long an automated provider waits on
// its own work. The external culler ignores it and never blocks: it checks the shards once, then
// returns or throws.
public record CullOptions(boolean allowPartial, @Nullable Duration timeout) {
}
