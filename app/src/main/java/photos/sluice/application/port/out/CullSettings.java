package photos.sluice.application.port.out;

import java.util.List;

// The cull configuration the application layer and vision adapters need, behind a port so neither
// imports the config record that supplies it. The settings bean implements this by exposing the
// values it already binds.
public interface CullSettings {

    // Id of the vision provider to route a cull through, matched against each VisionCuller.id().
    String provider();

    // The configured classification category cards. Every classification decision's category must
    // be one of the card names; ShardValidator checks that. Automated vision providers also render
    // each card's description into their culling prompt.
    List<CullCategory> categories();

    // Connection settings for API-backed providers. Never null; its fields are null when unset.
    CullProviderSettings providerSettings();

    // Tuning for the external-agent provider only. Never null; see ExternalAgentSettings' own doc.
    ExternalAgentSettings externalAgent();
}
