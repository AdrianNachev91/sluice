package photos.sluice.application.port.out;

/**
 * Where settings survive a restart.
 *
 * <p>Write-only on purpose. The config file is already a property source the framework reads at
 * startup. A second reader would be a second source of truth for the same file. So the app learns
 * its settings once, on the way up, and this port only ever puts them back.
 */
public interface SettingsStore {

    /**
     * Writes the given settings so the next start reads them back. Anything else already in the
     * stored file that these settings do not cover is left alone.
     *
     * @param settings {@link Settings} the settings to persist
     */
    void save(Settings settings);
}
