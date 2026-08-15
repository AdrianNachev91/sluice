package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StartupFailurePresenterTest {

    @Test
    void detailReachesPastAWrapperToTheMessageWorthReading() {
        final var wrapped = new IllegalStateException("Error creating bean with name 'pathsConfig'",
                new IllegalStateException("sluice.paths.inbox is not configured."));

        final var presenter = new StartupFailurePresenter(wrapped);

        assertThat(presenter.detail()).isEqualTo("sluice.paths.inbox is not configured.");
    }

    @Test
    void detailReadsAFailureThatWrapsNothing() {
        final var presenter = new StartupFailurePresenter(new IllegalStateException("the disk went away"));

        assertThat(presenter.detail()).isEqualTo("the disk went away");
    }

    // A cause chain deeper than one link is the ordinary shape, not the exception. Bean wiring adds
    // a layer of its own on top of whatever the framework already wrapped.
    @Test
    void detailReachesPastMoreThanOneWrapper() {
        final var wrapped = new IllegalStateException("outer",
                new IllegalStateException("middle", new IllegalStateException("the real problem")));

        final var presenter = new StartupFailurePresenter(wrapped);

        assertThat(presenter.detail()).isEqualTo("the real problem");
    }

    // Depth alone would take the technical failure at the bottom and drop the sentence written for
    // the person reading it. A refused working root is exactly that shape: the message worth
    // showing is on the outer failure, and the cause underneath it says nothing at all.
    @Test
    void detailKeepsAnAuthoredMessageOverADeeperCauseThatSaysNothing() {
        final var wrapped = new WorkingRootBusyException(Path.of("any-root"), new IllegalStateException());

        final var presenter = new StartupFailurePresenter(wrapped);

        assertThat(presenter.detail()).isEqualTo(wrapped.getMessage());
    }

    // A failure with no message would otherwise render an empty window, which says less than the
    // exception's own name does.
    @Test
    void detailNamesTheFailureTypeWhenNothingCarriesAMessage() {
        final var presenter = new StartupFailurePresenter(new NullPointerException());

        assertThat(presenter.detail()).startsWith("NullPointerException")
                .contains("No further detail was reported.");
    }

    @Test
    void detailNamesTheFailureTypeWhenTheMessageIsBlank() {
        final var presenter = new StartupFailurePresenter(new IllegalStateException("   "));

        assertThat(presenter.detail()).startsWith("IllegalStateException");
    }

    // Returning at all is the proof: an unbounded walk never reaches the assertion.
    //
    // The timeout needs its own thread. The default mode measures how long a test took and reports
    // once it returns, so a method that never returns is never measured and the suite hangs.
    //
    // Which message wins is left open. In a loop that falls out of where the bound lands, so
    // pinning it would pin the parity of a number chosen for headroom.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    void detailReturnsOnACauseChainThatLoopsBackOnItself() {
        final var inner = new IllegalStateException("the inner one");
        final var outer = new IllegalStateException("the outer one", inner);
        // Closes the loop. A cycle cannot be built out of constructors alone, since each end would
        // have to exist before the other.
        inner.initCause(outer);

        final var presenter = new StartupFailurePresenter(outer);

        assertThat(presenter.detail()).isIn("the outer one", "the inner one");
    }

    @Test
    void headlineNamesWhatHappened() {
        final var presenter = new StartupFailurePresenter(new IllegalStateException("anything"));

        assertThat(presenter.headline()).isEqualTo("Sluice could not start.");
    }
}
