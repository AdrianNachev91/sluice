package photos.sluice.adapter.ui.view;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;

/**
 * Pays the cost of a control's first appearance up front, on a throwaway copy of every node type
 * the screens are built from.
 *
 * <p>The first control of a given class to reach the screen is dear, and every one after it is
 * cheap. The class has to be loaded, compiled and given its style-matching structures. Measured on
 * the settings screen, whose nine control types no earlier screen uses, the first visit cost 237ms
 * against 53ms for a later one. A quarter second is long enough to read as the screen being slow
 * to open. Spending it here instead costs nothing anyone is waiting for.
 *
 * <p>{@code ScreenWarmUpTest} is what keeps the set complete, rather than anyone remembering to
 * extend it when a screen introduces a control.
 */
final class ScreenWarmUp {

    private ScreenWarmUp() {}

    /**
     * One of every node type the screens use.
     *
     * @return {@link List} of {@link Node} throwaway instances, in no particular order
     */
    static List<Node> nodes() {
        return List.of(
                new BorderPane(), new Group(), new HBox(), new StackPane(), new VBox(),
                new Button(), new ComboBox<String>(), new Hyperlink(),
                new Label(), new PasswordField(), new RadioButton(), new ScrollPane(),
                new Separator(), new Spinner<Integer>(), new TextArea(), new TextField(),
                new TitledPane(), new ToggleButton(),
                new Circle(), new Rectangle(), new SVGPath(), new Text(), new TextFlow(),
                new ListCell<String>(), new VisionProviderCard.ModelChoiceCell());
    }

    /**
     * Warms every node type once the window has been painted.
     *
     * @param host {@link Pane} a pane in the live scene, borrowed and left as it was found
     */
    static void afterFirstFrame(final Pane host) {
        AfterFirstFrame.run(() -> warm(host));
    }

    /**
     * Applies styles and lays out one of every node type, then takes them all back out again.
     *
     * <p>They have to hang off a live scene while it happens. A node with no scene above it has no
     * stylesheets to match against. Building one in isolation would warm the class and leave the
     * expensive half undone.
     *
     * @param host {@link Pane} the pane to hang them from
     */
    private static void warm(final Pane host) {
        final var throwaway = new VBox();
        throwaway.setVisible(false);
        throwaway.setManaged(false);
        throwaway.getChildren().setAll(nodes());
        host.getChildren().add(throwaway);
        throwaway.applyCss();
        throwaway.layout();
        host.getChildren().remove(throwaway);
    }
}
