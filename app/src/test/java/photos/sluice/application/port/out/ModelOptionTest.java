package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelOptionTest {

    @Test
    void anOptionWithAnIdAndALabelIsAccepted() {
        assertThatCode(() -> new ModelOption("a-model", "A model")).doesNotThrowAnyException();
    }

    @Test
    void aBlankIdIsRefused() {
        assertThatThrownBy(() -> new ModelOption("  ", "A model"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankLabelIsRefusedNamingTheModelItBelongsTo() {
        assertThatThrownBy(() -> new ModelOption("a-model", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-model");
    }

    @Test
    void rejectsNulls() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new ModelOption(null, "A model"))
                .isInstanceOf(IllegalArgumentException.class);
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new ModelOption("a-model", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
