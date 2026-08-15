package photos.sluice.adapter.ui.view;

import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.fs.FileChannelWorkingRootLock;
import photos.sluice.adapter.ui.UiBootstrap;
import photos.sluice.config.UiLauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// The end-to-end proof for the whole entry chain: JavaFX starts, Spring is built inside it, and a
// window comes up. It runs on JavaFX's own headless backend, so it needs no display on either CI
// runner. Every read of the scene graph goes through the FX thread rather than straight off the
// test thread. That is the discipline every later screen test inherits from here.
class SluiceFxApplicationTest {

    private static final int TIMEOUT_SECONDS = 60;

    private @Nullable Application application;

    @BeforeAll
    static void registerPrimaryStage() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    // The toolkit builds the window class itself, so what the real launcher installs before calling
    // it has to be installed here too. Without it the failure window falls back to saying nothing
    // about which failure it met, so a test asserting the busy-root sentence could not fail.
    @BeforeEach
    void installWhatTheLauncherWould(@TempDir final Path configDir) {
        UiLauncher.install(configDir.resolve("config.yml"));
    }

    @AfterEach
    void forgetWhatWasInstalled() {
        UiBootstrap.clear();
    }

    @AfterEach
    void stopApplication() throws Exception {
        if (this.application != null) {
            // Runs the app's own stop(), which closes the Spring context. Every test here works in
            // its own temp root, so what this protects is the JVM, not the next test's root.
            FxToolkit.cleanupApplication(this.application);
            this.application = null;
        }
        FxToolkit.cleanupStages();
    }

    @Test
    void aConfiguredInstallOpensItsWindow(@TempDir final Path repoRoot, @TempDir final Path libraryRoot,
                                          @TempDir final Path inbox) throws Exception {
        this.startApplication(repoRoot, libraryRoot, inbox);

        assertThat(styleClassesOfRoot()).contains("screen").doesNotContain("failure-screen");
        // Starting is also where the app takes the working root. Nothing else proves the real lock
        // runs on the real startup path rather than only in its own unit tests.
        assertThat(repoRoot.resolve(".sluice-lock")).exists();
    }

    // Closing the app has to hand the root back, or a restart would be refused by the copy that
    // just exited. The kernel would free it eventually, but only once the process is gone, and
    // nothing guarantees that has happened by the time somebody launches again.
    @Test
    void closingTheAppGivesTheWorkingRootBack(@TempDir final Path repoRoot, @TempDir final Path libraryRoot,
                                              @TempDir final Path inbox) throws Exception {
        this.startApplication(repoRoot, libraryRoot, inbox);
        final Application started = this.application;
        this.application = null;
        FxToolkit.cleanupApplication(started);

        // Claiming it is the proof. A root the app never gave back refuses this outright, which is
        // exactly what the next launch would meet.
        final var lock = new FileChannelWorkingRootLock();
        try {
            assertThatCode(() -> lock.acquire(repoRoot)).doesNotThrowAnyException();
        } finally {
            lock.releaseAll();
        }
    }

    // One process at a time, per working root. The refusal has to reach the user as a window that
    // says so, since a second launch is the ordinary way anybody meets it.
    @Test
    void anInstallWhoseRootIsAlreadyHeldOpensAWindowSayingSo(@TempDir final Path repoRoot,
                                                             @TempDir final Path libraryRoot,
                                                             @TempDir final Path inbox) throws Exception {
        final var holder = new FileChannelWorkingRootLock();
        holder.acquire(repoRoot);
        try {
            this.startApplication(repoRoot, libraryRoot, inbox);

            assertThat(styleClassesOfRoot()).contains("failure-screen");
            assertThat(failureDetail()).contains("Another Sluice process is already running");
            // A node inside the separator draws the line, not the separator. A stylesheet rule
            // naming only the separator parses and colours nothing, leaving the default look's own
            // near-white line.
            final Paint ruleColour = onFxThread(() -> {
                final var line = (Region) scene().getRoot().lookup(".card-rule .line");
                return line.getBorder().getStrokes().getFirst().getTopStroke();
            });
            assertThat(ruleColour).isEqualTo(Color.web("#dfe2e7"));
        } finally {
            holder.releaseAll();
        }
    }

    // A machine where nobody has chosen a folder yet is the ordinary first launch, not a failure.
    // The app comes up, and what it can do about it is the first-run flow rather than a stack trace.
    //
    // Started with no arguments, so nothing configures the paths. One assumption rides on that. An
    // OS environment variable outranks the bundled defaults. So a machine exporting a SLUICE_PATHS_
    // variable configures them anyway, and passes this for a reason unrelated to the code.
    @Test
    void anUnconfiguredInstallOpensItsWindowRatherThanAFailure() throws Exception {
        this.startApplication();

        assertThat(styleClassesOfRoot()).contains("screen").doesNotContain("failure-screen");
    }

    // The stylesheet is loaded off the classpath, so it can be present in source and absent from
    // the build. A window with no sheet attached renders unstyled and says nothing about why.
    @Test
    void theWindowCarriesTheBaseStylesheet(@TempDir final Path repoRoot, @TempDir final Path libraryRoot,
                                           @TempDir final Path inbox) throws Exception {
        this.startApplication(repoRoot, libraryRoot, inbox);

        assertThat(onFxThread(() -> scene().getStylesheets()))
                .anyMatch(sheet -> sheet.endsWith("/ui/sluice.css"));
    }

    // Loading the files and handing them to the window are separate steps, and only the second one
    // is what a desktop reads. A platform given none of them shows its own default and reports
    // nothing, so the window looks like any other program's.
    @Test
    void theWindowCarriesTheProductIcon(@TempDir final Path repoRoot, @TempDir final Path libraryRoot,
                                        @TempDir final Path inbox) throws Exception {
        this.startApplication(repoRoot, libraryRoot, inbox);

        assertThat(onFxThread(() -> FxToolkit.toolkitContext().getRegisteredStage().getIcons()))
                .isNotEmpty()
                .allSatisfy(icon -> assertThat(icon.isError()).isFalse());
    }

    private void startApplication(final Path repoRoot, final Path libraryRoot, final Path inbox) throws Exception {
        this.startApplication("--sluice.paths.repo-root=" + repoRoot,
                "--sluice.paths.library-root=" + libraryRoot,
                "--sluice.paths.inbox=" + inbox);
    }

    private void startApplication(final String... args) throws Exception {
        this.application = FxToolkit.setupApplication(SluiceFxApplication.class, args);
    }

    private static Scene scene() {
        return FxToolkit.toolkitContext().getRegisteredStage().getScene();
    }

    private static List<String> styleClassesOfRoot() throws Exception {
        return onFxThread(() -> List.copyOf(scene().getRoot().getStyleClass()));
    }

    private static String failureDetail() throws Exception {
        return onFxThread(() -> {
            final Parent root = scene().getRoot();
            return root.lookupAll(".failure-detail").stream()
                    .map(node -> ((Label) node).getText())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .failure-detail label in the window"));
        });
    }

    private static <T> T onFxThread(final Callable<T> read) throws Exception {
        return WaitForAsyncUtils.asyncFx(read).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
