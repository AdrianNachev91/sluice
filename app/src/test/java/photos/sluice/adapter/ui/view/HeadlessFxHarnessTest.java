package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// A test of the harness rather than of Sluice. On whichever runner it runs, a window comes up with
// no display attached and simulated input reaches the control under it.
class HeadlessFxHarnessTest {

    @BeforeAll
    static void registerPrimaryStage() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void cleanupStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void aClickReachesTheControlUnderIt() throws Exception {
        final var clicks = new AtomicInteger();
        FxToolkit.setupStage(stage -> {
            final var button = new Button("Press me");
            button.setId("harness-button");
            button.setOnAction(_ -> clicks.incrementAndGet());
            stage.setScene(new Scene(new StackPane(button), 400, 300));
            stage.show();
        });

        new FxRobot().clickOn("#harness-button");

        assertThat(clicks).hasValue(1);
    }
}
