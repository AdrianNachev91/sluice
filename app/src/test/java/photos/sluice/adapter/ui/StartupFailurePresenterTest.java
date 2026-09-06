package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.StartupFailureCard.BusyRoot;
import photos.sluice.adapter.ui.StartupFailureCard.Generic;
import photos.sluice.adapter.ui.StartupFailureCard.RejectedInFile;
import photos.sluice.adapter.ui.StartupFailureCard.Unparsable;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.UnusableSettings;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartupFailurePresenterTest {

    private static final String GENERIC = "Sluice hit a problem it has no explanation for. Report this as a "
            + "bug in Sluice.";

    @Test
    void headlineNamesWhatHappened() {
        final var presenter = new StartupFailurePresenter(new Unclassified("trace"));

        assertThat(presenter.headline()).isEqualTo("Sluice could not start.");
    }

    @Test
    void aBusyWorkingRootDrawsTheBusyRootCard() {
        final var presenter = new StartupFailurePresenter(new WorkingRootBusy(Path.of("any-root"), "trace"));

        assertThat(presenter.card()).isInstanceOf(BusyRoot.class);
    }

    @Test
    void aRejectedSettingWithASpotDrawsTheConfigCardNamingTheFileAndPosition() {
        final var spot = new ConfigSpot(Path.of("config.yml"), new ConfigPosition(3, 16));
        final var presenter = new StartupFailurePresenter(
                new RejectedSetting("sluice.montage.tile-size", spot, "trace"));

        assertThat(presenter.card()).isEqualTo(new RejectedInFile(
                "Sluice could not use the setting sluice.montage.tile-size. This usually means the value "
                        + "there has a typo, or is not the kind of value this setting expects. Removing it "
                        + "resets just this one setting to Sluice's own default. Everything else you have "
                        + "configured stays as it is. If the value looks right to you, report this as a bug "
                        + "in Sluice.",
                "sluice.montage.tile-size", "config.yml", "line 3, column 16", "trace"));
    }

    @Test
    void aRejectedSettingWithNoSpotDrawsTheGenericCardNamingTheOverridingEnvironmentVariable() {
        final var presenter = new StartupFailurePresenter(
                new RejectedSetting("sluice.montage.tile-size", null, "trace"));

        assertThat(presenter.card()).isEqualTo(new Generic(
                "Sluice could not use the setting sluice.montage.tile-size. It did not come from your settings "
                        + "file. Check for an environment variable named SLUICE_MONTAGE_TILE_SIZE, or report this "
                        + "as a bug in Sluice if you have not set one.",
                "trace"));
    }

    @Test
    void settingsTheAppRefusesDrawTheirOwnSentenceRatherThanTheReportABugCard() {
        final var presenter = new StartupFailurePresenter(new UnusableSettings(
                "Photo categories may not hold one called 'junk'. Sluice supplies that one itself, "
                        + "and it is always on.", "trace"));

        assertThat(presenter.card()).isEqualTo(new Generic(
                "Photo categories may not hold one called 'junk'. Sluice supplies that one itself, and it "
                        + "is always on.",
                "trace"));
    }

    @Test
    void anUnparsableConfigFileDrawsTheConfigCardWithTheParsersOwnProblem() {
        final var spot = new ConfigSpot(Path.of("config.yml"), null);
        final var presenter = new StartupFailurePresenter(
                new UnparsableConfigFile(spot, "expected ',' or ']'", "trace"));

        assertThat(presenter.card()).isEqualTo(new Unparsable(
                "Sluice could not read your settings file. It likely has a typo or a formatting mistake, "
                        + "often a missing or extra bracket or quote mark. Starting fresh renames the file "
                        + "aside rather than deleting it, and Sluice starts over with "
                        + "nothing configured. You can still open the old file afterward in a text editor to "
                        + "copy anything you typed by hand, like your category descriptions.",
                "config.yml", null, "expected ',' or ']'", "trace"));
    }

    @Test
    void anUnclassifiedFailureDrawsTheGenericCard() {
        final var presenter = new StartupFailurePresenter(new Unclassified("trace"));

        assertThat(presenter.card()).isEqualTo(new Generic(GENERIC, "trace"));
    }
}
