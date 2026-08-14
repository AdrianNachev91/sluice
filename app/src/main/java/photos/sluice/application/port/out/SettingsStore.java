package photos.sluice.application.port.out;

import java.io.UncheckedIOException;

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
     * <p>Two ways it can fail, and they are different answers. What is already stored may be
     * unreadable content that a retry will not fix. Or the storage itself may refuse. Both are
     * typed, so a surface can say the right one without reading prose out of a message.
     *
     * @param settings {@link Settings} the settings to persist
     * @throws MalformedSettingsException if what is already stored cannot be understood, so nothing
     *         can be merged into it
     * @throws UncheckedIOException if the stored settings cannot be read or written
     */
    void save(Settings settings);
}
