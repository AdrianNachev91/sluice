package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.job.ShardTally;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

/**
 * Lists every sift run on disk, and how far each one has got.
 *
 * <p>How far, rather than what is standing in the way. A blocked run is reported as a count of open
 * problems, not as the problems themselves, which is what {@code troubleshoot} is for. The count is
 * enough to say which run needs somebody.
 *
 * <p>It only reads, so it claims nothing. A folder claim here would mean this failing whenever the
 * desktop app happened to be open, which teaches people to close the app before looking at
 * anything.
 *
 * <p>The scope it prints is how every other command addresses the same run, so a caller resumes or
 * discards one by copying what it just read.
 */
@Component
@Profile("cli")
@Command(name = "runs", description = "List the sift runs on disk and how far each one has got.")
public class RunsCommand implements Callable<Integer> {

    /**
     * Printed for a run whose diagnosis never got far enough to count its sheets.
     */
    private static final String NO_COUNT = "-";

    /**
     * Blanks between one column and the next.
     */
    private static final int COLUMN_GAP = 2;

    private final Pipeline pipeline;
    private final CommandReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that enumerates and diagnoses the runs
     * @param reports {@link CommandReports} writes whatever this produced
     */
    public RunsCommand(final Pipeline pipeline, final CommandReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Lists the runs.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, "runs", () -> outcomeOf(this.pipeline.cullRuns()));
    }

    /**
     * What the command makes of a sweep of the sift-prep root.
     *
     * <p>A root nobody could read is refused rather than answered. Answering it would put an empty
     * table in front of a caller whose runs are all still there. A script reading the exit code
     * would take that for an install with nothing sifted.
     *
     * @param reading {@link CullRuns} what the sweep found
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome outcomeOf(final CullRuns reading) {
        return switch (reading) {
            case CullRuns.Listed(final List<CullRunSummary> runs) ->
                    CommandOutcome.done(runs.stream().map(CullPayloads::run).toList(), lines(runs));
            case CullRuns.Unlistable(final Path root) -> CommandOutcome.refused(RunsRefusals.unreadable(root));
        };
    }

    /**
     * The runs as a person reads them: a header, then one line each, columns lined up.
     *
     * @param runs a {@link List} of {@link CullRunSummary} every run found
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final List<CullRunSummary> runs) {
        if (runs.isEmpty()) {
            return List.of("No sift runs.");
        }
        final List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("SCOPE", "STATE", "SHEETS", "OPEN", "LAST WRITTEN"));
        runs.forEach(run -> rows.add(List.of(run.scope(), run.health().state().name(), sheets(run.shards()),
                String.valueOf(run.health().findings().size()), written(run.since()))));
        final List<Integer> widths = widths(rows);
        return rows.stream().map(row -> line(row, widths)).toList();
    }

    /**
     * One row, each cell padded out to its column's width. The last takes no padding, so no line
     * ends in blanks.
     *
     * @param row a {@link List} of {@link String} the cells
     * @param widths a {@link List} of {@link Integer} how wide each column is
     * @return {@link String} the line
     */
    private static String line(final List<String> row, final List<Integer> widths) {
        final StringBuilder line = new StringBuilder();
        for (int column = 0; column < row.size(); column++) {
            line.append(column == row.size() - 1 ? row.get(column) : pad(row.get(column), widths.get(column)));
        }
        return line.toString();
    }

    /**
     * How wide each column has to be to hold every cell in it, plus the gap to the next one.
     *
     * @param rows a {@link List} of {@link List} of {@link String} the header and every row under it
     * @return a {@link List} of {@link Integer} one width per column
     */
    private static List<Integer> widths(final List<List<String>> rows) {
        return IntStream.range(0, rows.getFirst().size())
                .mapToObj(column -> rows.stream().mapToInt(row -> row.get(column).length()).max().orElse(0)
                        + COLUMN_GAP)
                .toList();
    }

    /**
     * One cell, padded out to its column's width.
     *
     * @param cell {@link String} the cell
     * @param width int the column's width
     * @return {@link String} the padded cell
     */
    private static String pad(final String cell, final int width) {
        return cell + " ".repeat(width - cell.length());
    }

    /**
     * When a run was last written to, to the second.
     *
     * <p>The stored moment carries nanoseconds, which read as noise in a column somebody is
     * scanning for which run is oldest. The document keeps the full precision; nothing there is
     * scanning anything.
     *
     * <p>Still the moment as the clock outside this machine reads it, rather than the local one. It
     * is the one spelling that cannot be misread by somebody in another place, and this surface's
     * readers already know it.
     *
     * @param since {@link Instant} when the run was last written to
     * @return {@link String} the cell
     */
    private static String written(final Instant since) {
        return since.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * How far through its sheets a run is, or a dash when nothing could count them.
     *
     * @param shards {@link ShardTally} the count, or null
     * @return {@link String} the cell
     */
    private static String sheets(final @Nullable ShardTally shards) {
        return shards == null ? NO_COUNT : shards.valid() + "/" + shards.total();
    }
}
