package photos.sluice.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.FileChannelWorkingRootLock;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.YamlSettingsStore;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.config.SettingsFixture;
import photos.sluice.config.SettingsHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The service's own tests drive doubles, which prove the order it calls things in and nothing about
// what those calls do. This drives the real lock instead, so a working root that only half moves
// shows up as a folder the next process can or cannot open.
class SettingsServiceLockTest {

    private final FileChannelWorkingRootLock lock = new FileChannelWorkingRootLock();
    private final FileChannelWorkingRootLock otherProcess = new FileChannelWorkingRootLock();

    @AfterEach
    void releaseClaims() {
        this.lock.release();
        this.otherProcess.release();
    }

    @Test
    void aSavedWorkingRootIsTheOneThisProcessHolds(@TempDir final Path before, @TempDir final Path after,
                                                   @TempDir final Path configDir) {
        final var service = this.service(before, configDir);
        this.lock.acquire(before);

        service.save(settings(after));

        assertThatThrownBy(() -> this.otherProcess.acquire(after)).isInstanceOf(WorkingRootBusyException.class);
        assertThatCode(() -> this.otherProcess.acquire(before)).doesNotThrowAnyException();
    }

    @Test
    void aWorkingRootAnotherProcessAlreadyHoldsIsRefusedAndNothingMoves(
            @TempDir final Path before, @TempDir final Path after, @TempDir final Path configDir) {
        final var service = this.service(before, configDir);
        this.lock.acquire(before);
        this.otherProcess.acquire(after);

        assertThatThrownBy(() -> service.save(settings(after))).isInstanceOf(WorkingRootBusyException.class);

        assertThat(service.settings().paths().repoRoot()).isEqualTo(before.toString());
        assertThat(configDir.resolve("config.yml")).doesNotExist();
        assertThatThrownBy(() -> new FileChannelWorkingRootLock().acquire(before))
                .isInstanceOf(WorkingRootBusyException.class);
    }

    // Proved against the real lock rather than a counter, because what matters is whether the next
    // process can open the folder this one walked away from.
    @Test
    void clearingTheWorkingRootLeavesItOpenToTheNextProcess(@TempDir final Path root,
                                                            @TempDir final Path configDir) {
        final var service = this.service(root, configDir);
        this.lock.acquire(root);

        service.save(SettingsFixture.settings(new PathSettings(null, null, null)));

        assertThatCode(() -> this.otherProcess.acquire(root)).doesNotThrowAnyException();
    }

    // A process holding nothing, changing a root it never claims, while another has the working
    // root open. Claiming on every save would refuse this outright.
    @Test
    void aLibraryRootMovesWhileAnotherProcessHoldsTheWorkingRoot(
            @TempDir final Path root, @TempDir final Path library, @TempDir final Path configDir) {
        final var service = this.service(root, configDir);
        this.otherProcess.acquire(root);

        service.save(SettingsFixture.settings(new PathSettings(root.toString(), library.toString(),
                createDirectory(root.resolve("Inbox")).toString())));

        assertThat(service.settings().paths().libraryRoot()).isEqualTo(library.toString());
    }

    // Counting releases proves one happened, not which folder it let go of. Against the real lock,
    // what the next process can open is the answer.
    @Test
    void aSaveThatCannotTakeTheOldRootBackLeavesTheNewOneOpen(
            @TempDir final Path before, @TempDir final Path after) {
        final LiveSettings live = new SettingsHolder(settings(before));
        final SettingsStore store = _ -> {
            // The old folder taken by somebody else while this save was mid-write, which is what
            // makes taking it back impossible.
            this.otherProcess.acquire(before);
            throw new IllegalStateException("disk full");
        };
        final var service = settingsService(live, store, this.lock);
        this.lock.acquire(before);

        assertThatThrownBy(() -> service.save(settings(after))).isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> new FileChannelWorkingRootLock().acquire(after)).doesNotThrowAnyException();
    }

    private SettingsService service(final Path repoRoot, final Path configDir) {
        final LiveSettings live = new SettingsHolder(settings(repoRoot));
        return settingsService(live, new YamlSettingsStore(configDir.resolve("config.yml")), this.lock);
    }

    private static SettingsService settingsService(final LiveSettings live, final SettingsStore store,
                                                   final WorkingRootLock lock) {
        return new SettingsService(live, store, lock, new JobRunner(),
                new PathValidationService(new NioMediaStore(), live), List.of());
    }

    // The folder roots have to be there for the save's own check to pass, the same way a real
    // install's are.
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
}
