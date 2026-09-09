package photos.sluice.application.port.in;

import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.UncheckedIOException;
import java.util.Optional;

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
     * What supplies the given setting from above the user's config file, or empty when a saved
     * value holds.
     *
     * <p>Here rather than on its own seam because a screen showing a setting has to say this beside
     * it. A save writes the file and takes effect at once, and the next launch reads the higher
     * source again. Nothing else in the app would explain that revert.
     *
     * @param property {@link String} the property name, as the app spells it in its own config file
     * @return an {@link Optional} of {@link SettingOverride} what supplies it from above
     */
    Optional<SettingOverride> higherPrecedenceOverride(String property);

    /**
     * Persists the given settings and puts them in force.
     *
     * @param settings {@link Settings} the settings to save
     * @throws LibraryRootResolutionRequiredException if these settings move a configured library
     *         root. That goes through {@link LibraryRootUseCase#moveLibraryRoot}, which is where a
     *         caller says what becomes of the hash index. Setting one for the first time saves here
     * @throws PathsMisconfiguredException if these settings move a folder root and any root they set
     *         cannot be worked in, the moved one or not. A root left unset saves, since that is what
     *         an install still choosing its folders looks like
     * @throws WorkingRootBusyException if another process holds the working root these settings name
     * @throws JobInProgressException if a job is running and these settings move a folder root
     * @throws ShuttingDownException if the app is closing and these settings move a folder root. A
     *         save that leaves every root where it found it still goes through, since it never asks
     *         the job runner for anything
     * @throws MalformedSettingsException if the stored settings cannot be understood, so nothing can
     *         be merged into them
     * @throws UncheckedIOException if the stored settings cannot be read or written, or the working
     *         root cannot be claimed or given up
     */
    void save(Settings settings);
}
