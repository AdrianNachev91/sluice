package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CullExceptionTest {

    @Test
    void carriesAnAggregatedMessage() {
        var exception = new CullException("montage-001 has no shard");

        assertThat(exception.getMessage()).isEqualTo("montage-001 has no shard");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void carriesAnAggregatedMessageAndAnUnderlyingCause() {
        var cause = new RuntimeException("provider call failed");
        var exception = new CullException("montage-001 has no shard", cause);

        assertThat(exception.getMessage()).isEqualTo("montage-001 has no shard");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
