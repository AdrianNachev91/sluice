package photos.sluice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.util.List;

/**
 * The single place the app's current settings live, from the values bound at startup until the last
 * save before it closes.
 *
 * <p>It is also the {@link CullSettings} every cull reads. That is what makes a saved setting take
 * effect without a restart: consumers hold this, and this holds a reference that a save swaps.
 *
 * <p>The reference is volatile, and a save replaces the whole settings value rather than editing
 * one field of it. So one read is never a mixture: whichever value a reader gets, every field of it
 * came from the same save. A reader wanting a set of fields to agree takes {@link #current()} once
 * and reads them off it. An accessor per field is a read per field.
 */
@Component
public class SettingsHolder implements LiveSettings, CullSettings {

    private volatile Settings current;

    /**
     * Starts from the values bound out of the config files and the environment.
     *
     * @param paths {@link PathsProperties} the bound folder roots
     * @param cull {@link CullConfig} the bound cull settings
     * @param montage {@link MontageProperties} the bound contact-sheet grid
     * @param ui {@link UiProperties} the bound look
     */
    @Autowired
    public SettingsHolder(final PathsProperties paths, final CullConfig cull, final MontageProperties montage,
                          final UiProperties ui) {
        this(boundSettings(paths, cull, montage, ui));
    }

    /**
     * Starts from a settings value directly.
     *
     * @param initial {@link Settings} the settings to start on
     */
    public SettingsHolder(final Settings initial) {
        this.current = initial;
    }

    /**
     * Maps the bound property records onto one settings value. The grid records are mapped by
     * accessor name, so a reordering of either one's fields cannot silently swap the two ints.
     *
     * @param paths {@link PathsProperties} the bound folder roots
     * @param cull {@link CullConfig} the bound cull settings
     * @param montage {@link MontageProperties} the bound contact-sheet grid
     * @param ui {@link UiProperties} the bound look
     * @return {@link Settings} the settings the app starts on
     */
    static Settings boundSettings(final PathsProperties paths, final CullConfig cull, final MontageProperties montage,
                                  final UiProperties ui) {
        return new Settings(
                new PathSettings(paths.repoRoot(), paths.libraryRoot(), paths.inbox()),
                cull.provider(), cull.providerSettings(), cull.categories(),
                new MontageConfig(montage.tileSize(), montage.tilesPerRow()), ui.theme());
    }

    /**
     * The settings in force at this moment.
     *
     * @return {@link Settings} the current settings
     */
    @Override
    public Settings current() {
        return this.current;
    }

    /**
     * Makes the given settings the ones in force.
     *
     * @param settings {@link Settings} the settings to put in force
     */
    @Override
    public void apply(final Settings settings) {
        this.current = settings;
    }

    /**
     * The id of the vision provider a cull routes through.
     *
     * @return {@link String} the configured provider id
     */
    @Override
    public String provider() {
        return this.current.provider();
    }

    /**
     * The classification cards a cull routes to.
     *
     * @return a {@link List} of {@link CullCategory} the configured category cards
     */
    @Override
    public List<CullCategory> categories() {
        return this.current.categories();
    }

    /**
     * Connection settings for the provider in force.
     *
     * @return {@link CullProviderSettings} that provider's connection settings
     */
    @Override
    public CullProviderSettings providerSettings() {
        return this.current.providerSettings();
    }

    /**
     * Connection settings for one named provider.
     *
     * @param providerId {@link String} the provider whose settings to read
     * @return {@link CullProviderSettings} that provider's connection settings
     */
    @Override
    public CullProviderSettings providerSettings(final String providerId) {
        return this.current.providerSettings(providerId);
    }

    /**
     * The contact-sheet grid a cull renders.
     *
     * @return {@link MontageConfig} the montage grid configuration
     */
    @Override
    public MontageConfig montage() {
        return this.current.montage();
    }
}
