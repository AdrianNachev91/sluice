package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliLauncherTest {

    @Test
    void aVerbAndItsArgumentsAreParsed() {
        final String[] args = CliLauncher.commandArgs(new String[]{"sort", "2019", "--months=6-8"});

        assertThat(args).containsExactly("sort", "2019", "--months=6-8");
    }

    @Test
    void aSettingOverrideIsNotParsedAsAFlag() {
        final String[] args = CliLauncher.commandArgs(
                new String[]{"sort", "--sluice.paths.inbox=/photos", "2019"});

        assertThat(args).containsExactly("sort", "2019");
    }

    // Spring accepts a setting written with no value at all, so both spellings have to be filtered.
    @Test
    void aSettingOverrideWithNoValueIsNotParsedAsAFlagEither() {
        final String[] args = CliLauncher.commandArgs(new String[]{"--sluice.cull.provider", "cull"});

        assertThat(args).containsExactly("cull");
    }

    @Test
    void springsOwnDotlessSwitchesAreNotParsedAsFlags() {
        final String[] args = CliLauncher.commandArgs(new String[]{"--debug", "sort", "--trace"});

        assertThat(args).containsExactly("sort");
    }

    @Test
    void nothingIsFilteredAfterTheEndOfOptionsMarker() {
        final String[] args = CliLauncher.commandArgs(
                new String[]{"rescue", "--", "--2019.06", "--debug"});

        assertThat(args).containsExactly("rescue", "--", "--2019.06", "--debug");
    }

    @Test
    void aMistypedFlagStillReachesTheParser() {
        final String[] args = CliLauncher.commandArgs(new String[]{"sort", "--month=6-8"});

        assertThat(args).containsExactly("sort", "--month=6-8");
    }

    @Test
    void aValueThatHappensToHoldADotIsNotMistakenForASetting() {
        final String[] args = CliLauncher.commandArgs(new String[]{"rescue", "Review/2019.06"});

        assertThat(args).containsExactly("rescue", "Review/2019.06");
    }

    // The setting override rides along to prove the parser never sees it: one that did would refuse
    // the run, and the exit code would say so.
    @Test
    void aRunBootsTheCommandLineAndAnswersWithItsExitCode(@TempDir final Path dir) {
        final int exitCode = CliLauncher.run(dir.resolve("config.yml"),
                new String[]{"--help", "--sluice.paths.inbox=" + dir});

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
    }

    // This failure kills the context before a command runs, so nothing narrower than the whole boot
    // reaches it.
    @Test
    void aConfigFileTheParserGivesUpOnIsRefusedRatherThanCrashingTheProcess(@TempDir final Path dir)
            throws IOException {
        final Path config = dir.resolve("config.yml");
        Files.writeString(config, "sluice:\n  paths:\n    working-root: \"unclosed");

        final int exitCode = CliLauncher.run(config, new String[]{"runs"});

        assertThat(exitCode).isEqualTo(3);
    }

    // Reordering the two property writes below the builder would put the whole report back on the
    // output stream with a green suite.
    @Test
    void theLoggingPropertiesAreInForceOnceARunHasStarted(@TempDir final Path dir) {
        CliLauncher.run(dir.resolve("config.yml"), new String[]{"--help"});

        assertThat(System.getProperty("sluice.log.stream")).isEqualTo("System.err");
        assertThat(System.getProperty("sluice.log.startupFailure")).isEqualTo("OFF");
    }
}
