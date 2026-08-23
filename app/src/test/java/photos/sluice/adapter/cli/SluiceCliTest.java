package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SluiceCliTest {

    private final CommandLine commandLine = SluiceCli.parser(new SluiceCli(), CommandLine.defaultFactory());

    @Test
    void argumentsNamingNoCommandAreRefused() {
        final CliHarness.Result result = CliHarness.run(this.commandLine);

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
    }

    @Test
    void argumentsNamingNoCommandLeaveTheOutputStreamEmpty() {
        final CliHarness.Result result = CliHarness.run(this.commandLine);

        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("No command given.");
    }

    @Test
    void anUnknownCommandIsRefused() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "sortt", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(result.err()).contains("sortt");
    }

    // The usage block runs to as many lines as there are verbs. Repeating it under every refusal
    // therefore grows with the surface, while the mistake it explains stays one line.
    @Test
    void aRefusalPointsAtTheHelpRatherThanReprintingIt() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--month=6-8");

        assertThat(result.err())
                .contains("Unknown option: '--month=6-8'")
                .contains("Try 'sluice --help' for more information.")
                .doesNotContain("-h, --help   Show this message.");
    }

    @Test
    void argumentsNamingNoCommandPointAtTheHelpTheSameWay() {
        final CliHarness.Result result = CliHarness.run(this.commandLine);

        assertThat(result.err())
                .contains("Try 'sluice --help' for more information.")
                .doesNotContain("-h, --help   Show this message.");
    }

    @Test
    void aRefusalIsTwoLinesAndNothingElse() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--month=6-8");

        assertThat(result.err().lines().filter(line -> !line.isBlank())).containsExactly(
                "Unknown option: '--month=6-8'",
                "Try 'sluice --help' for more information.");
    }

    @Test
    void helpIsAskedForRatherThanStumbledInto() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--help");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(result.out()).contains("Usage: sluice");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void helpSaysWhatSluiceIsFor() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "-h");

        assertThat(result.out()).contains("Sluice organises your photos and videos.");
    }

    // The launcher sends every dotted argument to Spring alone. A flag spelled with a dot would be
    // filtered out before the parser saw it, and the command would run as though it had never been
    // typed. Nothing about picocli prevents naming one that way. This does.
    @Test
    void noOptionAnywhereOnTheSurfaceIsSpelledWithADot() {
        assertThat(optionNames(this.commandLine)).isNotEmpty().allSatisfy(name ->
                assertThat(name).doesNotContain("."));
    }

    private static List<String> optionNames(final CommandLine commandLine) {
        return Stream.concat(Stream.of(commandLine), commandLine.getSubcommands().values().stream())
                .flatMap(line -> line.getCommandSpec().options().stream())
                .flatMap(option -> Arrays.stream(option.names()))
                .toList();
    }
}
