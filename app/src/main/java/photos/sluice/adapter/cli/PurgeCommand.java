package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Hard-deletes the records of every finished run, with no way back.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>Loses those runs' records for good, so it refuses without {@code --yes}, naming which scopes
 * are going. Where nothing has finished, nothing is lost, and it runs straight through instead.
 */
@Component
@Profile("cli")
@Command(name = PurgeCommand.VERB, description = "Delete the records of every finished run, with no way back.")
public class PurgeCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "purge";

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Option(names = "--yes", description = "Confirm deleting the finished runs.")
    @SuppressWarnings("unused")
    private boolean yes;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the purge
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     */
    public PurgeCommand(final Pipeline pipeline, final JobReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Purges every finished run.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.reportUninterruptible(running, VERB, this::confirmed,
                _ -> this.pipeline.purgeCompleted(), PurgeCommand::purged);
    }

    /**
     * What the command makes of a finished purge.
     *
     * @param finished a {@link JobReports.Finished} of {@link PurgeReport} what the purge did
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome purged(final JobReports.Finished<PurgeReport> finished) {
        final PurgeReport report = finished.answer();
        return CommandOutcome.done(RecoveryPayloads.purged(report), lines(report));
    }

    /**
     * What a purge tells a person it did.
     *
     * @param report {@link PurgeReport} what the purge did
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final PurgeReport report) {
        if (report.unlistableRoot() != null) {
            return List.of("Nothing was cleared. " + RunsRefusals.unreadable(report.unlistableRoot()).sentence());
        }
        final List<String> lines = new ArrayList<>();
        lines.add(report.purged().isEmpty()
                ? "No finished runs to clear."
                : "Cleared " + ResultLines.grouped(report.purged().size()) + " finished run"
                        + (report.purged().size() == 1 ? "" : "s") + ".");
        final int leftBehind = report.skipped().size() + report.unreadable().size();
        if (leftBehind > 0) {
            lines.add(ResultLines.grouped(leftBehind) + " run" + (leftBehind == 1 ? " has" : "s have")
                    + " not finished, so nothing from " + (leftBehind == 1 ? "it" : "them") + " was touched.");
        }
        return lines;
    }

    /**
     * Refuses the purge without {@code --yes}, where something would actually be lost.
     *
     * @return {@code true} always; the value itself carries nothing
     * @throws ScopeRefusedException when the sift-prep root cannot be read, so what it holds is
     *         unknown
     * @throws ConfirmationRequiredException when a finished run would be deleted and {@code --yes}
     *         was not given
     */
    private boolean confirmed() {
        final List<String> finished = this.completedScopes();
        if (!finished.isEmpty() && !this.yes) {
            throw new ConfirmationRequiredException("Purging deletes the records of every finished run: "
                    + String.join(", ", finished) + ". This cannot be undone. Run this again with --yes to continue.");
        }
        return true;
    }

    /**
     * The scope of every run currently finished, which is what a purge right now would delete.
     *
     * @return a {@link List} of {@link String} their scope tags, empty when none are finished
     * @throws ScopeRefusedException when the sift-prep root cannot be read
     */
    private List<String> completedScopes() {
        return switch (this.pipeline.cullRuns()) {
            case CullRuns.Listed(final List<CullRunSummary> runs) -> runs.stream()
                    .filter(run -> run.health().state() == PrepDirHealth.State.COMPLETE)
                    .map(CullRunSummary::scope)
                    .toList();
            case CullRuns.Unlistable(final Path root) -> throw new ScopeRefusedException(
                    RunsRefusals.unreadable(root));
        };
    }
}
