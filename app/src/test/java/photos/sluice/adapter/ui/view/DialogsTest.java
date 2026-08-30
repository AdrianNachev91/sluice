package photos.sluice.adapter.ui.view;

import javafx.scene.Group;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.view.Dialogs.Choice;
import photos.sluice.adapter.ui.view.Dialogs.Emphasis;
import photos.sluice.adapter.ui.view.Dialogs.Role;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The set a caller offers, checked before a window opens. What the dialog then looks like needs a
// real one on screen and is walked through by hand; these are the shapes it refuses to draw at all.
class DialogsTest {

    // A node with no window behind it, which is all these refusals need. The set is judged before
    // anything is built, so an owner never comes into it.
    private static final Node OVER = new Group();

    private static final Choice WAY_THROUGH = new Choice("Go", Role.GO_AHEAD, Emphasis.QUIET);
    private static final Choice WAY_OUT = new Choice("Stop", Role.CANCEL, Emphasis.LOUD);

    @Test
    void aDialogWithNoWayThroughIsRefused() {
        assertThatThrownBy(() -> Dialogs.ask(OVER, "Heading", "Question", WAY_OUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 way(s) through");
    }

    @Test
    void aDialogWithoutOneWayOutIsRefused() {
        assertThatThrownBy(() -> Dialogs.ask(OVER, "Heading", "Question", WAY_THROUGH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 way(s) out");
        assertThatThrownBy(() -> Dialogs.ask(OVER, "Heading", "Question", WAY_THROUGH, WAY_OUT,
                new Choice("Stop again", Role.CANCEL, Emphasis.QUIET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 way(s) out");
    }

    @Test
    void aDialogWithoutExactlyOneLoudChoiceIsRefused() {
        assertThatThrownBy(() -> Dialogs.ask(OVER, "Heading", "Question", WAY_THROUGH,
                new Choice("Stop", Role.CANCEL, Emphasis.QUIET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 leading");
        assertThatThrownBy(() -> Dialogs.ask(OVER, "Heading", "Question",
                new Choice("Go", Role.GO_AHEAD, Emphasis.LOUD), WAY_OUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 leading");
    }
}
