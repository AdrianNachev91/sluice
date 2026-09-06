package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The look every dialog in this app wears, and the one question it knows how to put.
 *
 * <p>A dialog builds its own scene, so nothing a screen dressed before reaches it. Undressed, what
 * appears is the platform's own confirmation look: a question-mark badge, a stock title-bar icon,
 * colours from no palette of ours.
 *
 * <p>Every dialog names the window it belongs to. Without that it opens centred on the primary
 * display, which on a second monitor is nowhere near the app.
 *
 * <p>What a caller describes is the choices, not their placement. The row reads the same on every
 * desktop this app runs on: the choice the dialog wants pressed, then the rest, then the way out.
 * The three platforms order buttons by what each one is for, and disagree with each other about
 * where the way out goes. Following any of them would make the same question read differently
 * depending on the machine.
 */
final class Dialogs {

    // The window sizes itself off label widths measured before this app's own fonts apply. A long
    // choice then ends up wider than the room the skin reserved, and ellipsizes. Sizing hints on
    // the buttons themselves lost that fight twice, in both directions. A floor on the pane ends
    // it: wide enough for the choices at their real widths, and still well inside the window the
    // dialog covers.
    private static final double PANE_FLOOR = 650;

    // Where the body text folds. Inside PANE_FLOOR by the pane's own padding.
    private static final double BODY_WIDTH = 610;

    /**
     * What one button in a dialog is for.
     */
    enum Role {

        /** Does the thing the dialog is about. A dialog may offer more than one. */
        GO_AHEAD,

        /** Leaves without doing any of them. Every dialog has exactly one. */
        CANCEL
    }

    /**
     * How much of the reader's eye a choice should take.
     *
     * <p>Exactly one choice in a dialog is {@link #LOUD}, and it is also the one Enter presses. It
     * is not always a {@link Role#GO_AHEAD}: where the dialog asks about something the app cannot
     * put back, the way out is what should be loud.
     */
    enum Emphasis {

        /** The choice the dialog leads with. */
        LOUD,

        /** Offered, not urged. */
        QUIET;

        /**
         * The weight a choice is drawn at.
         *
         * @param leads boolean whether this is the choice the dialog leads with
         * @return {@link Emphasis} the weight
         */
        static Emphasis of(final boolean leads) {
            return leads ? LOUD : QUIET;
        }
    }

    /**
     * One button in a dialog.
     *
     * @param label {@link String} what it says
     * @param role {@link Role} what pressing it does
     * @param emphasis {@link Emphasis} how much of the eye it takes
     */
    record Choice(String label, Role role, Emphasis emphasis) {
    }

    /**
     * Prevents instantiation of this static factory class.
     */
    private Dialogs() {
    }

    /**
     * Whether a reader agreed to what a press is about to do.
     *
     * <p>An action carrying no question goes ahead unasked, so a caller with an optional
     * confirmation hands it straight over rather than branching on it.
     *
     * @param opensOver {@link Node} something on the window the question opens over
     * @param confirm {@link Confirmation} what to ask, or null where nothing needs asking
     * @return boolean true where the press should go ahead
     */
    static boolean agreed(final Node opensOver, final @Nullable Confirmation confirm) {
        return confirm == null || ask(opensOver, confirm.heading(), confirm.question(),
                new Choice(confirm.goAhead(), Role.GO_AHEAD, Emphasis.of(confirm.goAheadLeads())),
                new Choice(confirm.cancel(), Role.CANCEL, Emphasis.of(!confirm.goAheadLeads())))
                .isPresent();
    }

    /**
     * Puts one question and answers which choice was taken.
     *
     * <p>The row leads with the choice this dialog wants pressed and keeps the way out last. Two
     * or more ways to go ahead then stay together, rather than reading as equal options either
     * side of it.
     *
     * @param opensOver {@link Node} something on the window the question opens over
     * @param heading {@link String} what the question is about
     * @param question {@link String} the question, in the terms the choices answer it
     * @param choices the ways out of the dialog: one {@link Role#CANCEL}, at least one
     *     {@link Role#GO_AHEAD}, and exactly one {@link Emphasis#LOUD} among them
     * @return an {@link Optional} of {@link Choice} the one taken, empty where the dialog was
     *     cancelled or dismissed
     */
    static Optional<Choice> ask(final Node opensOver, final String heading, final String question,
                                final Choice... choices) {
        final Map<ButtonType, Choice> buttons = buttonsFor(choices);
        final Alert alert = asked(heading, question, buttons);
        openOver(alert, opensOver);
        return alert.showAndWait().map(buttons::get).filter(taken -> taken.role() == Role.GO_AHEAD);
    }

    /**
     * The wait's one control, as a button type.
     *
     * @param giveUp {@link String} what it says
     * @return {@link ButtonType} the control
     */
    static ButtonType givingUp(final String giveUp) {
        return new ButtonType(giveUp, ButtonBar.ButtonData.OK_DONE);
    }

    /**
     * The wait built and dressed, with nothing shown and no way out wired.
     *
     * <p>Separate from showing it for the same reason as {@link #asked}: a drawn dialog is the only
     * way anything can look at one.
     *
     * @param heading {@link String} what the dialog is about
     * @param message {@link String} what is happening
     * @param pressed {@link ButtonType} the one control, from {@link #givingUp}
     * @return {@link Alert} ready to show
     */
    static Alert waited(final String heading, final String message, final ButtonType pressed) {
        final var alert = new Alert(AlertType.CONFIRMATION, message, pressed);
        alert.setHeaderText(heading);
        dress(alert);
        return alert;
    }

    /**
     * The question built and dressed, with nothing shown yet.
     *
     * <p>Separate from showing it so a dialog can be drawn without a modal loop. A modal blocks its
     * caller until a button is pressed. So the screen gallery has no other way to reach the pane it
     * wants to snapshot.
     *
     * @param heading {@link String} what the question is about
     * @param question {@link String} the question itself
     * @param buttons a {@link Map} of {@link ButtonType} to {@link Choice} the ways out
     * @return {@link Alert} ready to show
     */
    static Alert asked(final String heading, final String question,
                       final Map<ButtonType, Choice> buttons) {
        final var alert = new Alert(AlertType.CONFIRMATION, question,
                buttons.keySet().toArray(new ButtonType[0]));
        alert.setHeaderText(heading);
        dress(alert);
        // Set after dressing, because grouping the go-aheads together takes the default-button role
        // with it. Cleared from the others in the same pass: a dialog with two default buttons has
        // two answers to Enter.
        buttons.forEach((button, choice) -> {
            if (alert.getDialogPane().lookupButton(button) instanceof final Button drawn) {
                drawn.setDefaultButton(choice.emphasis() == Emphasis.LOUD);
            }
        });
        return alert;
    }

    /**
     * Puts up a dialog that ends when the work it describes ends, rather than on a press.
     *
     * <p>Every other dialog here blocks its caller until a button is pressed. This one shows and
     * returns, because the caller has to keep making progress on the wait it describes. Its one
     * control does not end that wait either. It gives up on it, which is a different answer.
     *
     * <p>Nothing else takes it down. Escape and the window's own close button are both refused,
     * since a dismissed dialog would leave the app winding down behind a window still offering to
     * work.
     *
     * @param owner {@link Window} the window this covers
     * @param heading {@link String} what the dialog is about
     * @param message {@link String} what is happening, and what giving up on it costs
     * @param giveUp {@link String} what the one control says
     * @param onGiveUp {@link Runnable} what pressing it does
     * @return {@link Waiting} the way to take the dialog down once the wait is over
     */
    static Waiting waiting(final Window owner, final String heading, final String message,
                           final String giveUp, final Runnable onGiveUp) {
        final ButtonType pressed = givingUp(giveUp);
        final Alert alert = waited(heading, message, pressed);
        alert.initOwner(owner);
        // Cleared by the handle below, so this app's own close is the one that goes through.
        // Dialog.close() raises the same event Escape does, and a blanket refusal refuses both.
        final var ours = new AtomicBoolean();
        alert.setOnCloseRequest(event -> {
            if (!ours.get()) {
                event.consume();
            }
        });
        final Runnable close = () -> {
            ours.set(true);
            alert.setResult(pressed);
            alert.close();
        };
        // Taken down here rather than by the caller, because the wait is over either way it ends.
        // It also spares a caller the knot of a handle it can only get back after handing in the
        // press that needs it.
        if (alert.getDialogPane().lookupButton(pressed) instanceof final Button drawn) {
            drawn.setDefaultButton(true);
            drawn.setOnAction(_ -> {
                close.run();
                onGiveUp.run();
            });
        }
        alert.show();
        // Two guards for two routes, and the dialog's own is not enough. The title bar's close
        // button reaches the stage rather than the dialog, and a stage hides itself on a request
        // nobody consumes. Set after showing, since the stage does not exist before then.
        if (alert.getDialogPane().getScene().getWindow() instanceof final Stage stage) {
            stage.setOnCloseRequest(event -> {
                if (!ours.get()) {
                    event.consume();
                }
            });
        }
        return new Waiting(close);
    }

    /**
     * A dialog on screen for as long as the work it describes is still going.
     *
     * @param close {@link Runnable} takes it down
     */
    record Waiting(Runnable close) {
    }

    /**
     * The buttons for a set of choices, the one the dialog leads with first.
     *
     * <p>Refuses a set the dialog could not draw an answer from, rather than drawing one nobody
     * meant. Each of the three is a shape a caller can write by accident and no reader would spot.
     * No way out, two answers to Enter, or a dialog whose only button leaves.
     *
     * <p>The leading choice comes first whatever it is for. A dialog about something the app cannot
     * undo leads with backing out. A row putting the quiet choice ahead of that reads as an
     * afterthought stuck on the end. The offered order decides the rest.
     *
     * @param choices the choices to build
     * @return a {@link Map} of {@link ButtonType} to {@link Choice}, leading choice first
     */
    static Map<ButtonType, Choice> buttonsFor(final Choice... choices) {
        final long goAheads = Arrays.stream(choices).filter(c -> c.role() == Role.GO_AHEAD).count();
        final long cancels = Arrays.stream(choices).filter(c -> c.role() == Role.CANCEL).count();
        final long loud = Arrays.stream(choices).filter(c -> c.emphasis() == Emphasis.LOUD).count();
        if (goAheads < 1 || cancels != 1 || loud != 1) {
            throw new IllegalArgumentException("A dialog needs one way out, at least one way through, "
                    + "and exactly one choice to lead with; got " + goAheads + " way(s) through, "
                    + cancels + " way(s) out and " + loud + " leading");
        }
        final Map<ButtonType, Choice> buttons = new LinkedHashMap<>();
        for (final Choice choice : leadingFirst(choices)) {
            buttons.put(new ButtonType(choice.label(), dataFor(choice.role())), choice);
        }
        return buttons;
    }

    /**
     * Has a dialog's button row keep the order the buttons were built in.
     *
     * <p>A miss leaves the bar sorting the buttons itself, which is the whole of what this turns
     * off.
     *
     * @param alert {@link Alert} the dialog
     */
    private static void orderAsBuilt(final Alert alert) {
        if (alert.getDialogPane().lookup(".button-bar") instanceof final ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
        }
    }

    /**
     * The choices in the order the row draws them: the leading one, the rest as offered, the way
     * out last.
     *
     * <p>Pinning the way out to the end is what keeps two or more ways through together. A caller
     * writing them in any order still gets one block rather than a set of alternatives split by
     * the choice that answers none of them.
     *
     * <p>A dialog whose way out is itself the leading choice puts it first. Leading wins over last,
     * and with one way through there is nothing left for it to be split from.
     *
     * @param choices the choices as the caller wrote them
     * @return a {@link List} of {@link Choice} in the order the row draws them
     */
    private static List<Choice> leadingFirst(final Choice... choices) {
        return Arrays.stream(choices)
                .sorted(Comparator.comparingInt(Dialogs::placeOf))
                .toList();
    }

    /**
     * How early in the row one choice sits.
     *
     * @param choice {@link Choice} the choice
     * @return int lower for earlier
     */
    private static int placeOf(final Choice choice) {
        if (choice.emphasis() == Emphasis.LOUD) {
            return 0;
        }
        return choice.role() == Role.CANCEL ? 2 : 1;
    }

    /**
     * What one choice is, said in the terms the toolkit knows.
     *
     * <p>Only the way out carries anything the app relies on: it is what Escape closes, and what
     * the stylesheet dresses as the way out. Where each choice sits is decided by the order they
     * are built in instead.
     *
     * @param role {@link Role} what the choice is for
     * @return {@link ButtonBar.ButtonData} what the toolkit takes the choice to be
     */
    private static ButtonBar.ButtonData dataFor(final Role role) {
        return role == Role.CANCEL ? ButtonBar.ButtonData.CANCEL_CLOSE : ButtonBar.ButtonData.OK_DONE;
    }

    /**
     * Says which window a dialog belongs to, so it opens over that window.
     *
     * <p>An unowned dialog opens centred on the primary display whatever screen the app is on. It is
     * still modal, so nothing becomes unreachable. It appears somewhere the reader was not looking.
     *
     * <p>A node with no scene is left unowned rather than refused. That is a pane built outside a
     * window, which the screen gallery does, and a dialog is not what it is being built for.
     *
     * @param alert {@link Alert} the dialog
     * @param opensOver {@link Node} something on the window the dialog opens over
     */
    private static void openOver(final Alert alert, final Node opensOver) {
        if (opensOver.getScene() != null && opensOver.getScene().getWindow() != null) {
            alert.initOwner(opensOver.getScene().getWindow());
        }
    }

    /**
     * Dresses a dialog in the look the app is wearing.
     *
     * <p>Read once rather than bound, since a dialog is modal and gone before the look can change
     * under it.
     *
     * @param alert {@link Alert} the dialog to dress
     */
    private static void dress(final Alert alert) {
        alert.setGraphic(null);
        alert.getDialogPane().getStylesheets().setAll(Stylesheet.sheetsInForce());
        for (final ButtonType type : alert.getDialogPane().getButtonTypes()) {
            if (alert.getDialogPane().lookupButton(type) instanceof final Button button) {
                ButtonBar.setButtonUniformSize(button, false);
                button.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        alert.getDialogPane().setMinWidth(PANE_FLOOR);
        // The pane's own content label wraps or fails to by sizing arithmetic that has shifted
        // under every width change above. A label of our own with the bound stated makes the wrap
        // a fact rather than an outcome. The bound goes on the PREFERRED width, because that is
        // what the window sizes itself to. A wrapping label still prefers its full single-line
        // width. A maximum only caps the node after the window has already grown around it.
        final TextArea body = SelectableText.prose(alert.getContentText());
        body.setPrefWidth(BODY_WIDTH);
        body.setMaxWidth(BODY_WIDTH);
        alert.getDialogPane().setContent(body);
        alert.setOnShowing(_ -> {
            if (alert.getDialogPane().getScene().getWindow() instanceof final Stage stage) {
                stage.getIcons().setAll(BrandMark.icons());
            }
            // Without this the bar sorts the buttons itself, by what each one is for, and the
            // order they were built in counts for nothing.
            orderAsBuilt(alert);
        });
    }
}
