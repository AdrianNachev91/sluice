package photos.sluice.application.port.out;

import photos.sluice.domain.job.WatchMode;

/**
 * Tuning for the external-agent provider only. Every other provider ignores this. The {@code mode}
 * field selects how a waiting cull proceeds. It either needs an explicit Resume ({@code MANUAL},
 * the default), or gets polled by {@code Pipeline} until every montage's shard has arrived, then
 * resumed on its own ({@code WATCH}).
 *
 * <p>{@code mode} is the default a run starts with, not a permanent verdict. A single waiting run's
 * watch can be armed or disarmed on its own, whatever this says.
 */
public record ExternalAgentSettings(WatchMode mode) {

    /**
     * Defaults mode to MANUAL when unset.
     *
     * @param mode {@link WatchMode} which polling mode a waiting cull uses
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
