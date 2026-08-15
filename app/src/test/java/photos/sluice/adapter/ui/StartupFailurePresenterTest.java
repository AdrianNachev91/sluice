package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartupFailurePresenterTest {

    private static final String GENERIC = "Sluice hit a problem it has no explanation for.";

    @Test
    void aBusyWorkingRootSaysAnotherSluiceIsRunning() {
        final var presenter = new StartupFailurePresenter(new WorkingRootBusy(Path.of("any-root"), "trace"));

        assertThat(presenter.detail()).isEqualTo("Another Sluice process is already running: close it and try again.");
    }

    @Test
    void aFailureWithNoCopyOfItsOwnSaysSo() {
        final var presenter = new StartupFailurePresenter(new Unclassified("trace"));

        assertThat(presenter.detail()).isEqualTo(GENERIC);
    }

    // The app is holding the property name at this point, so a sentence claiming it has no
    // explanation would be untrue of the very value being read.
    @Test
    void aRejectedSettingNamesTheSettingItRefused() {
        final var spot = new ConfigSpot(Path.of("config.yml"), new ConfigPosition(3, 16));
        final var presenter = new StartupFailurePresenter(
                new RejectedSetting("sluice.montage.tile-size", spot, "trace"));

        assertThat(presenter.detail()).contains("sluice.montage.tile-size").isNotEqualTo(GENERIC);
    }

    @Test
    void anUnparsableConfigFileSaysTheSettingsFileCouldNotBeRead() {
        final var spot = new ConfigSpot(Path.of("config.yml"), null);
        final var presenter = new StartupFailurePresenter(
                new UnparsableConfigFile(spot, "expected ',' or ']'", "trace"));

        assertThat(presenter.detail()).isEqualTo("Sluice could not read your settings file.");
    }

    @Test
    void headlineNamesWhatHappened() {
        final var presenter = new StartupFailurePresenter(new Unclassified("trace"));

        assertThat(presenter.headline()).isEqualTo("Sluice could not start.");
    }
}
