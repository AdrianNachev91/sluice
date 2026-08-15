package photos.sluice.application.port.in;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShuttingDownExceptionTest {

    private static final String CLOSING = "Sluice is closing and will not take on new work.";

    @Test
    void aClosingRefusalIsAnIllegalStateException() {
        assertThat(new ShuttingDownException(CLOSING)).isInstanceOf(IllegalStateException.class);
    }

    // A caller catches the busy refusal to offer a retry. It must not catch this one, since the
    // runner is shut for the rest of the process and no retry against it can succeed.
    @Test
    void aClosingRefusalIsNotCaughtByAHandlerForABusyRunner() {
        assertThat(new ShuttingDownException(CLOSING)).isNotInstanceOf(JobInProgressException.class);
    }
}
