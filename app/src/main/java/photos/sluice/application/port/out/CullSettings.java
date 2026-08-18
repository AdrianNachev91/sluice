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
     * Connection settings for API-backed providers. An implementation must return a non-null
     * instance, substituting an all-null-fields instance for a missing configured block; its fields
     * are null when unset.
     *
     * @return {@link CullProviderSettings} the provider connection settings
     */
    CullProviderSettings providerSettings();

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
