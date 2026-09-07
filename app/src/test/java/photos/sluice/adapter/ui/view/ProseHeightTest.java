package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// A wrapping block measures its own words and asks its parent for that height. An ask under what
// the skin then lays out draws the last line below the block's own bottom edge, where it reads as
// cut through the descenders. Windows has spare room from pixel snapping and hides it, so this runs
// on every CI platform and prints its numbers for the ones that do not.
class ProseHeightTest {

    private static final double WIDTH = 360;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aBlockAsksForAtLeastTheHeightItsOwnSkinLaysOut() throws Exception {
        final String[] sentences = {
            "One line only.",
            "A sentence long enough that it has to wrap across two lines at this width.",
            "A sentence long enough that it has to wrap across three whole lines at the width "
                    + "this test lays it out in, which is about what a settings box is.",
        };
        for (final String sentence : sentences) {
            final double[] measured = onFxThread(() -> {
                final TextArea area = SelectableText.prose(sentence);
                area.setPrefWidth(WIDTH);
                final var scene = new Scene(new StackPane(area), 400, 300);
                Stylesheet.applyTo(scene);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                final Text drawn = (Text) area.lookup(".text");
                return new double[] {
                    area.prefHeight(WIDTH),
                    drawn == null ? -1 : drawn.getLayoutBounds().getHeight(),
                    drawn == null ? -1 : drawn.getLineSpacing(),
                };
            });
            System.out.println("PROSE asked=" + measured[0] + " drawn=" + measured[1]
                    + " lineSpacing=" + measured[2] + " for: " + sentence);
            assertThat(measured[1]).isPositive();
            assertThat(measured[0]).isGreaterThanOrEqualTo(measured[1]);
        }
    }

    // A block's preferred WIDTH decides the line count a parent gets back. A parent laying out a
    // row asks how tall its child would be at that child's preferred width. A text area
    // answers a column count, the same number whatever it holds, and a sentence wider than that
    // comes back as two lines. The row is then built at twice the height the sentence draws in.
    @Test
    void aBlockPrefersTheWidthOfItsOwnWordsRatherThanAColumnCount() throws Exception {
        final String oneLine = "Keep your library and your inbox apart. Neither may sit inside the "
                + "other, and they may not be the same folder.";

        final double[] measured = onFxThread(() -> {
            final TextArea area = SelectableText.prose(oneLine);
            final var scene = new Scene(new StackPane(area), 1200, 300);
            Stylesheet.applyTo(scene);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            final Text drawn = (Text) area.lookup(".text");
            return new double[] {
                area.prefHeight(area.prefWidth(-1)),
                drawn == null ? -1 : drawn.getLayoutBounds().getHeight(),
            };
        });

        assertThat(measured[1]).isPositive();
        assertThat(measured[0]).isEqualTo(measured[1]);
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
