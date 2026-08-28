package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.model.SortScope;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// The stand-in below declares the three scope arguments the way a real verb does, and does nothing
// else. So what these assert is the route from a typed argument to an exit code, rather than any
// verb's own work.
class ScopeRefusalReportingTest {

    @Test
    void aScopeTheVerbRefusedLeavesTheAnswerStreamEmptyAndSaysWhyOnTheOther() {
        final CliHarness.Result result = run("scoped", "2019", "--months", "6,8,11");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("6,8,11").contains("span");
    }

    @Test
    void aCallerAskingForADocumentGetsOneNamingWhichRefusalItWas() {
        final CliHarness.Result result = run("scoped", "2019", "--months", "6,8,11", "--json");

        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out())
                .contains("\"status\":\"REFUSED\"")
                .contains("\"kind\":\"MONTHS_NOT_A_SPAN\"")
                .contains("\"command\":\"scoped\"");
    }

    @Test
    void theDocumentCarriesTheMonthsThemselvesRatherThanOnlyTheSentence() {
        assertThat(run("scoped", "2019", "--months", "6,8,11", "--json").out()).contains("[6,8,11]");
    }

    @Test
    void everyKindOfScopeRefusalTakesTheSameCodeAndTheDocumentTellsThemApart() {
        assertThat(run("scoped", "--months", "6-8").exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(run("scoped", "2019", "--oldest", "30").exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(run("scoped", "20199").exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());

        assertThat(run("scoped", "--months", "6-8", "--json").out()).contains("SCOPE_MISSING");
        assertThat(run("scoped", "2019", "--oldest", "30", "--json").out()).contains("SCOPE_CONFLICTING");
        assertThat(run("scoped", "20199", "--json").out()).contains("SCOPE_VALUE_REFUSED");
    }

    @Test
    void aValueTheParserItselfCannotReadIsAUsageErrorWithNoDocument() {
        final CliHarness.Result result = run("scoped", "--oldest", "many", "--json");

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(result.out()).isEmpty();
    }

    @Test
    void aScopeTheVerbAcceptedIsNotRefused() {
        final CliHarness.Result result = run("scoped", "2019", "--months", "6-8");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out()).contains("Year[year=2019, months=MonthRange[from=6, to=8]]");
    }

    private static CliHarness.Result run(final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var runs = new RunsCommand(mock(Pipeline.class), reports);
        final var scoped = new ScopedCommand(reports);
        final CommandLine parser = SluiceCli.parser(new SluiceCli(), CliHarness.supplying(runs, scoped));
        parser.addSubcommand("scoped", scoped);
        return CliHarness.run(parser, args);
    }

    @Command(name = "scoped", description = "Stands in for a verb that takes a scope.")
    static class ScopedCommand implements Callable<Integer> {

        @Spec
        @SuppressWarnings("unused")
        private @Nullable CommandSpec spec;

        @Parameters(index = "0", arity = "0..1", paramLabel = "YEAR", description = "The year to work on.")
        @SuppressWarnings("unused")
        private @Nullable String year;

        @Option(names = ScopeArguments.MONTHS, paramLabel = "MONTHS",
                description = "Months within the year, as a span or a list.")
        @SuppressWarnings("unused")
        private @Nullable String months;

        @Option(names = ScopeArguments.OLDEST, paramLabel = "N", description = "The oldest N photos instead.")
        @SuppressWarnings("unused")
        private @Nullable Integer oldest;

        private final CommandReports reports;

        ScopedCommand(final CommandReports reports) {
            this.reports = reports;
        }

        @Override
        public Integer call() {
            final CommandSpec running = Objects.requireNonNull(this.spec);
            return this.reports.report(running, running.name(), () -> {
                final SortScope scope =
                        new ScopeArguments(this.year, this.months, this.oldest).sortScope(running.name());
                return CommandOutcome.done(Fields.of("scope", scope.toString()), List.of(scope.toString()));
            });
        }
    }
}
