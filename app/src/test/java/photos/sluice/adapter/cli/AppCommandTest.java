package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class AppCommandTest {

    @Test
    void aStrayYearIsRefusedInThisAppsOwnWordsRatherThanTheParsersDefault() {
        final CliHarness.Result result = this.run("app", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(result.err()).contains("app takes no arguments")
                .doesNotContain("Unmatched argument");
    }

    @Test
    void aStrayOptionIsRefusedInThoseSameWords() {
        assertThat(this.run("app", "--json").err()).contains("app takes no arguments");
    }

    @Test
    void theUsageHelpOffersNoArgumentToGiveIt() {
        final CliHarness.Result result = this.run("app", "--help");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(result.out().lines().findFirst()).hasValue("Usage: sluice app [-h] [--json] [--quiet]");
    }

    private CliHarness.Result run(final String... args) {
        return CliHarness.run(CliHarness.parser(new AppCommand()), args);
    }
}
