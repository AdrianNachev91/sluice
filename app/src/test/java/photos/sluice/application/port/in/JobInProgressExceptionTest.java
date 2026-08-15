package photos.sluice.application.port.in;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobInProgressExceptionTest {

    @Test
    void aBusyRefusalIsAnIllegalStateException() {
        assertThat(new JobInProgressException("a job is running")).isInstanceOf(IllegalStateException.class);
    }
}
