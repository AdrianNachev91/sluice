package photos.sluice.application.port.in;

import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.UncheckedIOException;

/**
 * Reading and changing the app's settings. The one entry point a settings screen, or a future
 * non-interactive caller, goes through.
 *
 * <p>Saving is more than writing a file. It also claims the folder the new settings name, and puts
 * the new values in force without a restart. Those three steps have one order that leaves nothing
 * half-done, so they live behind one call rather than in each caller.
 */
public interface SettingsUseCase {

    /**
     * The settings the app is running on right now, for a screen to show and edit.
     *
     * @return {@link Settings} the current settings
     */
    Settings settings();

    /**
     * Persists the given settings and puts them in force.
     *
     * @param settings {@link Settings} the settings to save
     * @throws PathsMisconfiguredException if a folder root these settings move is set to somewhere
     *         that cannot be worked in. A root left unset saves, since that is what an install
     *         still choosing its folders looks like
     * @throws WorkingRootBusyException if another process holds the working root these settings name
     * @throws JobInProgressException if a job is running and these settings move a folder root
     * @throws ShuttingDownException if the app is closing and these settings move a folder root. A
     *         save that leaves every root where it found it still goes through, since it never asks
     *         the job runner for anything
     * @throws MalformedSettingsException if the stored settings cannot be understood, so nothing can
     *         be merged into them
     * @throws UncheckedIOException if the stored settings cannot be read or written
     */
    void save(Settings settings);
}
