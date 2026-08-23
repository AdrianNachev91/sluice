package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliLauncherTest {

    @Test
    void springArgsImportsTheConfigFile() {
        final Path configFile = Path.of("somewhere", "config.yml");

        final String[] args = CliLauncher.springArgs(configFile, new String[0]);

        assertThat(args).containsExactly("--spring.config.import=optional:file:" + configFile);
    }

    // A fresh install has no config file and the command still has to run.
    @Test
    void springArgsImportsTheConfigFileOptionally() {
        final String[] args = CliLauncher.springArgs(Path.of("config.yml"), new String[0]);

        assertThat(args[0]).contains("optional:");
    }

    @Test
    void springArgsKeepsTheUsersOwnArgumentsAfterTheImport() {
        final String[] args = CliLauncher.springArgs(Path.of("config.yml"),
                new String[]{"sort", "2019", "--sluice.paths.inbox=/photos"});

        assertThat(args).containsExactly("--spring.config.import=optional:file:config.yml",
                "sort", "2019", "--sluice.paths.inbox=/photos");
    }

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

    // Spring's own switches carry no dotted name, so the dot alone would send them to the parser,
    // which refuses them as unknown options.
    @Test
    void springsOwnDotlessSwitchesAreNotParsedAsFlags() {
        final String[] args = CliLauncher.commandArgs(new String[]{"--debug", "sort", "--trace"});

        assertThat(args).containsExactly("sort");
    }

    // Past the end-of-options marker the user is naming values, not options. Filtering there would
    // delete an argument silently, which is worse than refusing it.
    @Test
    void nothingIsFilteredAfterTheEndOfOptionsMarker() {
        final String[] args = CliLauncher.commandArgs(
                new String[]{"rescue", "--", "--2019.06", "--debug"});

        assertThat(args).containsExactly("rescue", "--", "--2019.06", "--debug");
    }

    // The half a blanket "ignore anything unrecognised" would have thrown away.
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

    // The whole boot, end to end. The profile activates, the command bean and the factory that
    // builds it are both there, and the exit code comes back out. The override rides along to prove
    // the parser never sees it: one that did would refuse the run and the exit code would say so.
    // What reaches Spring is the separate concern springArgs covers.
    @Test
    void aRunBootsTheCommandLineAndAnswersWithItsExitCode(@TempDir final Path dir) {
        final int exitCode = CliLauncher.run(dir.resolve("config.yml"),
                new String[]{"--help", "--sluice.paths.inbox=" + dir});

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
    }
}
