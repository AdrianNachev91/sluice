package photos.sluice.adapter.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.ConfigFileRepairPort;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;
import photos.sluice.application.startup.StartupFailureClassifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiBootstrapTest {

    @BeforeEach
    @AfterEach
    void forgetWhatOtherTestsInstalled() {
        UiBootstrap.clear();
    }

    @Test
    void aPresentedFailureIsClassifiedByWhatWasInstalled() {
        final var busy = new WorkingRootBusy(Path.of("any-root"), "trace");
        UiBootstrap.install(new FixedClassifier(busy), new UnusedRepair());

        final StartupFailurePresenter presenter = UiBootstrap.reportAndPresent(
                new IllegalStateException("anything"));

        assertThat(presenter.card().detail()).contains("Another Sluice process is already running");
    }

    @Test
    void theClassifierSeesTheFailureItWasGiven() {
        final var classifier = new FixedClassifier(new Unclassified("trace"));
        UiBootstrap.install(classifier, new UnusedRepair());
        final var failure = new IllegalStateException("the disk went away");

        UiBootstrap.reportAndPresent(failure);

        assertThat(classifier.classified).containsExactly(failure);
    }

    @Test
    void aWindowStillExplainsItselfWhenNothingWasInstalled() {
        final StartupFailurePresenter presenter = UiBootstrap.reportAndPresent(
                new IllegalStateException("anything"));

        assertThat(presenter.card().detail()).isEqualTo(
                "Sluice hit a problem it has no explanation for. Report this as a bug in Sluice.");
    }

    @Test
    void theRepairIsHandedOnAsInstalled() {
        final var repair = new UnusedRepair();
        UiBootstrap.install(new FixedClassifier(new Unclassified("trace")), repair);

        assertThat(UiBootstrap.repair()).isSameAs(repair);
    }

    @Test
    void thereIsNoRepairBeforeAnythingIsInstalled() {
        assertThat(UiBootstrap.repair()).isNull();
    }

    @Test
    void removeSettingReachesTheInstalledRepair() {
        UiBootstrap.install(new FixedClassifier(new Unclassified("trace")), FixedRepair.removing(true));

        assertThat(UiBootstrap.removeSetting("sluice.montage.tile-size")).isTrue();
    }

    @Test
    void removeSettingAnswersFalseRatherThanThrowWhenNothingWasInstalled() {
        assertThat(UiBootstrap.removeSetting("sluice.montage.tile-size")).isFalse();
    }

    @Test
    void setAsideReachesTheInstalledRepair() {
        final var movedTo = Path.of("config.broken-2026-08-16_00-00-00.yml");
        UiBootstrap.install(new FixedClassifier(new Unclassified("trace")), FixedRepair.settingAside(movedTo));

        assertThat(UiBootstrap.setAside()).isEqualTo(movedTo);
    }

    @Test
    void setAsideRefusesRatherThanThrowANullPointerWhenNothingWasInstalled() {
        assertThatThrownBy(UiBootstrap::setAside).isInstanceOf(IllegalStateException.class);
    }

    private static final class FixedClassifier implements StartupFailureClassifier {

        private final StartupFailure answer;
        private final List<Throwable> classified = new ArrayList<>();

        private FixedClassifier(final StartupFailure answer) {
            this.answer = answer;
        }

        @Override
        public StartupFailure classify(final Throwable failure) {
            this.classified.add(failure);
            return this.answer;
        }
    }

    private record FixedRepair(boolean removed, Path movedTo) implements ConfigFileRepairPort {

        private static FixedRepair removing(final boolean removed) {
            return new FixedRepair(removed, Path.of("unused"));
        }

        private static FixedRepair settingAside(final Path movedTo) {
            return new FixedRepair(false, movedTo);
        }

        @Override
        public boolean removeSetting(final String property) {
            return this.removed;
        }

        @Override
        public Path setAside() {
            return this.movedTo;
        }
    }

    // Nothing here calls the repair. The bodies throw, so a holder doing anything with it other
    // than handing it back is caught.
    private static final class UnusedRepair implements ConfigFileRepairPort {

        @Override
        public boolean removeSetting(final String property) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path setAside() {
            throw new UnsupportedOperationException();
        }
    }
}
