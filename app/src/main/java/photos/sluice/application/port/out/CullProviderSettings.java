package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

// Connection settings for a vision provider that calls a model API from inside the app: which model
// to request and, optionally, a non-default endpoint to send the request to. Both fields are
// nullable because a user on the external-agent provider never sets them. A provider that needs a
// field checks it at cull time, failing loud with the property name. The app still starts for
// everyone else.
public record CullProviderSettings(@Nullable String model, @Nullable String endpoint) {
}
