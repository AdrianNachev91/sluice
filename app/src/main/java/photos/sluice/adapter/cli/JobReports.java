package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.JobHandle;
import picocli.CommandLine.Model.CommandSpec;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs one verb's job from end to end and reports what came of it.
 *
 * <p>It settles what is the same whichever job ran. Claiming the folder before anything is changed.
 * Turning progress on or off. Watching for a typed cancel. And saying whether the run finished or
 * was stopped. A verb supplies only the call that starts its job, and the reading of what that job
 * answered.
 *
 * <p>Waiting is {@link JobHandle#join()} and nothing more, so a failure raised inside a job arrives
 * wrapped and is left that way. Unwrapping it here would answer it differently from the same
 * failure raised before the job started.
 */
@Component
@Profile("cli")
public class JobReports {

    private final CommandReports reports;
    private final MutatingCommandStart start;
    private final TypedCancel cancel;
    private final ConsoleProgressPort progress;

    /**
     * Creates the reporter.
     *
     * @param reports {@link CommandReports} writes whatever this produced
     * @param start {@link MutatingCommandStart} claims the working root before anything changes
     * @param cancel {@link TypedCancel} stops the job when the caller asks
     * @param progress {@link ConsoleProgressPort} reports the job as it runs
     */
    public JobReports(final CommandReports reports, final MutatingCommandStart start,
                      final TypedCancel cancel, final ConsoleProgressPort progress) {
        this.reports = reports;
        this.start = start;
        this.cancel = cancel;
        this.progress = progress;
    }

    /**
     * Reads what a verb was asked for, starts its job, waits, and writes what it produced.
     *
     * @param <S> what the verb was asked to work on
     * @param <T> what the job answers with
     * @param spec {@link CommandSpec} the running command, which carries its own two streams
     * @param command {@link String} the verb's name, as the document reports it
     * @param asked a {@link Supplier} reads the arguments, refusing what this verb cannot take
     * @param submit a {@link Function} starts the job over what was asked for
     * @param stoppedShort a {@link Predicate} reads the job's own account of whether it gave up
     * @param answered a {@link Function} reads what the job answered into an outcome
     * @return int the code the process leaves with
     */
    public <S, T> int report(final CommandSpec spec, final String command, final Supplier<S> asked,
                             final Function<S, JobHandle<T>> submit, final Predicate<T> stoppedShort,
                             final Function<Finished<T>, CommandOutcome> answered) {
        return this.reports.report(spec, command,
                () -> this.run(spec, asked, submit, stoppedShort, answered));
    }

    /**
     * What a job answered, and whether the caller stopped it before it got there.
     *
     * <p>A verb needs both to word itself. Counts from a run somebody stopped support fewer claims
     * than the same counts from a run that finished. Empty ones mean the caller was quick, not that
     * there was nothing to do.
     *
     *
     * @param <T> what the job answers with
     * @param answer T what the job produced
     * @param stopped boolean whether the job gave up before the end of its scope
     */
    public record Finished<T>(T answer, boolean stopped) {
    }

    /**
     * Reads the arguments, claims the root, runs the job, and reads what it answered.
     *
     * <p>The arguments are read first, so a verb refusing what it was given costs nothing else. The
     * claim that follows takes a folder off another Sluice and sweeps files out of it. Neither is
     * worth doing for a command about to be turned down over a typo.
     *
     * @param <S> what the verb was asked to work on
     * @param <T> what the job answers with
     * @param spec {@link CommandSpec} the running command
     * @param asked a {@link Supplier} reads the arguments
     * @param submit a {@link Function} starts the job over what was asked for
     * @param stoppedShort a {@link Predicate} reads the job's own account of whether it gave up
     * @param answered a {@link Function} reads what the job answered into an outcome
     * @return {@link CommandOutcome} what the job produced
     */
    private <S, T> CommandOutcome run(final CommandSpec spec, final Supplier<S> asked,
                                      final Function<S, JobHandle<T>> submit,
                                      final Predicate<T> stoppedShort,
                                      final Function<Finished<T>, CommandOutcome> answered) {
        final boolean quiet = SluiceCli.quietAsked(spec);
        this.progress.quiet(quiet);
        final S scope = asked.get();
        this.start.claimAndSweep();
        final JobHandle<T> job = submit.apply(scope);
        if (!quiet && this.progress.watched()) {
            this.progress.note(TypedCancel.HINT);
        }
        final T produced;
        // The watch closes as this block ends, before the outcome below is built. A line arriving
        // after that cannot print a note under a run which has already finished.
        try (final var _ = this.cancel.watch(job)) {
            produced = job.join();
        }
        // The answer's own account rather than the handle's flag. A cancel asked for while the last
        // of the work was already finishing stopped nothing, and only the answer knows that.
        final boolean stopped = stoppedShort.test(produced);
        final CommandOutcome outcome = answered.apply(new Finished<>(produced, stopped));
        return stopped ? outcome.cancelled() : outcome;
    }
}
