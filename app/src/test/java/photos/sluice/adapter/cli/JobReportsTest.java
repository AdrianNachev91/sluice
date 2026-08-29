package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.paths.PathViolation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Driven through the parser with a stand-in verb, because most of what decides this is the root
// command's own --quiet, which only a real parse fills in. The stand-in starts a job and reads what
// it answered, and does nothing else.
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JobReportsTest {

    private final JobRunner runner = new JobRunner();
    private final ByteArrayOutputStream reported = new ByteArrayOutputStream();

    private InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void aPersonWatchingARunIsToldHowToStopIt(@TempDir final Path root) {
        this.run(root, this.watched(true));

        assertThat(this.reported()).contains(TypedCancel.HINT);
    }

    @Test
    void aRunNobodyIsWatchingIsNotToldHowToStopIt(@TempDir final Path root) {
        this.run(root, this.watched(false));

        assertThat(this.reported()).doesNotContain(TypedCancel.HINT);
    }

    @Test
    void aQuietRunIsNotToldEither(@TempDir final Path root) {
        this.run(root, this.watched(true), "--quiet");

        assertThat(this.reported()).doesNotContain(TypedCancel.HINT);
    }

    @Test
    void aCancelArrivingAfterTheJobAnsweredChangesNothing(@TempDir final Path root) {
        final CountDownLatch building = new CountDownLatch(1);
        final CountDownLatch handedOver = new CountDownLatch(1);
        final var wasRead = new AtomicBoolean();
        this.typed = typedAfter(building, handedOver);

        final CliHarness.Result result = this.run(root, this.watched(true), _ -> {
            building.countDown();
            wasRead.set(await(handedOver));
            return CommandOutcome.done(null, List.of("done"));
        });

        // Checked first: a line that never reached the reader would leave the two assertions below
        // true of a run nobody ever tried to cancel.
        assertThat(wasRead).isTrue();
        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(this.reported()).doesNotContain("Stopping");
    }

    private void run(final Path root, final ConsoleProgressPort progress, final String... args) {
        this.run(root, progress, _ -> CommandOutcome.done(null, List.of("done")), args);
    }

    private CliHarness.Result run(final Path root, final ConsoleProgressPort progress,
                                  final Function<JobReports.Finished<String>, CommandOutcome> answered,
                                  final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(mock(WorkingRootLock.class),
                SettingsFixture.workingRoot(root), mock(Pipeline.class), new UsableRoots());
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, progress), progress);
        final var stand = new StandInCommand(jobs, this.runner, answered);
        final CommandLine parser = CliHarness.parser(stand);
        parser.addSubcommand("stand-in", stand);
        return CliHarness.run(parser, invocation(args));
    }

    private static String[] invocation(final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "stand-in";
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    private ConsoleProgressPort watched(final boolean watching) {
        return new ConsoleProgressPort(
                new PrintStream(this.reported, true, StandardCharsets.UTF_8), watching);
    }

    private String reported() {
        return this.reported.toString(StandardCharsets.UTF_8);
    }

    private static boolean await(final CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Holds the typed line back until the outcome is being built, then reports that it was handed
    // over. So the reader meets it inside the window under test rather than whenever it gets there.
    private static InputStream typedAfter(final CountDownLatch until, final CountDownLatch given) {
        return new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(final byte[] into, final int from, final int wanted) {
                if (!await(until)) {
                    return -1;
                }
                final int read = super.read(into, from, wanted);
                given.countDown();
                return read;
            }
        };
    }

    private record UsableRoots() implements PathValidationUseCase {
        @Override
        public List<PathViolation> violations(final PathSettings paths) {
            return List.of();
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return List.of();
        }
    }

    @Command(name = "stand-in", description = "Stands in for a verb that starts a job and waits.")
    static class StandInCommand implements Callable<Integer> {

        @Spec
        @SuppressWarnings("unused")
        private @Nullable CommandSpec spec;

        private final JobReports reports;
        private final JobRunner runner;
        private final Function<JobReports.Finished<String>, CommandOutcome> answered;

        StandInCommand(final JobReports reports, final JobRunner runner,
                       final Function<JobReports.Finished<String>, CommandOutcome> answered) {
            this.reports = reports;
            this.runner = runner;
            this.answered = answered;
        }

        @Override
        public Integer call() {
            final CommandSpec running = Objects.requireNonNull(this.spec);
            return this.reports.report(running, running.name(), () -> "scope",
                    scope -> this.runner.submit(_ -> scope), _ -> false, this.answered);
        }
    }
}
