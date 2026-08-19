package photos.sluice.application.port.out;

import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.util.List;

/**
 * The effect boundary the application layer and vision adapters use to read the cull
 * configuration, so neither imports the config record that supplies it. The settings bean
 * implements this by exposing the values it already binds.
 */
public interface CullSettings {

    /**
     * Id of the vision provider to route a cull through, matched against what each
     * {@link VisionCuller} describes itself as.
     *
     * @return {@link String} the configured provider id
     */
    String provider();

    /**
     * The configured classification category cards. Every classification decision's category must
     * be one of the card names; ShardValidator checks that. Automated vision providers also render
     * each card's description into their culling prompt.
     *
     * @return a {@link List} of {@link CullCategory} the configured category cards
     */
    List<CullCategory> categories();

    /**
     * Connection settings for the provider {@link #provider()} names. For a surface reporting on
     * what is configured right now.
     *
     * @return {@link CullProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    CullProviderSettings providerSettings();

    /**
     * Connection settings for one named provider, whichever one is in force.
     *
     * <p>What a culler reads about itself. A screen can offer to test a selection the user has not
     * saved yet. A provider asking for "the settings in force" would then read another provider's
     * endpoint.
     *
     * @param providerId {@link String} the provider whose settings to read
     * @return {@link CullProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    CullProviderSettings providerSettings(String providerId);

    /**
     * Tuning for the external-agent provider only. An implementation must return a non-null
     * instance, substituting a MANUAL-mode default for a missing configured block; see
     * ExternalAgentSettings' own doc for its own null-handling.
     *
     * @return {@link ExternalAgentSettings} the external-agent tuning settings
     */
    ExternalAgentSettings externalAgent();

    /**
     * The contact-sheet grid a cull renders: tile size, and how many tiles form a row. Asked for at
     * the moment it is needed, so a saved change reaches a cull without a restart.
     *
     * @return {@link MontageConfig} the montage grid configuration
     */
    MontageConfig montage();
}
