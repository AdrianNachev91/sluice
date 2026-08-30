package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a caller is told before a run is thrown away, shared by every route that can throw one away.
 */
@Component
@Profile("cli")
public class DiscardConfirmation {

    private final Pipeline pipeline;

    /**
     * Creates the confirmation builder.
     *
     * @param pipeline {@link Pipeline} enumerates the runs a shard count is read off, and names
     *        whether the configured provider spends and where discarded records are archived
     */
    public DiscardConfirmation(final Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * What discarding this run would lose, and where its records would go.
     *
     * @param prepDir {@link Path} the run to discard
     * @return {@link String} the sentence a caller is told without {@code --yes}
     */
    String messageFor(final Path prepDir) {
        final Optional<CullRunSummary> run = this.runAt(prepDir);
        final String paidFor = run.map(this::paidFor).orElse("Sluice could not confirm how many paid sheet "
                + "decisions this includes. ");
        return paidFor + "Discarding this run's records will archive them. They will stay on disk in "
                + this.pipeline.archivesFolder() + " for 30 days. Run this again with --yes to continue.";
    }

    /**
     * What a found run's own shard count says about what would be lost.
     *
     * @param run {@link CullRunSummary} the run, freshly diagnosed
     * @return {@link String} the clause, empty when nothing counted is worth naming
     */
    private String paidFor(final CullRunSummary run) {
        final var sheets = run.shards();
        if (sheets == null || sheets.valid() == 0) {
            return "";
        }
        return counted(sheets.valid(), "sheet decision", "sheet decisions")
                + (this.pipeline.configuredProviderSpends()
                ? " you have already paid for are set aside with it. "
                : " are set aside with it. ");
    }

    /**
     * The run at this folder, read off a fresh sweep of the sift-prep root.
     *
     * @param prepDir {@link Path} the run
     * @return an {@link Optional} of {@link CullRunSummary} the run, empty when it is not among
     *         those found or the sift-prep root could not be read
     */
    private Optional<CullRunSummary> runAt(final Path prepDir) {
        return switch (this.pipeline.cullRuns()) {
            case CullRuns.Listed(final List<CullRunSummary> runs) ->
                    runs.stream().filter(run -> run.prepDir().equals(prepDir)).findFirst();
            case final CullRuns.Unlistable ignored -> Optional.empty();
        };
    }

    /**
     * A count with the word it counts, singular or plural.
     *
     * @param count int the count
     * @param singular {@link String} the word for one
     * @param plural {@link String} the word for anything else
     * @return {@link String} the count and the word together
     */
    private static String counted(final int count, final String singular, final String plural) {
        return ResultLines.grouped(count) + " " + (count == 1 ? singular : plural);
    }
}
