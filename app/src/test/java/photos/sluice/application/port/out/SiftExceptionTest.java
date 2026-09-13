package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiftExceptionTest {

    @Test
    void carriesAnAggregatedMessage() {
        final var exception = new SiftException("montage-001 has no shard");

        assertThat(exception.getMessage()).isEqualTo("montage-001 has no shard");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void carriesAnAggregatedMessageAndAnUnderlyingCause() {
        final var cause = new RuntimeException("provider call failed");
        final var exception = new SiftException("montage-001 has no shard", cause);

        assertThat(exception.getMessage()).isEqualTo("montage-001 has no shard");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
