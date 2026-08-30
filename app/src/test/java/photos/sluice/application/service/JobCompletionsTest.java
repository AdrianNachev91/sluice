package photos.sluice.application.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JobCompletionsTest {

    @Test
    void everyListenerIsToldWhenAJobFinishes() {
        final var completions = new JobCompletions();
        final var first = new AtomicInteger();
        final var second = new AtomicInteger();
        completions.onFinished(first::incrementAndGet);
        completions.onFinished(second::incrementAndGet);

        completions.finished();

        assertThat(first).hasValue(1);
        assertThat(second).hasValue(1);
    }

    @Test
    void nobodyIsToldUntilAJobFinishes() {
        final var completions = new JobCompletions();
        final var told = new AtomicInteger();
        completions.onFinished(told::incrementAndGet);

        assertThat(told).hasValue(0);
    }

    @Test
    void aListenerThatThrowsStopsNeitherTheOthersNorTheCaller() {
        final var completions = new JobCompletions();
        final var reached = new AtomicInteger();
        completions.onFinished(() -> {
            throw new IllegalStateException("this screen is gone");
        });
        completions.onFinished(reached::incrementAndGet);

        assertThatCode(completions::finished).doesNotThrowAnyException();
        assertThat(reached).hasValue(1);
    }
}
