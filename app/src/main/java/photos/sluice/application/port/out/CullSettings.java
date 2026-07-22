package photos.sluice.application.port.out;

// The cull configuration the application layer needs, behind a port so the layer never imports the
// config record that supplies it. Currently just the selected provider id; the settings bean
// implements this by exposing the value it already binds.
public interface CullSettings {

    // Id of the vision provider to route a cull through, matched against each VisionCuller.id().
    String provider();
}
