package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MontageRendererTest {

    @Test
    void progressAndCancellationAwareOverloadsDefaultToThePlainBuildMethod() {
        final var prepDir = new PrepDir("2020", Path.of("base"), 5, List.of(), 1, Path.of("prep"), List.of());
        final var calls = new ArrayList<String>();
        final MontageRenderer renderer = (_, _) -> {
            calls.add("build");
            return prepDir;
        };

        final var scope = new CullScope.Year(2020, null);
        final var config = new MontageConfig(224, 5);

        assertThat(renderer.build(scope, config, ProgressCallback.NO_OP)).isSameAs(prepDir);
        assertThat(renderer.build(scope, config, ProgressCallback.NO_OP, CancellationSignal.NEVER))
                .isSameAs(prepDir);
        assertThat(calls).containsExactly("build", "build");
    }
}
