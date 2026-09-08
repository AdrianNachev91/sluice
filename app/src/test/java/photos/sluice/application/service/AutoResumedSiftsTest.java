package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.CullJobOutcome;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoResumedSiftsTest {

    private final AutoResumedSifts sifts = new AutoResumedSifts();

    @Test
    void everyListenerHearsTheRunsNameAndItsJob() {
        final var heard = new ArrayList<String>();
        final JobHandle<CullJobOutcome> job = neverFinishes();
        final var jobs = new ArrayList<JobHandle<CullJobOutcome>>();
        this.sifts.onResumed((scope, resumed) -> {
            heard.add(scope);
            jobs.add(resumed);
        });
        this.sifts.onResumed((scope, _) -> heard.add(scope));

        this.sifts.resumed("2019-06", job);

        assertThat(heard).containsExactly("2019-06", "2019-06");
        assertThat(jobs).containsExactly(job);
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheNextOneHearing() {
        final var heard = new ArrayList<String>();
        this.sifts.onResumed((_, _) -> {
            throw new IllegalStateException("a screen that could not draw");
        });
        this.sifts.onResumed((scope, _) -> heard.add(scope));

        assertThatCode(() -> this.sifts.resumed("2019", neverFinishes())).doesNotThrowAnyException();

        assertThat(heard).containsExactly("2019");
    }

    @Test
    void nothingIsAnnouncedToAListenerRegisteredAfterTheResume() {
        final var heard = new ArrayList<String>();

        this.sifts.resumed("2019", neverFinishes());
        this.sifts.onResumed((scope, _) -> heard.add(scope));

        assertThat(heard).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<CullJobOutcome> neverFinishes() {
        final JobHandle<CullJobOutcome> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        return handle;
    }
}
