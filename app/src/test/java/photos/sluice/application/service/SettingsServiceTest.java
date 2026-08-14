package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.FolderRootsChangeListener;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsServiceTest {

    @Test
    void aSavedSettingIsWrittenAndInForce(@TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var store = new RecordingStore();
        final var lock = new RecordingLock();
        final var service = settingsService(live, store, lock, new JobRunner());

        service.save(settings(after));

        assertThat(store.saved).containsExactly(settings(after));
        assertThat(live.current()).isEqualTo(settings(after));
        assertThat(service.settings()).isEqualTo(settings(after));
    }

    @Test
    void theWorkingRootIsClaimedBeforeAnythingIsWritten(@TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var lock = new RecordingLock();
        final List<Integer> claimsWhenWritten = new ArrayList<>();
        final SettingsStore store = _ -> claimsWhenWritten.add(lock.claimed.size());
        final var service = settingsService(live, store, lock, new JobRunner());

        service.save(settings(after));

        assertThat(claimsWhenWritten).containsExactly(1);
    }

    // Only a save that moves the working root asks for it. A process holding no root at all - a
    // command-line one, run while the desktop app has the folder open - can still change a category.
    @Test
    void aSaveThatLeavesTheFolderRootsAloneClaimsNothing(@TempDir final Path root) {
        final var live = new RecordingLive(settings(root));
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());

        service.save(withProvider(settings(root), "anthropic"));

        assertThat(lock.claimed).isEmpty();
        assertThat(live.current().provider()).isEqualTo("anthropic");
    }

    @Test
    void aWorkingRootAnotherProcessHoldsLeavesEverythingAsItWas(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var store = new RecordingStore();
        final var lock = new RefusingLock();
        final var service = settingsService(live, store, lock, new JobRunner());

        assertThatThrownBy(() -> service.save(settings(after)))
                .isInstanceOf(WorkingRootBusyException.class);

        assertThat(store.saved).isEmpty();
        assertThat(live.current()).isEqualTo(settings(before));
    }

    @Test
    void aFailedWriteLeavesNeitherTheClaimNorTheSettingsMoved(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var lock = new RecordingLock();
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, lock, new JobRunner());

        assertThatThrownBy(() -> service.save(settings(after)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("disk full");

        assertThat(lock.claimed).containsExactly(after.toAbsolutePath().normalize(),
                before.toAbsolutePath().normalize());
        assertThat(live.current()).isEqualTo(settings(before));
    }

    @Test
    void anUnconfiguredInstallClaimsTheWorkingRootTheFirstSaveNames(@TempDir final Path root) {
        final var live = new RecordingLive(unconfigured());
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());

        service.save(settings(root));

        assertThat(lock.claimed).containsExactly(root.toAbsolutePath().normalize());
    }

    // There is no earlier root to move the claim back to, so the claim is given up instead. Left
    // held, it would lock a folder this process never went on to use, for as long as it runs.
    @Test
    void aFailedFirstSaveGivesUpTheRootItJustClaimed(@TempDir final Path root) {
        final var live = new RecordingLive(unconfigured());
        final var lock = new RecordingLock();
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, lock, new JobRunner());

        assertThatThrownBy(() -> service.save(settings(root))).isInstanceOf(IllegalStateException.class);

        assertThat(lock.claimed).containsExactly(root.toAbsolutePath().normalize());
        assertThat(lock.releases).isEqualTo(1);
    }

    @Test
    void aFailedSaveReportsAFailedTakeBackAgainstTheFailureThatCausedIt(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, new RefusingSecondClaim(), new JobRunner());

        assertThatThrownBy(() -> service.save(settings(after)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("disk full")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .hasOnlyElementsOfType(WorkingRootBusyException.class));
    }

    // The library moved and the working root did not, so there is no claim to move. A process that
    // holding no root can still make this change. That is a command-line one, run while the desktop
    // app has the folder open, not being refused a folder it was never going to touch.
    @Test
    void aSaveThatMovesOnlyTheLibraryRootClaimsNothing(@TempDir final Path root, @TempDir final Path library) {
        final var live = new RecordingLive(settings(root));
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());

        service.save(settingsWithLibrary(root, library));

        assertThat(lock.claimed).isEmpty();
        assertThat(live.current().paths().libraryRoot()).isEqualTo(library.toString());
    }

    // Held on, that folder would be locked against every other Sluice for the life of this process,
    // while this one has no working root at all.
    @Test
    void clearingTheWorkingRootGivesUpTheClaimOnIt(@TempDir final Path root) {
        final var live = new RecordingLive(settings(root));
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());

        service.save(unconfigured());

        assertThat(lock.claimed).isEmpty();
        assertThat(lock.releases).isEqualTo(1);
    }

    @Test
    void aSaveThatMovesTheWorkingRootTellsTheListeners(@TempDir final Path before, @TempDir final Path after) {
        final var listener = new RecordingListener();
        final var service = settingsService(new RecordingLive(settings(before)), new RecordingStore(),
                new RecordingLock(), new JobRunner(), List.of(listener));

        service.save(settings(after));

        assertThat(listener.calls).isEqualTo(1);
        assertThat(listener.lastWorkingRootMoved).isTrue();
    }

    @Test
    void theFirstSaveToNameAWorkingRootTellsTheListeners(@TempDir final Path root) {
        final var listener = new RecordingListener();
        final var service = settingsService(new RecordingLive(unconfigured()), new RecordingStore(),
                new RecordingLock(), new JobRunner(), List.of(listener));

        service.save(settings(root));

        assertThat(listener.calls).isEqualTo(1);
    }

    @Test
    void aSaveThatMovesNoFolderRootTellsNobody(@TempDir final Path root) {
        final var listener = new RecordingListener();
        final var service = settingsService(new RecordingLive(settings(root)), new RecordingStore(),
                new RecordingLock(), new JobRunner(), List.of(listener));

        service.save(withProvider(settings(root), "anthropic"));

        assertThat(listener.calls).isZero();
    }

    @Test
    void aSaveThatMovesOnlyTheLibraryRootTellsTheListenersButNotThatTheWorkingRootMoved(
            @TempDir final Path root, @TempDir final Path library) {
        final var listener = new RecordingListener();
        final var service = settingsService(new RecordingLive(settings(root)), new RecordingStore(),
                new RecordingLock(), new JobRunner(), List.of(listener));

        service.save(settingsWithLibrary(root, library));

        assertThat(listener.calls).isEqualTo(1);
        assertThat(listener.lastWorkingRootMoved).isFalse();
    }

    @Test
    void aSaveThatFailsPartWayTellsNobody(@TempDir final Path before, @TempDir final Path after) {
        final var listener = new RecordingListener();
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(new RecordingLive(settings(before)), store,
                new RecordingLock(), new JobRunner(), List.of(listener));

        assertThatThrownBy(() -> service.save(settings(after))).isInstanceOf(IllegalStateException.class);

        assertThat(listener.calls).isZero();
    }

    // The throwing listener is registered first, so the second one running at all is what proves the
    // failure was contained rather than merely not rethrown.
    @Test
    void aListenerThatThrowsDoesNotFailTheSaveOrStopTheNextOne(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var second = new RecordingListener();
        // An Error, not an exception, so narrowing the catch back to RuntimeException fails here.
        final FolderRootsChangeListener first = _ -> {
            throw new StackOverflowError();
        };
        final var service = settingsService(live, new RecordingStore(), new RecordingLock(), new JobRunner(),
                List.of(first, second));

        service.save(settings(after));

        assertThat(second.calls).isEqualTo(1);
        assertThat(live.current()).isEqualTo(settings(after));
    }

    // Blank is what a config file with the key present and empty binds to, and it names no folder
    // any more than an absent key does.
    @Test
    void savingABlankWorkingRootClaimsNothing() {
        final var lock = new RecordingLock();
        final var service = settingsService(new RecordingLive(unconfigured()), new RecordingStore(), lock,
                new JobRunner());

        service.save(SettingsFixture.settings(new PathSettings("  ", null, null)));

        assertThat(lock.claimed).isEmpty();
    }

    @Test
    void aFolderRootSetToAFolderThatIsNotThereIsRefused(@TempDir final Path root, @TempDir final Path after) {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var lock = new RecordingLock();
        final var service = settingsService(live, store, lock, new JobRunner());
        final Path missing = after.resolve("Library");
        final Settings candidate = SettingsFixture.settings(new PathSettings(after.toString(),
                missing.toString(), createDirectory(after.resolve("Inbox")).toString()));

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new NotADirectory(PathRole.LIBRARY_ROOT, missing)));

        assertThat(store.saved).isEmpty();
        assertThat(lock.claimed).isEmpty();
        assertThat(live.current()).isEqualTo(settings(root));
    }

    @Test
    void folderRootsThatSitInsideEachOtherAreRefused(@TempDir final Path root, @TempDir final Path after) {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var service = settingsService(live, store, new RecordingLock(), new JobRunner());
        final Path library = createDirectory(after.resolve("Library"));
        final Settings candidate = SettingsFixture.settings(new PathSettings(after.toString(),
                library.toString(), createDirectory(library.resolve("Inbox")).toString()));

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX)));

        assertThat(store.saved).isEmpty();
    }

    @Test
    void aFolderRootThatNamesNoPathThisSystemCouldHaveIsRefused(@TempDir final Path root,
                                                                @TempDir final Path after) {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var service = settingsService(live, store, new RecordingLock(), new JobRunner());
        // Neither path parser will encode a NUL, so this is a string Path.of refuses on every
        // platform rather than only on the one running the test.
        final String impossible = "photos" + (char) 0 + "inbox";
        final Settings candidate = SettingsFixture.settings(new PathSettings(after.toString(),
                createDirectory(after.resolve("Library")).toString(), impossible));

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new NotAPath(PathRole.INBOX, impossible)));

        assertThat(store.saved).isEmpty();
    }

    // One refused root beside one admitted root. A rule that filters to the unusable violations and
    // a rule that refuses whenever no violation is an unset one agree on every other fixture.
    @Test
    void anUnsetRootIsStillAdmittedWhenAnUnusableOneRefusesTheSave(@TempDir final Path root,
                                                                   @TempDir final Path after) {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var service = settingsService(live, store, new RecordingLock(), new JobRunner());
        final Path missing = after.resolve("Inbox");
        final Settings candidate = SettingsFixture.settings(
                new PathSettings(after.toString(), null, missing.toString()));

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new NotADirectory(PathRole.INBOX, missing)));

        assertThat(store.saved).isEmpty();
    }

    // The working root is the one value save reads for itself, to work out whether the claim moves.
    // Unparseable, it threw from there with no violation to show for it.
    @Test
    void aWorkingRootThatNamesNoPathThisSystemCouldHaveIsRefused(@TempDir final Path root) {
        final var live = new RecordingLive(settings(root));
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());
        final String impossible = "photos" + (char) 0 + "work";
        final Settings candidate = SettingsFixture.settings(new PathSettings(impossible, null, null));

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new NotAPath(PathRole.REPO_ROOT, impossible)));

        assertThat(lock.claimed).isEmpty();
    }

    // aSavedSettingIsWrittenAndInForce is the control: the same fixture shape, with nothing refusing
    // to resolve, saves.
    @Test
    void aFolderRootThatIsThereAndCannotBeResolvedIsRefusedWithTheRest(@TempDir final Path root,
                                                                       @TempDir final Path after) {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var lock = new RecordingLock();
        final Path library = createDirectory(after.resolve("Library"));
        final Settings candidate = settings(after);
        final var service = new SettingsService(live, store, lock, new JobRunner(),
                new PathValidationService(refusingToResolve(library), live), List.of());

        assertThatThrownBy(() -> service.save(candidate))
                .isInstanceOfSatisfying(PathsMisconfiguredException.class, e -> assertThat(e.violations())
                        .containsExactly(new Unreadable(PathRole.LIBRARY_ROOT, library)));

        assertThat(store.saved).isEmpty();
        assertThat(lock.claimed).isEmpty();
        assertThat(live.current()).isEqualTo(settings(root));
    }

    @Test
    void anInstallThatHasOnlyChosenItsWorkingRootSoFarIsSaved(@TempDir final Path root) {
        final var live = new RecordingLive(unconfigured());
        final var lock = new RecordingLock();
        final var service = settingsService(live, new RecordingStore(), lock, new JobRunner());

        service.save(SettingsFixture.settings(new PathSettings(root.toString(), null, null)));

        assertThat(lock.claimed).containsExactly(root.toAbsolutePath().normalize());
        assertThat(live.current().paths().repoRoot()).isEqualTo(root.toString());
    }

    @Test
    void aSaveThatMovesNoFolderRootIsNotRefusedByAMissingRoot(@TempDir final Path root) throws IOException {
        final var live = new RecordingLive(settings(root));
        final var service = settingsService(live, new RecordingStore(), new RecordingLock(), new JobRunner());
        final Settings sameRootsNewProvider = withProvider(settings(root), "anthropic");
        Files.delete(root.resolve("Library"));

        service.save(sameRootsNewProvider);

        assertThat(live.current().provider()).isEqualTo("anthropic");
    }

    @Test
    void aSaveRefusedForBothReasonsReportsTheUnusableRoot(@TempDir final Path root, @TempDir final Path after)
            throws InterruptedException {
        final var live = new RecordingLive(settings(root));
        final var jobRunner = new JobRunner();
        final var service = settingsService(live, new RecordingStore(), new RecordingLock(), jobRunner);
        final Settings candidate = SettingsFixture.settings(new PathSettings(after.toString(),
                after.resolve("Library").toString(), createDirectory(after.resolve("Inbox")).toString()));
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> job = jobRunner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        try {
            assertThatThrownBy(() -> service.save(candidate)).isInstanceOf(PathsMisconfiguredException.class);
        } finally {
            release.countDown();
            job.join();
        }
    }

    @Test
    void movingAFolderRootIsRefusedWhileAJobRuns(@TempDir final Path before, @TempDir final Path after)
            throws InterruptedException {
        final var live = new RecordingLive(settings(before));
        final var store = new RecordingStore();
        final var jobRunner = new JobRunner();
        final var service = settingsService(live, store, new RecordingLock(), jobRunner);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> job = jobRunner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        try {
            assertThatThrownBy(() -> service.save(settings(after)))
                    .isInstanceOf(JobInProgressException.class);
        } finally {
            release.countDown();
            job.join();
        }
        assertThat(store.saved).isEmpty();
        assertThat(live.current()).isEqualTo(settings(before));
    }

    // Only a folder root is gated. A run does read some of the rest as it goes, so a mid-run change
    // can reach it. That is the accepted trade. Gating everything would make Settings read-only for
    // the length of a cull.
    @Test
    void everySettingBesideTheFolderRootsIsStillSavedWhileAJobRuns(@TempDir final Path root)
            throws InterruptedException {
        final var live = new RecordingLive(settings(root));
        final var store = new RecordingStore();
        final var jobRunner = new JobRunner();
        final var service = settingsService(live, store, new RecordingLock(), jobRunner);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> job = jobRunner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        try {
            service.save(withProvider(settings(root), "anthropic"));
        } finally {
            release.countDown();
            job.join();
        }
        assertThat(live.current().provider()).isEqualTo("anthropic");
    }

    // The gate has to hold the job slot shut, not read it and then act. A watcher polling a prep
    // dir starts jobs from its own thread. A save that only asked whether one was running could be
    // overtaken between the question and the answer landing.
    @Test
    void noJobCanStartWhileASaveThatMovesAFolderRootIsStillRunning(
            @TempDir final Path before, @TempDir final Path after) throws InterruptedException {
        final var live = new RecordingLive(settings(before));
        final var jobRunner = new JobRunner();
        final var writing = new CountDownLatch(1);
        final var reachedSubmit = new CountDownLatch(1);
        final var submitted = new CountDownLatch(1);
        final var finishWriting = new CountDownLatch(1);
        final SettingsStore store = _ -> {
            writing.countDown();
            await(finishWriting);
        };
        final var service = settingsService(live, store, new RecordingLock(), jobRunner);
        final var saving = Thread.ofVirtual().start(() -> service.save(settings(after)));
        writing.await();
        final var submitting = Thread.ofVirtual().start(() -> {
            reachedSubmit.countDown();
            jobRunner.submit(_ -> "done");
            submitted.countDown();
        });
        // Without this the assertion below could pass because the thread never ran at all.
        reachedSubmit.await();

        try {
            assertThat(submitted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            finishWriting.countDown();
            saving.join();
            submitting.join();
        }
        assertThat(live.current()).isEqualTo(settings(after));
    }

    // Fails the moment the announce moves out of the runIfIdle lambda.
    @Test
    void noJobCanStartWhileTheListenersAreStillBeingTold(
            @TempDir final Path before, @TempDir final Path after) throws InterruptedException {
        final var jobRunner = new JobRunner();
        final var announcing = new CountDownLatch(1);
        final var reachedSubmit = new CountDownLatch(1);
        final var submitted = new CountDownLatch(1);
        final var finishAnnouncing = new CountDownLatch(1);
        final FolderRootsChangeListener slowListener = _ -> {
            announcing.countDown();
            await(finishAnnouncing);
        };
        final var service = settingsService(new RecordingLive(settings(before)), new RecordingStore(),
                new RecordingLock(), jobRunner, List.of(slowListener));
        final var saving = Thread.ofVirtual().start(() -> service.save(settings(after)));
        announcing.await();
        final var submitting = Thread.ofVirtual().start(() -> {
            reachedSubmit.countDown();
            jobRunner.submit(_ -> "done");
            submitted.countDown();
        });
        reachedSubmit.await();

        try {
            assertThat(submitted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            finishAnnouncing.countDown();
            saving.join();
            submitting.join();
        }
    }

    // Neither folder can be held now: the one the settings name was taken while this save ran, and
    // the one it claimed is a folder nothing names. Keeping the second would lock every other Sluice
    // out of a folder this process will never work in.
    @Test
    void aSaveThatCannotTakeTheOldRootBackGivesUpTheNewOneToo(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final var lock = new RefusingSecondClaim();
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, lock, new JobRunner());

        assertThatThrownBy(() -> service.save(settings(after))).isInstanceOf(IllegalStateException.class);

        assertThat(lock.releases).isEqualTo(1);
    }

    // A save reads the settings in force to work out what it is changing, then acts on that answer.
    // A second one running in between would decide from a state the first is halfway through
    // replacing.
    //
    // The first save here leaves the folder roots alone, so it never enters the job runner and never
    // holds the job slot. That slot would otherwise serialize these two on its own, and this would
    // pass with the save monitor deleted.
    @Test
    void aSecondSaveWaitsForTheFirstToFinish(@TempDir final Path before, @TempDir final Path after)
            throws InterruptedException {
        final var live = new RecordingLive(settings(before));
        final var writes = new AtomicInteger();
        final var firstWriting = new CountDownLatch(1);
        final var finishFirst = new CountDownLatch(1);
        final var reachedSecondSave = new CountDownLatch(1);
        final var secondWrote = new CountDownLatch(1);
        final SettingsStore store = _ -> {
            if (writes.incrementAndGet() == 1) {
                firstWriting.countDown();
                await(finishFirst);
            } else {
                secondWrote.countDown();
            }
        };
        final var lock = new RecordingLock();
        final var service = settingsService(live, store, lock, new JobRunner());
        final var first = Thread.ofVirtual().start(() -> service.save(withProvider(settings(before), "anthropic")));
        firstWriting.await();
        final var second = Thread.ofVirtual().start(() -> {
            reachedSecondSave.countDown();
            service.save(settings(after));
        });
        reachedSecondSave.await();

        try {
            // The window is margin, not a guess at how long anything takes. A save that waits cannot
            // reach the store at all until the line below releases the first one, so no load can
            // make this fail. A save that does not wait gets there in microseconds. The wait also
            // gives the second thread time to read the settings while the first still has them,
            // which is the state a stale read would be read from.
            assertThat(secondWrote.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            finishFirst.countDown();
            first.join();
            second.join();
        }
        assertThat(live.current().paths()).isEqualTo(settings(after).paths());
        // The deterministic half, which holds whatever the scheduler did. The second save decided
        // from the first one's result rather than the state it started on. Deciding from a stale
        // read claims the same root a second time.
        assertThat(lock.claimed).containsExactly(after.toAbsolutePath().normalize());
    }

    // Both failures have to reach the caller. A release that fails on the way out of a failed save
    // must not replace the failure that caused the save to fail in the first place.
    @Test
    void aFailedReleaseIsReportedAgainstTheSaveFailureRatherThanInsteadOfIt(
            @TempDir final Path before, @TempDir final Path after) {
        final var live = new RecordingLive(settings(before));
        final SettingsStore store = _ -> {
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, new RefusingSecondClaimAndRelease(), new JobRunner());

        assertThatThrownBy(() -> service.save(settings(after)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("disk full")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .hasSize(2)
                        .hasOnlyElementsOfType(WorkingRootBusyException.class));
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static SettingsService settingsService(final LiveSettings live, final SettingsStore store,
                                                   final WorkingRootLock lock, final JobRunner jobRunner) {
        return settingsService(live, store, lock, jobRunner, List.of());
    }

    private static SettingsService settingsService(final LiveSettings live, final SettingsStore store,
                                                   final WorkingRootLock lock, final JobRunner jobRunner,
                                                   final List<FolderRootsChangeListener> listeners) {
        return new SettingsService(live, store, lock, jobRunner,
                new PathValidationService(new NioMediaStore(), live), listeners);
    }

    // The real store everywhere except the one call the failure is about. Every other root in the
    // candidate still gets the verdict a real filesystem gives it.
    private static MediaReader refusingToResolve(final Path refused) {
        return new NioMediaStore() {
            @Override
            public Optional<Path> realDirectory(final Path path) {
                if (path.equals(refused)) {
                    throw new UncheckedIOException(new IOException("the share went away"));
                }
                return super.realDirectory(path);
            }
        };
    }

    private static Settings withProvider(final Settings settings, final String provider) {
        return new Settings(settings.paths(), provider, settings.providerSettings(), settings.categories(),
                settings.externalAgent(), settings.montage());
    }

    private static Settings unconfigured() {
        return SettingsFixture.settings(new PathSettings(null, null, null));
    }

    // The library moves to a folder of its own, the working root and inbox stay put. Builds its own
    // inbox rather than relying on a settings() call earlier in the test having made one.
    private static Settings settingsWithLibrary(final Path root, final Path library) {
        return SettingsFixture.settings(new PathSettings(root.toString(), library.toString(),
                createDirectory(root.resolve("Inbox")).toString()));
    }

    // The two folders are made real, the way a real install's are. A fixture naming folders nobody
    // created is refused before it reaches the claim, the write or the listeners.
    private static Settings settings(final Path root) {
        return SettingsFixture.settings(new PathSettings(root.toString(),
                createDirectory(root.resolve("Library")).toString(),
                createDirectory(root.resolve("Inbox")).toString()));
    }

    private static Path createDirectory(final Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create " + directory, e);
        }
    }

    private static final class RecordingLive implements LiveSettings {

        private Settings current;

        private RecordingLive(final Settings initial) {
            this.current = initial;
        }

        @Override
        public Settings current() {
            return this.current;
        }

        @Override
        public void apply(final Settings settings) {
            this.current = settings;
        }
    }

    private static final class RecordingStore implements SettingsStore {

        private final List<Settings> saved = new ArrayList<>();

        @Override
        public void save(final Settings settings) {
            this.saved.add(settings);
        }
    }

    private static final class RecordingListener implements FolderRootsChangeListener {

        private int calls;

        private boolean lastWorkingRootMoved;

        @Override
        public void folderRootsChanged(final boolean workingRootMoved) {
            this.calls++;
            this.lastWorkingRootMoved = workingRootMoved;
        }
    }

    private static final class RecordingLock implements WorkingRootLock {

        private final List<Path> claimed = new ArrayList<>();
        private int releases;

        @Override
        public void acquire(final Path workingRoot) {
            this.claimed.add(workingRoot);
        }

        @Override
        public void release() {
            this.releases++;
        }
    }

    private static final class RefusingLock implements WorkingRootLock {

        @Override
        public void acquire(final Path workingRoot) {
            throw new WorkingRootBusyException(workingRoot);
        }

        @Override
        public void release() {
        }
    }

    // Lets a save take the new root, then refuses both the take-back and giving the new one up.
    // Every way of putting the claim right fails, which is what leaves two failures to report.
    private static final class RefusingSecondClaimAndRelease implements WorkingRootLock {

        private int claims;

        @Override
        public void acquire(final Path workingRoot) {
            this.claims++;
            if (this.claims > 1) {
                throw new WorkingRootBusyException(workingRoot);
            }
        }

        @Override
        public void release() {
            throw new WorkingRootBusyException(Path.of("unreleasable"));
        }
    }

    // Lets the save take the new root, then refuses to give the old one back. That is the corner a
    // second process reaching the old folder in between would produce.
    private static final class RefusingSecondClaim implements WorkingRootLock {

        private int claims;
        private int releases;

        @Override
        public void acquire(final Path workingRoot) {
            this.claims++;
            if (this.claims > 1) {
                throw new WorkingRootBusyException(workingRoot);
            }
        }

        @Override
        public void release() {
            this.releases++;
        }
    }
}
