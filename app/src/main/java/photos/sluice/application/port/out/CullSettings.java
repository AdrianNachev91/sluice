package photos.sluice.application.port.out;

import java.util.List;

// The cull configuration the application layer and vision adapters need, behind a port so neither
// imports the config record that supplies it. The settings bean implements this by exposing the
// values it already binds.
public interface CullSettings {

    // Id of the vision provider to route a cull through, matched against each VisionCuller.id().
    String provider();

    // The configured classification categories. Every classification decision's category must be a
    // member of this set; ShardValidator checks that.
    List<String> categories();
}
