package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Resumes a sift left waiting, picking up wherever its shards stand now.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 */
@Component
@Profile("cli")
@Command(name = ResumeCommand.VERB, description = "Resume a sift that is waiting, by its scope tag or its folder.")
public class ResumeCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "resume";

    private final Pipeline pipeline;
    private final JobReports reports;
    private final RunAddress address;
    private final PathsPort paths;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "RUN",
            description = "A scope tag, as 'runs' prints it, or the folder's own full path.")
    @SuppressWarnings("unused")
    private @Nullable String run;

    @Option(names = "--allow-partial", description = "Apply what has been judged, and leave the rest where it is.")
    @SuppressWarnings("unused")
    private boolean allowPartial;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that resumes the sift
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     * @param address {@link RunAddress} turns what was typed into the run's own folder
     * @param paths {@link PathsPort} resolves the Duplicates root the result names
     */
    public ResumeCommand(final Pipeline pipeline, final JobReports reports, final RunAddress address,
                         final PathsPort paths) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.address = address;
        this.paths = paths;
    }

    /**
     * Resumes the run the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::folder, this::submit, _ -> false,
                finished -> SiftOutcomeReport.of(finished.answer(), this.paths.duplicates(),
                        this.pipeline::launchPromptFor));
    }

    /**
     * Which run this call was asked to resume.
     *
     * @return {@link Path} the run's own folder
     * @throws ScopeRefusedException when the address names no sift on disk
     */
    private Path folder() {
        return this.address.folderFor(this.run);
    }

    /**
     * Starts the resume job.
     *
     * @param prepDir {@link Path} the run's own folder
     * @return a {@link JobHandle} of {@link SiftJobOutcome} a handle to the running job
     */
    private JobHandle<SiftJobOutcome> submit(final Path prepDir) {
        return this.pipeline.resume(prepDir, this.allowPartial);
    }
}
