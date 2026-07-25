package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.job.WatchMode;

import java.time.Duration;

// Tuning for the external-agent provider only - every other provider ignores this. mode selects
// whether a waiting cull needs an explicit Resume (MANUAL, the default) or is also polled by
// Pipeline until every shard is present and valid (WATCH). watchTimeout is null by default,
// meaning a watch never gives up on its own. When set, it only stops polling after that long with
// no fully-valid tally - it never fails the job, and every shard already dropped stays exactly
// where it is.
public record ExternalAgentSettings(WatchMode mode, @Nullable Duration watchTimeout) {

    public ExternalAgentSettings {
        // Spring can bind null here when the property is absent; the IDE can't model that
        // reflective path and reads the guard as always-false.
        //noinspection ConstantValue
        if (mode == null) {
            mode = WatchMode.MANUAL;
        }
    }
}
