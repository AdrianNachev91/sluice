package photos.sluice.adapter.ui.view;

import javafx.concurrent.Task;
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
 * one is not. Kept apart from the screen anyway, since the flow is five sentences of copy and a job
 * that runs for as long as a library takes.
 */
final class LibraryRootMoveDialog {

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
     * @param presenter {@link SettingsPresenter} carries out whichever resolution was chosen
     * @param needsResolution {@link SaveOutcome.NeedsLibraryRootResolution} the refused save, with
     *     the question already worded
     * @param working {@link Consumer} of {@link String} says the move is under way
     * @param report {@link Consumer} of {@link SettingsPresenter.MoveOutcome} takes what happened.
     *     The screen decides what each state does to it, since only one of the three leaves
     *     anything new on disk to redraw from
     */
    static void resolve(final SettingsPresenter presenter,
                        final SaveOutcome.NeedsLibraryRootResolution needsResolution,
                        final Consumer<String> working,
                        final Consumer<SettingsPresenter.MoveOutcome> report) {
        // The copy leads: it is the resolution that keeps what the record already knows, and the
        // one a reader who is unsure should land on.
        final var copyAndKeep = new Dialogs.Choice("Copy the old library across",
                Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.LOUD);
        final var startFresh = new Dialogs.Choice("Start the record fresh",
                Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.QUIET);
        final Optional<Dialogs.Choice> chosen = Dialogs.ask("Moving the library root",
                needsResolution.message(), copyAndKeep, startFresh,
                new Dialogs.Choice("Cancel", Dialogs.Role.CANCEL, Dialogs.Emphasis.QUIET));
        if (chosen.isEmpty()) {
            return;
        }
        final boolean copying = chosen.get() == copyAndKeep;
        working.accept("Moving the library...");
        final var task = new Task<SettingsPresenter.MoveOutcome>() {
            @Override
            protected SettingsPresenter.MoveOutcome call() {
                return copying ? presenter.moveLibraryRootCopyingTheIndex(needsResolution)
                        : presenter.moveLibraryRootWithAFreshIndex(needsResolution);
            }
        };
        // The outcome's own words, not a generic saved line. Each resolution says what it did with
        // the files and the record, and that is what the user chose between.
        task.setOnSucceeded(_ -> report.accept(task.getValue()));
        // Only an Error reaches here. The presenter catches every RuntimeException the move can
        // raise and words it, so this path is what is left over rather than the ordinary failure.
        task.setOnFailed(_ -> report.accept(new SettingsPresenter.MoveOutcome.Failed(
                "The library move stopped, and Sluice cannot say why. Report this as a bug in Sluice, "
                        + "quoting this: " + task.getException())));
        Thread.ofVirtual().start(task);
    }
}
