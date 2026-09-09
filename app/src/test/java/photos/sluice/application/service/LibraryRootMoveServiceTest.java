package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.YamlSettingsStore;
import photos.sluice.application.port.in.LibraryRootMoveOutcome;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.UnfinishedRunsException;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.config.SettingsHolder;
import photos.sluice.domain.copy.CopySummary;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.CullPrepTestSupport.prepDir;
import static photos.sluice.application.service.CullPrepTestSupport.writeIndex;

class LibraryRootMoveServiceTest {

    @Nested
    class CopyingAndKeepingTheIndex {

        @Test
        void copiesTheOldLibraryIntoTheNewOneAndThenMovesTheRoot(@TempDir final Path root,
                                                                 @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("2019/06/holiday.jpg"), "holiday");

            final LibraryRootMoveOutcome outcome = fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(outcome).isEqualTo(new LibraryRootMoveOutcome.CopiedAndMoved(1, 1, fixture.library));
            assertThat(newLibrary.resolve("2019/06/holiday.jpg")).hasContent("holiday");
            assertThat(fixture.libraryRootInForce()).isEqualTo(newLibrary.toString());
        }

        // Through the real YAML store, since a move reaching only the in-memory settings is
        // silently undone by the next launch.
        @Test
        void theMoveSurvivesToTheConfigFile(@TempDir final Path root, @TempDir final Path newLibrary)
                throws IOException {
            final Path configFile = root.resolve("config.yml");
            final var fixture = new Fixture(root, new CopyEngine(new NioMediaStore()),
                    new YamlSettingsStore(configFile));
            write(fixture.library.resolve("holiday.jpg"), "holiday");

            fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(Files.readString(configFile)).contains("library-root: " + newLibrary);
        }

        @Test
        void leavesTheHashIndexExactlyWhereItWas(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            fixture.recordInTheIndex("abc123", fixture.library.resolve("holiday.jpg"));

            fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(fixture.hashIndex.contains("abc123")).isTrue();
        }

        @Test
        void leavesTheOldLibraryHoldingEverythingItHeld(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");

            fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(fixture.library.resolve("holiday.jpg")).hasContent("holiday");
        }

        @Test
        void reportsTheCopyThroughTheProgressPort(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");

            fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(fixture.progress.events)
                    .containsExactly("planned:Copying the library...", "started", "tick 1/1", "finished");
        }

        @Test
        void aFreshIndexMoveAnnouncesThatItReportsNoPhaseAtAll(@TempDir final Path root,
                                                              @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");

            fixture.move(newLibrary, LibraryRootResolution.START_A_FRESH_INDEX);

            assertThat(fixture.progress.events).containsExactly("planned:");
        }

        // The engine is stubbed rather than stopped for real. Two small files copy in microseconds,
        // so a real stop raced against the job's own start decides the assertion by scheduler order.
        @Test
        void aCopyReportedAsStoppedLeavesTheRootWhereItWas(@TempDir final Path root,
                                                           @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root, new StoppedCopyEngine());
            write(fixture.library.resolve("one.jpg"), "one");
            write(fixture.library.resolve("two.jpg"), "two");

            final LibraryRootMoveOutcome outcome =
                    fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(outcome).isEqualTo(new LibraryRootMoveOutcome.CopyCancelled(1, 2,
                    newLibrary.toAbsolutePath().normalize()));
            assertThat(fixture.libraryRootInForce()).isEqualTo(fixture.library.toString());
        }

        // Both stubbed cases above pass against a service wired to CancellationSignal.NEVER. So the
        // cost of leaving it at those two is a Stop button doing nothing on the app's longest job.
        @Test
        void theJobsCancellationReachesTheCopy(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var engine = new SignalWatchingCopyEngine();
            final var fixture = new Fixture(root, engine);
            write(fixture.library.resolve("one.jpg"), "one");

            final JobHandle<LibraryRootMoveOutcome> handle =
                    fixture.service.moveLibraryRoot(createDirectory(newLibrary),
                            LibraryRootResolution.COPY_AND_KEEP_INDEX);
            handle.requestCancellation();
            engine.releaseOnceTheCopyHasStarted();

            assertThat(handle.join()).isEqualTo(new LibraryRootMoveOutcome.CopyCancelled(0, 1,
                    newLibrary.toAbsolutePath().normalize()));
            assertThat(fixture.libraryRootInForce()).isEqualTo(fixture.library.toString());
        }

        @Test
        void aCopyThatFinishesMovesTheRoot(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("one.jpg"), "one");

            fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX);

            assertThat(fixture.libraryRootInForce()).isEqualTo(newLibrary.toString());
        }
    }

    @Nested
    class StartingAFreshIndex {

        @Test
        void filesTheIndexIntoTheGraveyardAndMovesTheRoot(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            fixture.recordInTheIndex("abc123", fixture.library.resolve("holiday.jpg"));

            final LibraryRootMoveOutcome outcome =
                    fixture.move(newLibrary, LibraryRootResolution.START_A_FRESH_INDEX);

            final Path filedAt = ((LibraryRootMoveOutcome.MovedWithAFreshIndex) outcome).previousIndexFiledAt();
            assertThat(filedAt).isNotNull().exists().hasParent(root.resolve("logs/archives"));
            assertThat(fixture.hashIndex.contains("abc123")).isFalse();
            assertThat(fixture.libraryRootInForce()).isEqualTo(newLibrary.toString());
        }

        @Test
        void copiesNothingIntoTheNewLibrary(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");

            fixture.move(newLibrary, LibraryRootResolution.START_A_FRESH_INDEX);

            assertThat(new NioMediaStore().listFiles(newLibrary)).isEmpty();
        }

        @Test
        void saysThereWasNoIndexToFileWhenNoneHadBeenWritten(@TempDir final Path root,
                                                             @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);

            final LibraryRootMoveOutcome outcome =
                    fixture.move(newLibrary, LibraryRootResolution.START_A_FRESH_INDEX);

            assertThat(outcome).isEqualTo(new LibraryRootMoveOutcome.MovedWithAFreshIndex(null));
        }
    }

    @Nested
    class Refusals {

        @Test
        void refusesWhileACullRunHasNotFinished(@TempDir final Path root, @TempDir final Path newLibrary)
                throws IOException {
            final var fixture = new Fixture(root);
            writeIndex(prepDir(root), 1, List.of("montage-001"));

            assertThatThrownBy(() -> fixture.move(newLibrary, LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOfSatisfying(UnfinishedRunsException.class,
                            refusal -> assertThat(refusal.scopes()).containsExactly("scope1"));

            assertThat(fixture.libraryRootInForce()).isEqualTo(fixture.library.toString());
        }

        @Test
        void refusesAFolderTheLibraryIsAlreadyIn(@TempDir final Path root) {
            final var fixture = new Fixture(root);

            assertThatThrownBy(() -> fixture.move(fixture.library, LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(fixture.library.toString());
        }

        @Test
        void refusesAnotherSpellingOfTheFolderTheLibraryIsAlreadyIn(@TempDir final Path root) {
            final var fixture = new Fixture(root);
            final Path respelled = fixture.library.resolve("..").resolve(fixture.library.getFileName());

            assertThatThrownBy(() -> fixture.move(respelled, LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesAFolderThatIsNotThereBeforeAnythingIsCopied(@TempDir final Path root) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");
            final Path missing = root.resolve("nowhere");

            assertThatThrownBy(() -> fixture.service.moveLibraryRoot(missing,
                    LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOf(PathsMisconfiguredException.class);

            assertThat(missing).doesNotExist();
            assertThat(fixture.libraryRootInForce()).isEqualTo(fixture.library.toString());
        }

        // The overlap a user reaches by picking a folder inside a root they already configured.
        @Test
        void refusesAFolderInsideTheInboxBeforeAnythingIsCopied(@TempDir final Path root) {
            final var fixture = new Fixture(root);
            write(fixture.library.resolve("holiday.jpg"), "holiday");
            final Path insideTheInbox = createDirectory(root.resolve("Inbox").resolve("NewLibrary"));

            assertThatThrownBy(() -> fixture.service.moveLibraryRoot(insideTheInbox,
                    LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOfSatisfying(PathsMisconfiguredException.class,
                            refusal -> assertThat(refusal.violations())
                                    .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX)));

            assertThat(new NioMediaStore().listFiles(insideTheInbox)).isEmpty();
        }

        // A refusal must leave the runner free, or one refused move would cost every later job.
        @Test
        void aRefusalLeavesTheJobSlotFree(@TempDir final Path root, @TempDir final Path newLibrary) {
            final var fixture = new Fixture(root);

            assertThatThrownBy(() -> fixture.move(fixture.library, LibraryRootResolution.COPY_AND_KEEP_INDEX))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(fixture.move(newLibrary, LibraryRootResolution.START_A_FRESH_INDEX))
                    .isInstanceOf(LibraryRootMoveOutcome.MovedWithAFreshIndex.class);
        }
    }

    // Every collaborator is the real one, over one settings holder, the way the context wires them.
    // The lock is the exception: claiming a working root is process-wide state, and no move here
    // changes which folder is claimed.
    private static final class Fixture {

        private final Path library;
        private final SettingsHolder live;
        private final CsvLibraryHashIndex hashIndex;
        private final RecordingProgress progress = new RecordingProgress();
        private final LibraryRootMoveService service;

        private Fixture(final Path root) {
            this(root, new CopyEngine(new NioMediaStore()));
        }

        private Fixture(final Path root, final CopyEngine copyEngine) {
            this(root, copyEngine, _ -> {
            });
        }

        private Fixture(final Path root, final CopyEngine copyEngine, final SettingsStore store) {
            this.library = createDirectory(root.resolve("Library"));
            createDirectory(root.resolve("Inbox"));
            this.live = SettingsFixture.holder(root, this.library, root.resolve("Inbox"));
            final var paths = new PathsConfig(this.live);
            final var mediaStore = new NioMediaStore();
            final var jobRunner = new JobRunner();
            final var validation = new PathValidationService(mediaStore, this.live);
            this.hashIndex = new CsvLibraryHashIndex(paths);
            final var settingsService = new SettingsService(this.live, store,
                    new NoClaims(), jobRunner, validation, new NioMediaStore(),
                    _ -> Optional.empty(), List.of());
            this.service = new LibraryRootMoveService(settingsService, jobRunner, copyEngine,
                    this.hashIndex, paths, CullPrepTestSupport.prepDirDoctor(root), validation, this.progress);
        }

        private LibraryRootMoveOutcome move(final Path newLibrary, final LibraryRootResolution resolution) {
            return this.service.moveLibraryRoot(createDirectory(newLibrary), resolution).join();
        }

        private @Nullable String libraryRootInForce() {
            return this.live.current().paths().libraryRoot();
        }

        private void recordInTheIndex(final String hash, final Path file) {
            this.hashIndex.append(List.of(new IndexEntry(hash, file)));
        }
    }

    // Reports one file copied out of two and then stopped, without touching a disk.
    private static final class StoppedCopyEngine extends CopyEngine {

        private StoppedCopyEngine() {
            super(new NioMediaStore());
        }

        @Override
        public CopySummary copyTree(final Path source, final Path destination,
                                    final ProgressCallback progress, final CancellationSignal cancelled) {
            return new CopySummary(1, 2, true);
        }
    }

    // Holds the copy open until the test has asked the job to stop, then answers whatever the
    // signal it was handed says. That signal is the service's own wiring, so this fails if the
    // service ever stops passing the job's cancellation through.
    private static final class SignalWatchingCopyEngine extends CopyEngine {

        private final CountDownLatch copyStarted = new CountDownLatch(1);
        private final CountDownLatch mayFinish = new CountDownLatch(1);

        private SignalWatchingCopyEngine() {
            super(new NioMediaStore());
        }

        @Override
        public CopySummary copyTree(final Path source, final Path destination,
                                    final ProgressCallback progress, final CancellationSignal cancelled) {
            this.copyStarted.countDown();
            await(this.mayFinish);
            return new CopySummary(0, 1, cancelled.isCancelled());
        }

        private void releaseOnceTheCopyHasStarted() {
            await(this.copyStarted);
            this.mayFinish.countDown();
        }

        private static void await(final CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("the copy never reached the point this test waits for");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static final class RecordingProgress implements ProgressPort {

        private final List<String> events = new ArrayList<>();

        @Override
        public void phasesPlanned(final List<String> phases) {
            this.events.add("planned:" + String.join(", ", phases));
        }

        @Override
        public void phaseStarted(final String phase) {
            this.events.add("started");
        }

        @Override
        public void tick(final String phase, final int current, final int total) {
            this.events.add("tick " + current + "/" + total);
        }

        @Override
        public void phaseFinished(final String phase) {
            this.events.add("finished");
        }
    }

    private static final class NoClaims implements WorkingRootLock {

        @Override
        public void acquire(final Path root) throws WorkingRootBusyException {
        }

        @Override
        public void release(final Path root) {
        }

        @Override
        public void releaseAll() {
        }
    }

    private static Path createDirectory(final Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create " + directory, e);
        }
    }

    private static void write(final Path file, final String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
