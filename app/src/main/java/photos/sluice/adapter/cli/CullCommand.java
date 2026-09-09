package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullScope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Sifts what is staged in Sorted, calling the configured vision provider to judge it.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>Named {@code sift} rather than {@code cull}. The latter names reducing a population by
 * killing it, which is not what this does.
 */
@Component
@Profile("cli")
@Command(name = CullCommand.VERB,
        description = "Have your configured provider judge what is in Sorted, and move anything it "
                + "does not keep out of it.")
public class CullCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "sift";

    /**
     * Printed instead of a figure when the count behind it could not be read.
     */
    private static final String ESTIMATE_UNAVAILABLE =
            "No cost estimate for this sift. It is running anyway.";

    private final Pipeline pipeline;
    private final JobReports reports;
    private final ConsoleProgressPort progress;
    private final PathsPort paths;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "YEAR",
            description = "The year to sift. Required unless " + ScopeArguments.OLDEST + " is given.")
    @SuppressWarnings("unused")
    private @Nullable String year;

    @Option(names = ScopeArguments.MONTHS, paramLabel = "MONTHS",
            description = "Months within that year, as a span or a list: 6-8, or 6,8,11.")
    @SuppressWarnings("unused")
    private @Nullable String months;

    @Option(names = ScopeArguments.OLDEST, paramLabel = "N",
            description = "The N oldest photos by file timestamp instead of a year.")
    @SuppressWarnings("unused")
    private @Nullable Integer oldest;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the sift
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     * @param progress {@link ConsoleProgressPort} where the pre-run estimate is written
     * @param paths {@link PathsPort} resolves the Duplicates root the result names
     */
    public CullCommand(final Pipeline pipeline, final JobReports reports, final ConsoleProgressPort progress,
                       final PathsPort paths) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.progress = progress;
        this.paths = paths;
    }

    /**
     * Sifts what the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::scope, this::submit, _ -> false,
                finished -> CullOutcomeReport.of(finished.answer(), this.paths.duplicates(),
                        this.pipeline::launchPromptFor));
    }

    /**
     * Which photos this run was asked for.
     *
     * @return {@link CullScope} what to sift
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    private CullScope scope() {
        return new ScopeArguments(this.year, this.months, this.oldest).cullScope(VERB);
    }

    /**
     * Starts the sift job, then prints the pre-run estimate.
     *
     * <p>Read after {@link Pipeline#cull} returns rather than before it is called. A call refused
     * before it starts throws from here rather than returning, so no figure is printed for work
     * that was never going to happen.
     *
     * <p>Nothing the estimate does may throw once that call has returned. The job is already
     * running by then, and nothing else holds the handle. A throw here would leave it with no way
     * to be joined or stopped.
     *
     * @param scope {@link CullScope} what to sift
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    private JobHandle<CullJobOutcome> submit(final CullScope scope) {
        final JobHandle<CullJobOutcome> job = this.pipeline.cull(scope);
        this.printEstimate(scope);
        return job;
    }

    /**
     * Prints what this sift is expected to consume, on the error stream.
     *
     * @param scope {@link CullScope} what is being sifted
     */
    private void printEstimate(final CullScope scope) {
        if (SluiceCli.quietAsked(Objects.requireNonNull(this.spec)) || !this.pipeline.configuredProviderSpends()) {
            return;
        }
        try {
            final int photos = photosFor(scope, this.pipeline.sortedTally());
            final SpendEstimate estimate = this.pipeline.estimateFor(photos);
            this.progress.note(estimateLine(photos, estimate));
        } catch (final RuntimeException couldNotEstimate) {
            this.progress.note(ESTIMATE_UNAVAILABLE);
        }
    }

    /**
     * The line a pre-run estimate is worded as, naming how much to trust it.
     *
     * <p>{@code exactInput} is false on every call here: no sheet exists yet to count the input
     * half against.
     *
     * @param photos int how many photos the scope covers
     * @param estimate {@link SpendEstimate} what the sift is expected to consume
     * @return {@link String} the line
     */
    private static String estimateLine(final int photos, final SpendEstimate estimate) {
        final String trust = estimate.exactInput() ? "" : "an estimate, ";
        final String basis = estimate.historicOutput() ? "based on your previous runs"
                : "based on default estimates";
        return "Sifting " + ResultLines.grouped(photos) + " photos is expected to spend about "
                + ResultLines.rounded(estimate.totalTokens()) + " tokens (" + trust + basis + "). It stops "
                + "on its own if it goes far past that.";
    }

    /**
     * How many staged photos one scope covers.
     *
     * @param scope {@link CullScope} the scope
     * @param tally {@link SortedTally} what is staged, by year
     * @return int the photo count
     */
    private static int photosFor(final CullScope scope, final SortedTally tally) {
        return switch (scope) {
            case final CullScope.Year year -> tally.years().stream()
                    .filter(row -> row.year() == year.year())
                    .findFirst()
                    .map(row -> year.months() == null ? row.photos() : row.photosIn(year.months()))
                    .orElse(0);
            case final CullScope.OldestN oldestN -> Math.min(oldestN.n(),
                    tally.years().stream().mapToInt(SortedTally.YearRow::photos).sum());
        };
    }
}
