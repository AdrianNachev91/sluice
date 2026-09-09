// Vision providers. Each culler implements VisionCuller and registers as a Spring component.
// CullDispatcher takes them by list injection, so a new one needs no wiring beyond its own class.
// What one has to satisfy: app/docs/design/adapter/vision/adding-a-provider.md. OpenAI and Ollama
// adapters are planned under the ids "openai" and "ollama".
@NullMarked
package photos.sluice.adapter.vision;

import org.jspecify.annotations.NullMarked;
