// Vision providers. Each culler implements VisionCuller and registers as a Spring component.
// CullDispatcher receives every registered culler by list injection, so a new provider needs no
// wiring beyond its own class. Future OpenAI and Ollama adapters are planned under the ids
// "openai" and "ollama". For a new API-backed provider, AnthropicCuller is the reference
// implementation to copy.
@NullMarked
package photos.sluice.adapter.vision;

import org.jspecify.annotations.NullMarked;
