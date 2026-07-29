package photos.sluice.application.port.out;

import java.util.List;

/**
 * The effect boundary the application layer and vision adapters use to read the cull
 * configuration, so neither imports the config record that supplies it. The settings bean
 * implements this by exposing the values it already binds.
 */
public interface CullSettings {

    /**
     * Id of the vision provider to route a cull through, matched against each VisionCuller.id().
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
     * Connection settings for API-backed providers. Never null; its fields are null when unset.
     *
     * @return {@link CullProviderSettings} the provider connection settings
     */
    CullProviderSettings providerSettings();

    /**
     * Tuning for the external-agent provider only. Never null; see ExternalAgentSettings' own doc.
     *
     * @return {@link ExternalAgentSettings} the external-agent tuning settings
     */
    ExternalAgentSettings externalAgent();
}
