package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeavingUnsavedTest {

    @Test
    void theQuestionLeadsWithStaying() {
        assertThat(LeavingUnsaved.question().goAheadLeads()).isFalse();
        assertThat(LeavingUnsaved.question().cancel()).isEqualTo("Stay");
    }
}
