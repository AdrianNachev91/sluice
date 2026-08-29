package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressPaceTest {

    private final AtomicLong clock = new AtomicLong();

    private final ProgressPace pace = new ProgressPace(Duration.ofSeconds(1), this.clock::get);

    @Test
    void theFirstEventOfAPhaseIsWritten() {
        this.pace.phaseStarted();

        assertThat(this.pace.due(1, 100)).isTrue();
    }

    @Test
    void anEventInsideTheIntervalIsNotWritten() {
        this.pace.phaseStarted();
        this.pace.due(1, 100);

        this.tick(Duration.ofMillis(999));

        assertThat(this.pace.due(2, 100)).isFalse();
    }

    @Test
    void anEventOnceTheIntervalHasPassedIsWritten() {
        this.pace.phaseStarted();
        this.pace.due(1, 100);

        this.tick(Duration.ofSeconds(1));

        assertThat(this.pace.due(2, 100)).isTrue();
    }

    @Test
    void theLastEventOfAPhaseIsWrittenHoweverSoonItArrives() {
        this.pace.phaseStarted();
        this.pace.due(1, 100);

        assertThat(this.pace.due(100, 100)).isTrue();
    }

    @Test
    void aPhaseWithNothingToCountTreatsEveryEventAsItsLast() {
        this.pace.phaseStarted();

        assertThat(this.pace.due(0, 0)).isTrue();
        assertThat(this.pace.due(0, 0)).isTrue();
    }

    @Test
    void theIntervalRunsFromTheLastEventWrittenRatherThanTheLastOffered() {
        this.pace.phaseStarted();
        this.pace.due(1, 100);

        this.tick(Duration.ofMillis(600));
        assertThat(this.pace.due(2, 100)).isFalse();
        this.tick(Duration.ofMillis(600));

        assertThat(this.pace.due(3, 100)).isTrue();
    }

    @Test
    void aNewPhaseWritesItsOwnFirstEventHoweverRecentlyTheLastOneWrote() {
        this.pace.phaseStarted();
        this.pace.due(1, 100);

        this.pace.phaseStarted();

        assertThat(this.pace.due(1, 100)).isTrue();
    }

    private void tick(final Duration passed) {
        this.clock.addAndGet(passed.toNanos());
    }
}
