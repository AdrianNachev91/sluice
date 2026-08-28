package photos.sluice.application.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunChangesTest {

    @Test
    void everyListenerIsToldWhenARunMoves() {
        final var changes = new RunChanges();
        final var first = new AtomicInteger();
        final var second = new AtomicInteger();
        changes.onMoved(first::incrementAndGet);
        changes.onMoved(second::incrementAndGet);

        changes.moved();

        assertThat(first).hasValue(1);
        assertThat(second).hasValue(1);
    }

    @Test
    void nobodyIsToldUntilSomethingMoves() {
        final var changes = new RunChanges();
        final var told = new AtomicInteger();
        changes.onMoved(told::incrementAndGet);

        assertThat(told).hasValue(0);
    }

    @Test
    void aListenerThatThrowsStopsNeitherTheOthersNorTheCaller() {
        final var changes = new RunChanges();
        final var reached = new AtomicInteger();
        changes.onMoved(() -> {
            throw new IllegalStateException("this screen is gone");
        });
        changes.onMoved(reached::incrementAndGet);

        assertThatCode(changes::moved).doesNotThrowAnyException();
        assertThat(reached).hasValue(1);
    }
}
