package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartupFailureReportTest {

    private static final Path CONFIG = Path.of("C:", "Users", "someone", "AppData", "Roaming", "Sluice",
            "config.yml");

    private static final Path WORKING_ROOT = Path.of("D:", "Photos");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void aConfigFileTheParserGaveUpOnIsRefusedRatherThanCrashing() {
        final int code = this.report(false).write(new StartupFailure.UnparsableConfigFile(
                new ConfigSpot(CONFIG, new ConfigPosition(3, 16)), "found unexpected end of stream", "the trace"));

        assertThat(code).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(this.written(this.out)).isEmpty();
        assertThat(this.written(this.err))
                .contains(CONFIG.toString())
                .contains("line 3, column 16")
                .contains("found unexpected end of stream");
    }

    @Test
    void aConfigFileTheParserCouldNotEvenPlaceIsStillNamed() {
        this.report(false).write(new StartupFailure.UnparsableConfigFile(
                new ConfigSpot(CONFIG, null), "found unexpected end of stream", "the trace"));

        assertThat(this.written(this.err)).contains(CONFIG.toString()).doesNotContain("line");
    }

    @Test
    void aRejectedSettingIsNamedAlongsideWhereItSits() {
        this.report(false).write(new StartupFailure.RejectedSetting("sluice.montage.tile-size",
                new ConfigSpot(CONFIG, new ConfigPosition(7, 16)), "the trace"));

        assertThat(this.written(this.err))
                .contains("sluice.montage.tile-size")
                .contains(CONFIG.toString())
                .contains("line 7, column 16");
    }

    @Test
    void aRejectedSettingFromOutsideTheFileSaysToLookElsewhere() {
        this.report(false).write(new StartupFailure.RejectedSetting("sluice.montage.tile-size", null, "the trace"));

        assertThat(this.written(this.err)).contains("sluice.montage.tile-size")
                .doesNotContain(CONFIG.toString())
                .contains("environment");
    }

    @Test
    void aRejectedSettingsPropertyAndPlaceReachTheCallerAsFields() {
        this.report(true).write(new StartupFailure.RejectedSetting("sluice.montage.tile-size",
                new ConfigSpot(CONFIG, new ConfigPosition(7, 16)), "the trace"));

        assertThat(this.written(this.out))
                .contains("\"kind\":\"SETTING_REJECTED\"")
                .contains("\"property\":\"sluice.montage.tile-size\"")
                .contains("\"line\":7")
                .contains("\"column\":16");
    }

    @Test
    void aSecondSluiceOnTheSameFolderIsRefusedNamingThatFolder() {
        final int code = this.report(true).write(
                new StartupFailure.WorkingRootBusy(WORKING_ROOT, "the trace"));

        assertThat(code).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(this.written(this.out)).contains("\"kind\":\"WORKING_ROOT_BUSY\"")
                .contains(WORKING_ROOT.toString().replace("\\", "\\\\"));
    }

    // One refusal, two places that word it. A command meeting a held root reads the exception's own
    // sentence. A startup meeting the same thing has only the folder and a trace to work from, so
    // this class writes the sentence itself. Nothing but this stops the two drifting apart.
    @Test
    void aHeldRootIsWordedTheSameWhetherItStoppedTheStartupOrJustTheCommand() {
        this.report(false).write(new StartupFailure.WorkingRootBusy(WORKING_ROOT, "the trace"));

        assertThat(this.written(this.err).strip())
                .isEqualTo(new WorkingRootBusyException(WORKING_ROOT).getMessage());
    }

    @Test
    void aFailureNothingClassifiedTakesItsOwnCodeAndHandsOverTheTrace() {
        final int code = this.report(false).write(new StartupFailure.Unclassified("the trace"));

        assertThat(code).isEqualTo(CommandStatus.FAILED.exitCode());
        assertThat(this.written(this.err)).contains("the trace");
    }

    @Test
    void aStartupFailureNamesNoVerbBecauseNoneWasReached() {
        this.report(true).write(new StartupFailure.Unclassified("the trace"));

        assertThat(this.written(this.out)).doesNotContain("\"command\"").contains("\"status\":\"FAILED\"");
    }

    @Test
    void aRefusalReachesThePersonEvenWhenADocumentWasAskedFor() {
        this.report(true).write(new StartupFailure.UnparsableConfigFile(
                new ConfigSpot(CONFIG, new ConfigPosition(3, 16)), "found unexpected end of stream", "the trace"));

        assertThat(this.written(this.err)).contains("not valid YAML");
        assertThat(this.written(this.out)).contains("\"kind\":\"CONFIG_FILE_UNPARSABLE\"");
    }

    private StartupFailureReport report(final boolean asDocument) {
        return new StartupFailureReport(new PrintStream(this.out, true, StandardCharsets.UTF_8),
                new PrintStream(this.err, true, StandardCharsets.UTF_8), asDocument);
    }

    private String written(final ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }
}
