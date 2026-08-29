package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Brings chosen folders and files into the Inbox, so a documented route participates in what a
 * plain drag into Explorer would do unrecorded.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 */
@Component
@Profile("cli")
@Command(name = ImportCommand.VERB, description = "Bring chosen folders and files into the Inbox.")
public class ImportCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "import";

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    // An array rather than a List. picocli's Spring-aware factory tries every collection field's
    // type against the application context first. List is an interface, so that attempt fails and
    // falls back correctly, but not silently: it logs on every real invocation. An array needs no
    // such fallback.
    @Parameters(index = "0..*", paramLabel = "SOURCE", arity = "1..*",
            description = "Folders or files to bring into the Inbox.")
    @SuppressWarnings("unused")
    private String @Nullable [] sources;

    @Option(names = "--move", description = "Delete each original once its bytes are confirmed in the Inbox.")
    @SuppressWarnings("unused")
    private boolean move;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the import
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     */
    public ImportCommand(final Pipeline pipeline, final JobReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Imports what the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::sources, this::submit, ImportSummary::cancelled,
                this::imported);
    }

    /**
     * Which folders and files this run was asked to bring in.
     *
     * @return a {@link List} of {@link Path} the sources
     */
    private List<Path> sources() {
        return Arrays.stream(Objects.requireNonNull(this.sources,
                        "picocli refuses a missing positional before this runs"))
                .map(Path::of).toList();
    }

    /**
     * Starts the import job.
     *
     * @param sources a {@link List} of {@link Path} the sources to bring in
     * @return a {@link JobHandle} of {@link ImportSummary} a handle to the running job
     * @throws ImportSourceException where the chosen sources cannot be imported
     */
    private JobHandle<ImportSummary> submit(final List<Path> sources) {
        return this.pipeline.importFrom(sources, this.move ? ImportKind.MOVE : ImportKind.COPY);
    }

    /**
     * What the command makes of a finished import.
     *
     * @param finished a {@link JobReports.Finished} of {@link ImportSummary} what the import did,
     *        and whether the caller stopped it
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome imported(final JobReports.Finished<ImportSummary> finished) {
        final ImportSummary imported = finished.answer();
        return CommandOutcome.done(ImportPayloads.imported(imported), lines(imported, finished.stopped()));
    }

    /**
     * What an import run tells a person it did.
     *
     * @param imported {@link ImportSummary} what the run did
     * @param stopped boolean whether the caller asked it to stop
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final ImportSummary imported, final boolean stopped) {
        if (imported.found() == 0 && imported.unreadablePlaces() == 0) {
            return List.of(stopped ? "Stopped before anything was found." : "Nothing to import there.");
        }
        final List<String> lines = new ArrayList<>();
        if (stopped) {
            lines.add(stoppedLine(leftBehind(imported)));
        }
        ResultLines.addWhenAny(lines, "Found", imported.found());
        ResultLines.addWhenAny(lines, "Imported", imported.broughtIn());
        ResultLines.addWhenAny(lines, "Skipped: already in your Inbox", imported.alreadyThere());
        ResultLines.addWhenAny(lines, "Arrived broken", imported.unverified());
        ResultLines.addWhenAny(lines, "Could not be read", imported.couldNotBeRead());
        ResultLines.addWhenAny(lines, "Folders could not be opened", imported.unreadablePlaces());
        return lines;
    }

    /**
     * How many of the files found this run never reached.
     *
     * @param imported {@link ImportSummary} what the run did
     * @return int the count
     */
    private static int leftBehind(final ImportSummary imported) {
        return imported.found() - imported.broughtIn() - imported.alreadyThere() - imported.unverified()
                - imported.couldNotBeRead();
    }

    /**
     * What a stopped run says above its counts.
     *
     * @param leftBehind int files found but never reached
     * @return {@link String} the line
     */
    private static String stoppedLine(final int leftBehind) {
        if (leftBehind == 0) {
            return "Stopped. Everything found was brought in.";
        }
        return "Stopped. Run the import again to pick up the rest.";
    }
}
