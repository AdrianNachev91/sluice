package photos.sluice.adapter.ui.view;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The look every dialog in this app wears, and the one question it knows how to put.
 *
 * <p>A dialog builds its own scene, so nothing a screen dressed before reaches it. Undressed, what
 * appears is the platform's own confirmation look: a question-mark badge, a stock title-bar icon,
 * colours from no palette of ours.
 *
 * <p>What a caller describes is the choices, not their placement. The row reads the same on every
 * desktop this app runs on: the choice the dialog wants pressed, then the rest, then the way out.
 * The three platforms order buttons by what each one is for and disagree with each other about
 * where the way out goes, so following any of them would make the same question read differently
 * depending on the machine.
 */
final class Dialogs {

    // The window sizes itself off label widths measured before this app's own fonts apply. A long
    // choice then ends up wider than the room the skin reserved, and ellipsizes. Sizing hints on
    // the buttons themselves lost that fight twice, in both directions. A floor on the pane ends
    // it: wide enough for the choices at their real widths, and still well inside the window the
    // dialog covers.
    private static final double PANE_FLOOR = 600;

    // Where the body text folds. Inside PANE_FLOOR by the pane's own padding.
    private static final double BODY_WIDTH = 560;

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
        QUIET
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
     * Puts one question and answers which choice was taken.
     *
     * <p>The row leads with the choice this dialog wants pressed and keeps the way out last, so
     * two or more ways to go ahead stay together rather than reading as equal options either side
     * of it.
     *
     * @param heading {@link String} what the question is about
     * @param question {@link String} the question, in the terms the choices answer it
     * @param choices the ways out of the dialog: one {@link Role#CANCEL}, at least one
     *     {@link Role#GO_AHEAD}, and exactly one {@link Emphasis#LOUD} among them
     * @return an {@link Optional} of {@link Choice} the one taken, empty where the dialog was
     *     cancelled or dismissed
     */
    static Optional<Choice> ask(final String heading, final String question, final Choice... choices) {
        final Map<ButtonType, Choice> buttons = buttonsFor(choices);
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
        return alert.showAndWait().map(buttons::get).filter(taken -> taken.role() == Role.GO_AHEAD);
    }

    /**
     * The buttons for a set of choices, the one the dialog leads with first.
     *
     * <p>Refuses a set the dialog could not draw an answer from, rather than drawing one nobody
     * meant. Each of the three is a shape a caller can write by accident and no reader would spot.
     * No way out, two answers to Enter, or a dialog whose only button leaves.
     *
     * <p>The leading choice comes first whatever it is for. A dialog about something the app cannot
     * undo leads with backing out, and a row putting the quiet choice ahead of it reads as an
     * afterthought stuck on the end. The offered order decides the rest.
     *
     * @param choices the choices to build
     * @return a {@link Map} of {@link ButtonType} to {@link Choice}, leading choice first
     */
    private static Map<ButtonType, Choice> buttonsFor(final Choice... choices) {
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
     * off, so {@code DialogsTest} pins the resulting order rather than this call.
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
        final var body = new Label(alert.getContentText());
        body.setWrapText(true);
        body.setPrefWidth(BODY_WIDTH);
        body.setMaxWidth(BODY_WIDTH);
        // The height half of the same fight: a wrapping label shorted on rows ellipsizes exactly
        // like an unwrapped one. Pinning the minimum to the preferred height makes the dialog grow
        // tall enough for every row the fold produces.
        body.setMinHeight(Region.USE_PREF_SIZE);
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
