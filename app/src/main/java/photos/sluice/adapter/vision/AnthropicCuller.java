package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.PrepDir;

// Declared placeholder for the Anthropic API provider. This flavor calls the user's own configured
// vision model synchronously inside cull() and writes the shards from its responses, all behind the
// same port signature the external-agent flavor uses. The call itself is not implemented yet.
// Registered anyway so the provider id is reserved and selecting it fails loud with an honest
// "not implemented". The dispatcher's generic "no culler registered" would read as a bug instead.
@Component
class AnthropicCuller implements VisionCuller {

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public void cull(PrepDir prep, CullOptions opts) {
        throw new UnsupportedOperationException(
                "The 'anthropic' vision provider is not implemented yet. Use provider 'external-agent'.");
    }
}
