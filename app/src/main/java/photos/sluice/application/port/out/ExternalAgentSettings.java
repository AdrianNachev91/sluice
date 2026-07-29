package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.job.WatchMode;

import java.time.Duration;

/**
 * Tuning for the external-agent provider only. Every other provider ignores this. The {@code mode}
 * field selects how a waiting cull proceeds. It either needs an explicit Resume ({@code MANUAL},
 * the default), or gets polled by {@code Pipeline} until every shard is present and valid
 * ({@code WATCH}).
 *
 * <p>{@code watchTimeout} is null by default, meaning a watch never gives up on its own. When set,
 * it only stops polling after that long with no fully-valid tally. It never fails the job, and
 * every shard already dropped stays exactly where it is.
 */
public record ExternalAgentSettings(WatchMode mode, @Nullable Duration watchTimeout) {

    /**
     * Defaults mode to MANUAL when unset.
     *
     * @param mode {@link WatchMode} which polling mode a waiting cull uses
     * @param watchTimeout {@link Duration} how long WATCH polls before giving up, or null to never give up
     */
    public ExternalAgentSettings {
        // Spring can bind null here when the property is absent; the IDE can't model that
        // reflective path and reads the guard as always-false.
        //noinspection ConstantValue
        if (mode == null) {
            mode = WatchMode.MANUAL;
        }
    }
}
