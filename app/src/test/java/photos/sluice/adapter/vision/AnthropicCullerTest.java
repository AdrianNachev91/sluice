package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.domain.cull.PrepDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicCullerTest {

    private final AnthropicCuller culler = new AnthropicCuller();

    @Test
    void reservesTheAnthropicProviderId() {
        assertThat(culler.id()).isEqualTo("anthropic");
    }

    @Test
    void failsLoudBecauseTheProviderIsNotImplementedYet() {
        var prep = new PrepDir("2019", Path.of("base"), 0, List.of(), 0, Path.of("prep"), List.of());

        assertThatThrownBy(() -> culler.cull(prep, new CullOptions(false, null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("anthropic")
                .hasMessageContaining("external-agent");
    }
}
