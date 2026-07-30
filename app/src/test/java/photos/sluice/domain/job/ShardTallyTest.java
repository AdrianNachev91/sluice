package photos.sluice.domain.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardTallyTest {

    @Test
    void acceptsPresentAndValidUpToTotal() {
        final ShardTally tally = new ShardTally(2, 1, 3);

        assertThat(tally.present()).isEqualTo(2);
        assertThat(tally.valid()).isEqualTo(1);
        assertThat(tally.total()).isEqualTo(3);
    }

    @Test
    void presentEqualToTotalIsAllowed() {
        final ShardTally tally = new ShardTally(3, 2, 3);

        assertThat(tally.present()).isEqualTo(3);
    }

    @Test
    void fullyResolvedTallyIsAllowed() {
        final ShardTally tally = new ShardTally(3, 3, 3);

        assertThat(tally.valid()).isEqualTo(3);
    }

    @Test
    void presentAboveTotalThrows() {
        assertThatThrownBy(() -> new ShardTally(4, 1, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validAbovePresentThrows() {
        assertThatThrownBy(() -> new ShardTally(1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCountThrows() {
        assertThatThrownBy(() -> new ShardTally(-1, 0, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
