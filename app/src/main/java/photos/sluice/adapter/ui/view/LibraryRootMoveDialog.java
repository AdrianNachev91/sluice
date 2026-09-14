package photos.sluice.adapter.ui.view;

import javafx.concurrent.Task;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Asks what to do about the record of the library when a save moves the library root, then carries
 * out the answer.
 *
 * <p>Settings is the one screen that asks. The first-run card holds the same field and answers the
 * question itself, because a move needs every folder usable and that card is on screen exactly while
 * one is not. Kept apart from the screen anyway, since the flow is five sentences and a job that
 * runs for as long as a library takes.
 */
final class LibraryRootMoveDialog {

    private static final Logger log = LoggerFactory.getLogger(LibraryRootMoveDialog.class);

    /**
     * Prevents instantiation of this static factory class.
     */
    private LibraryRootMoveDialog() {
    }

    /**
     * Puts the question, and runs the chosen resolution off the FX thread.
     *
     * <p>Returns as soon as the dialog is answered. A copy runs for as long as the library takes,
     * so what it did is reported through the callbacks rather than returned.
     *
     * @param opensOver {@link Node} something on the window the question opens over
     * @param presenter {@link SettingsPresenter} carries out whichever resolution was chosen
     * @param needsResolution {@link SaveOutcome.NeedsLibraryRootResolution} the refused save, with
     *     the question already worded
     * @param working {@link Consumer} of {@link String} says how far the move has got
     * @param report {@link Consumer} of {@link SettingsPresenter.MoveOutcome} takes what happened.
     *     The screen decides what each state does to it, since only one of the three leaves
     *     anything new on disk to redraw from
     */
    // The reporting handle outlives this method by design. It is opened for the length of the copy
    // and closed by whichever of the task's two handlers ends it. There is no block for a
    // try-with-resources to wrap.
    @SuppressWarnings("resource")
    static void resolve(final Node opensOver, final SettingsPresenter presenter,
                        final SaveOutcome.NeedsLibraryRootResolution needsResolution,
                        final Consumer<String> working,
                        final Consumer<SettingsPresenter.MoveOutcome> report) {
        // The copy leads: it is the resolution that keeps what the record already knows, and the
        // one a reader who is unsure should land on.
        final var copyAndKeep = new Dialogs.Choice("Copy the old Library across",
                Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.LOUD);
        final var startFresh = new Dialogs.Choice("Start the record fresh",
                Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.QUIET);
        final Optional<Dialogs.Choice> chosen = Dialogs.ask(opensOver, "Moving the Library root",
                needsResolution.message(), copyAndKeep, startFresh,
                new Dialogs.Choice("Cancel", Dialogs.Role.CANCEL, Dialogs.Emphasis.QUIET));
        if (chosen.isEmpty()) {
            return;
        }
        final boolean copying = chosen.get() == copyAndKeep;
        // Reported only while the task runs. This dialog is not the screen that owns the progress
        // area, and the line it writes to goes with it.
        final AutoCloseable watching = presenter.reportMoving(working);
        final var task = new Task<SettingsPresenter.MoveOutcome>() {
            @Override
            protected SettingsPresenter.MoveOutcome call() {
                return copying ? presenter.moveLibraryRootCopyingTheIndex(needsResolution)
                        : presenter.moveLibraryRootWithAFreshIndex(needsResolution);
            }
        };
        // The outcome's own words, not a generic saved line. Each resolution says what it did with
        // the files and the record, and that is what the user chose between.
        task.setOnSucceeded(_ -> {
            stopWatching(watching);
            report.accept(task.getValue());
        });
        // Only an Error reaches here. The presenter catches every RuntimeException the move can
        // raise and words it, so this path is what is left over rather than the ordinary failure.
        task.setOnFailed(_ -> {
            stopWatching(watching);
            report.accept(new SettingsPresenter.MoveOutcome.Failed(
                    "The Library move stopped, and it's not known why. Report this as "
                            + "a bug, quoting this: " + task.getException()));
        });
        Thread.ofVirtual().start(task);
    }

    /**
     * Stops the dialog watching the port, whichever way the move ended.
     *
     * <p>Swallows what closing throws. The move is over and its outcome is about to be reported,
     * and losing that report over a piece of housekeeping would be the worse failure.
     *
     * @param watching {@link AutoCloseable} the handle to close
     */
    private static void stopWatching(final AutoCloseable watching) {
        try {
            watching.close();
        } catch (final Exception housekeeping) {
            log.warn("Could not stop watching the library move's progress", housekeeping);
        }
    }
}
