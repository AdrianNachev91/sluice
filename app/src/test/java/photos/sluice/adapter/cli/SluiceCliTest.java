package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SluiceCliTest {

    private final CommandLine commandLine = CliHarness.parser();

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
    void theHelpListsTheVerbThatOpensTheWindow() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--help");

        assertThat(result.out()).contains("app").contains("Open the Sluice desktop application.");
    }

    @Test
    void theWindowVerbCarryingAnArgumentIsRefusedRatherThanIgnored() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "app", "--json");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err().lines().filter(line -> !line.isBlank())).containsExactly(
                "app takes no arguments. Run 'sluice app' on its own to open the desktop application.",
                "Try 'sluice app --help' for more information.");
    }

    @Test
    void versionIsAskedForRatherThanStumbledInto() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--version");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(result.out()).contains("Sluice");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void aRunFromSourceWordsTheAbsentVersionRatherThanPrintingNothing() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--version");

        assertThat(result.out()).contains("running from source");
    }

    @Test
    void versionIsNotAVerbsToAnswer() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "sort", "--version");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Unknown option: '--version'");
    }

    @Test
    void helpIsAskedForRatherThanStumbledInto() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--help");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(result.out()).contains("Usage: sluice");
        assertThat(result.err()).isEmpty();
    }

    // The refusal a verb gives an unknown option ends by naming that verb's own --help. Reachable
    // only on the root, that line sends a reader to a command which answers with the same refusal.
    @Test
    void everyVerbAnswersTheHelpItsOwnRefusalPointsAt() {
        assertThat(this.commandLine.getSubcommands().keySet()).isNotEmpty().allSatisfy(verb -> {
            final CliHarness.Result result = CliHarness.run(CliHarness.parser(), verb, "--help");

            assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
            assertThat(result.out()).contains("Usage: sluice " + verb);
        });
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

    @Test
    void theHelpNamesEveryVerbTheSurfaceCarries() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--help");

        assertThat(this.commandLine.getSubcommands().keySet())
                .isNotEmpty()
                .allSatisfy(verb -> assertThat(result.out()).containsPattern("(?m)^\\s+" + verb + "\\s"));
    }

    // Asking for a document is asking about a result, and help is not one. A document written here
    // would be an empty one wrapped around nothing.
    @Test
    void askingForHelpPrintsHelpEvenWhenADocumentWasAskedFor() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--json", "--help");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(result.out()).contains("Usage: sluice").doesNotContain("\"status\"");
    }

    @Test
    void argumentsNamingNoCommandWriteNoDocumentEvenWhenOneWasAskedFor() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "--json");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("No command given.");
    }

    @Test
    void theDocumentFlagIsReadFromArgumentsWhereverItSits() {
        assertThat(SluiceCli.documentAsked(new String[]{"runs", "--json"})).isTrue();
        assertThat(SluiceCli.documentAsked(new String[]{"--json", "runs"})).isTrue();
        assertThat(SluiceCli.documentAsked(new String[]{"runs"})).isFalse();
    }

    @Test
    void anArgumentAfterTheEndOfOptionsMarkerIsAValueRatherThanTheFlag() {
        assertThat(SluiceCli.documentAsked(new String[]{"rescue", "--", "--json"})).isFalse();
    }

    @Test
    void aSpellingTheArgumentReadingCannotSeeIsRefusedByTheParserToo() {
        final CliHarness.Result result = CliHarness.run(this.commandLine, "runs", "--json=true");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(SluiceCli.documentAsked(new String[]{"runs", "--json=true"})).isFalse();
    }

    private static List<String> optionNames(final CommandLine commandLine) {
        return Stream.concat(Stream.of(commandLine), commandLine.getSubcommands().values().stream())
                .flatMap(line -> line.getCommandSpec().options().stream())
                .flatMap(option -> Arrays.stream(option.names()))
                .toList();
    }
}
