package photos.sluice.adapter.ui.view;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.ThemeSelection;
import photos.sluice.application.port.out.ThemeChoice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Everything runs on the FX thread, because dressing a scene reads the desktop's preferences and
// that call refuses any other thread.
class StylesheetTest {

    private static final String DARK_SHEET = "sluice-dark.css";

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void resetSelection() throws Exception {
        WaitForAsyncUtils.asyncFx(ThemeSelection::clear).get(10, TimeUnit.SECONDS);
    }

    @Test
    void aDressedSceneWearsTheDarkOverlayOnlyWhileTheDarkLookIsInForce() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            return sheetsOf(scene);
        })).anyMatch(sheet -> sheet.endsWith(DARK_SHEET));

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            return sheetsOf(scene);
        })).noneMatch(sheet -> sheet.endsWith(DARK_SHEET));
    }

    // A listener that fires once leaves the dark sheet in place from the first change, so asking
    // for it again afterwards proves nothing. The second look has to be one that takes the sheet
    // away.
    @Test
    void aSceneKeepsTrackingTheLookAfterTheFirstChange() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        final List<String> afterFirst = onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            return sheetsOf(scene);
        });
        final List<String> afterSecond = onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            return sheetsOf(scene);
        });

        assertThat(afterFirst).anyMatch(sheet -> sheet.endsWith(DARK_SHEET));
        assertThat(afterSecond).noneMatch(sheet -> sheet.endsWith(DARK_SHEET));
    }

    @Test
    void everyLookKeepsTheBaseSheetUnderneath() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        for (final ThemeChoice choice : ThemeChoice.values()) {
            final List<String> sheets = onFxThread(() -> {
                ThemeSelection.set(choice);
                return sheetsOf(scene);
            });
            assertThat(sheets).isNotEmpty();
            assertThat(sheets.getFirst()).endsWith("sluice.css");
        }
    }

    // NaN is what a never-sized stage reads back as, so it is what proves no size was set.
    @Test
    void aDisplayCapsTheWindowAndNeverSizesIt() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(0, 0, 1366, 728));

        assertThat(opened.maxWidth()).isEqualTo(1366);
        assertThat(opened.maxHeight()).isEqualTo(728);
        assertThat(opened.width()).isNaN();
        assertThat(opened.height()).isNaN();
    }

    @Test
    void aDisplayWithRoomToSpareIsStillTheCeiling() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(0, 0, 2560, 1440));

        assertThat(opened.maxWidth()).isEqualTo(2560);
        assertThat(opened.maxHeight()).isEqualTo(1440);
    }

    @Test
    void showingTheWindowLiftsTheCap() throws Exception {
        final Opened opened = shownWithin(new Scene(new StackPane(), 400, 300),
                new Rectangle2D(0, 0, 900, 600));

        assertThat(opened.maxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(opened.maxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void aShownWindowWithRoomToSpareKeepsTheSizeItsSceneAsked() throws Exception {
        final Opened opened = shownWithin(new Scene(new StackPane(), 640, 480),
                new Rectangle2D(0, 0, 2560, 1440));

        assertThat(opened.sceneWidth()).isEqualTo(640);
        assertThat(opened.sceneHeight()).isEqualTo(480);
    }

    // The area here starts well right of the screen's own origin, the way a taskbar down the left
    // edge leaves it. That is the case that would tempt a position of our own.
    @Test
    void aCappedWindowIsNeverPlaced() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(120, 40, 900, 600));

        assertThat(opened.x()).isNaN();
        assertThat(opened.y()).isNaN();
    }

    // Either side reading zero is enough to leave the window alone, so the guard joins the two
    // sides with an "or". An "and" would let this area through and cap the window to no width.
    @Test
    void aDisplayReportingNoRoomOnOneSideOnlyLeavesTheWindowUncapped() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(0, 0, 0, 1000));

        assertThat(opened.maxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(opened.maxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    // Without the guard both figures would read 0, and the window would open at nothing.
    @Test
    void aDisplayReportingNoRoomLeavesTheWindowUncapped() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(0, 0, 0, 0));

        assertThat(opened.maxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(opened.maxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    // Rectangle2D rejects a negative side but permits NaN, and NaN fails every comparison. A guard
    // written as "<= 0" would let it through and cap the window to NaN.
    @Test
    void aDisplayReportingNoRoomAsNotANumberLeavesTheWindowUncapped() throws Exception {
        final Opened opened = openedWithin(new Rectangle2D(0, 0, Double.NaN, Double.NaN));

        assertThat(opened.maxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(opened.maxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    // Read as text rather than through a dressed scene. A scene reaches only the controls some
    // scenario happens to build, where the sheets carry every rule.
    //
    // Only a px size can be judged against a floor. An em or a percentage is a multiple of whatever
    // its parent resolved to, so it has no size of its own to check.
    @Test
    void noSheetSetsAFixedTypeSizeBelowTheLegibilityFloor() throws Exception {
        final List<Integer> found = new ArrayList<>();
        for (final String name : List.of("sluice.css", DARK_SHEET)) {
            final var sheet = new String(
                    Objects.requireNonNull(Stylesheet.class.getResourceAsStream("/ui/" + name)).readAllBytes(),
                    StandardCharsets.UTF_8);
            final Matcher sizes = Pattern.compile("-fx-font-size:\\s*(\\d+)px").matcher(sheet);
            while (sizes.find()) {
                found.add(Integer.valueOf(sizes.group(1)));
            }
        }

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(size -> assertThat(size).isGreaterThanOrEqualTo(12));
    }

    // Every figure is read on the FX thread and carried out as plain numbers, so no test reaches
    // into a Stage from the JUnit thread.
    private record Opened(double maxWidth, double maxHeight, double width, double height,
                          double x, double y, double sceneWidth, double sceneHeight) {
    }

    private static Opened openedWithin(final Rectangle2D area) throws Exception {
        return onFxThread(() -> {
            final var stage = new Stage();
            Stylesheet.openNoLargerThan(stage, area);
            return readingOf(stage);
        });
    }

    private static Opened shownWithin(final Scene scene, final Rectangle2D area) throws Exception {
        return onFxThread(() -> {
            final var stage = new Stage();
            stage.setScene(scene);
            Stylesheet.openNoLargerThan(stage, area);
            try {
                stage.show();
                return readingOf(stage);
            } finally {
                // In the finally so a failed assertion cannot leave a shown stage behind. Surefire
                // reuses forks, so it would outlive this class and redden whatever ran next.
                stage.close();
            }
        });
    }

    private static Opened readingOf(final Stage stage) {
        final Scene scene = stage.getScene();
        return new Opened(stage.getMaxWidth(), stage.getMaxHeight(),
                stage.getWidth(), stage.getHeight(), stage.getX(), stage.getY(),
                scene == null ? Double.NaN : scene.getWidth(),
                scene == null ? Double.NaN : scene.getHeight());
    }

    private static List<String> sheetsOf(final Scene scene) {
        return List.copyOf(scene.getStylesheets());
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
