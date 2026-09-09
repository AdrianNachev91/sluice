package photos.sluice.adapter.ui.view;

import javafx.application.Application;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import photos.sluice.SluiceApplication;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.QuitPresenter;
import photos.sluice.adapter.ui.ReviewPresenter;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.TroubleshootPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.adapter.ui.StartupSequence;
import photos.sluice.adapter.ui.UiBootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The desktop window's own lifecycle, wrapped around the Spring context it needs.
 *
 * <p>JavaFX starts first and Spring is built inside {@code init()}, which runs before any window
 * exists but after the toolkit is up. That ordering is what gives a startup failure somewhere to be
 * reported.
 *
 * <p>A failure here is held rather than rethrown, so {@code start()} can show it. Anything thrown
 * out of {@code init()} takes the toolkit down with it.
 *
 * <p>The failure screen's own buttons re-enter this same startup path, since retrying is nothing
 * more than trying it again. A busy working root only needs the sequence run a second time, because
 * the context that holds it is already up. A config repair needs the context rebuilt from nothing,
 * because it never came up the first time.
 */
public class SluiceFxApplication extends Application {

    // Written on the thread that runs init(), read on the FX application thread in start(), a
    // retry, and stop(). JavaFX orders those calls, but nothing in this class says so, and volatile
    // costs nothing to stop the question coming up again.
    private volatile @Nullable ConfigurableApplicationContext context;
    private volatile @Nullable StartupSequence startup;
    private volatile @Nullable Throwable failure;

    /**
     * Builds the Spring context from the forwarded command-line arguments, then runs the startup
     * sequence. Both the arguments and the context are Spring's ordinary ones: nothing about the
     * user's config file is worked out here.
     */
    @Override
    public void init() {
        this.buildContextAndRun();
    }

    /**
     * Shows the app, or the reason it could not start.
     *
     * @param stage {@link Stage} the primary stage JavaFX supplies
     */
    @Override
    public void start(final Stage stage) {
        stage.setTitle("Sluice");
        stage.getIcons().setAll(BrandMark.icons());
        ExternalBrowser.openWith(this.getHostServices()::showDocument);
        FileManager.openWith(folder ->
                Thread.ofVirtual().start(() -> FileManager.openInSystemFileManager(folder)));
        this.present(stage);
        Stylesheet.openNoLargerThan(stage, Screen.getPrimary().getVisualBounds());
        stage.show();
    }

    /**
     * Winds the app down, then closes the Spring context. Called by JavaFX as the last window
     * closes, and the ordinary way a run ends.
     *
     * <p>What winding down means is {@link StartupSequence#shutdown}'s to say. This method decides
     * only the ordering against the context: the context closes last, because a job still finishing
     * is running against beans inside it.
     *
     * <p>It closes even when winding down fails, so one stuck step cannot leave a whole context
     * running behind a window that is gone.
     */
    @Override
    public void stop() {
        ExternalBrowser.clear();
        final StartupSequence runningStartup = this.startup;
        try (final ConfigurableApplicationContext _ = this.context) {
            if (runningStartup != null) {
                runningStartup.shutdown();
            }
        }
    }

    /**
     * Puts the quit question in the way of the window's own close button.
     *
     * <p>Here rather than in {@link #stop}, which the toolkit calls once the last window has already
     * gone. A question needs a window to appear in, and by then there is none.
     *
     * <p>The close button, the platform's own quit key and the window manager all raise this one
     * request, so there is one place the question is put and one wind-down behind it.
     *
     * @param stage {@link Stage} the window being closed
     * @param quitting {@link QuitFlow} puts the questions and carries out the answers
     */
    private void askBeforeClosing(final Stage stage, final QuitFlow quitting) {
        stage.setOnCloseRequest(request -> {
            if (!quitting.mayClose(stage)) {
                request.consume();
            }
        });
    }

    /**
     * Builds the Spring context and runs the startup sequence against it, recording whatever
     * stopped either one rather than letting it escape. Redoes the whole thing every time, since a
     * context that failed to build leaves nothing behind to retry against.
     */
    private void buildContextAndRun() {
        try {
            final var built = new SpringApplicationBuilder(SluiceApplication.class)
                    // The banner is not log output, so the logging configuration cannot reach it.
                    // A packaged window has a stream attached whenever one was started from a
                    // terminal, and a framework's logo is not something this app says.
                    .bannerMode(Banner.Mode.OFF)
                    // Spring sets java.awt.headless true before the context starts, and
                    // GraphicsEnvironment latches it on first read. That leaves Desktop reporting
                    // itself unsupported, which is what opens a folder in the file manager. Left
                    // to the environment, a machine with no display still answers headless.
                    .headless(false)
                    .run(this.getParameters().getRaw().toArray(String[]::new));
            this.context = built;
            final var sequence = built.getBean(StartupSequence.class);
            this.startup = sequence;
            sequence.run();
            this.failure = null;
        } catch (final RuntimeException | Error e) {
            this.failure = e;
        }
    }

    /**
     * Shows whichever scene the app's current state calls for: the shell when nothing stopped it,
     * the failure screen otherwise.
     *
     * <p>The quit question is installed and taken away here rather than once at launch. A repair
     * swaps the shell in on a stage that was showing the failure screen. Installed at launch, the
     * question would be absent on exactly that path. Closing the window over a running job would
     * then take the unattended exit, with nothing on screen to say so.
     *
     * @param stage {@link Stage} the stage to draw into
     */
    private void present(final Stage stage) {
        // Before any scene is built, so a window opens already wearing the saved look rather than
        // dressing itself in the desktop's and then correcting.
        //
        // Ahead of the failure branch, not inside the success one, because a context can exist on
        // either. A busy working root is thrown by the startup sequence, which runs after the
        // context is built, so that failure screen can honour a saved theme. One that killed the
        // context has none to read and follows the desktop.
        this.applySavedThemeIfSettingsAreReadable();
        final Throwable startupFailure = this.failure;
        if (startupFailure == null) {
            final var built = Objects.requireNonNull(this.context, "no failure means a context was built");
            // Shared with the quit flow, so closing the window asks about typed work the same way
            // walking off the screen does.
            final var leavingLosesWork = new AtomicReference<BooleanSupplier>(() -> false);
            stage.setScene(MainWindow.scene(built.getBean(FirstRunPresenter.class),
                    built.getBean(SettingsPresenter.class),
                    built.getBean(VisionProviderPresenter.class),
                    built.getBean(PhotoCategoriesPresenter.class),
                    built.getBean(RunLauncherPresenter.class),
                    built.getBean(RunsPresenter.class),
                    built.getBean(TroubleshootPresenter.class),
                    built.getBean(ReviewPresenter.class),
                    leavingLosesWork));
            this.askBeforeClosing(stage, new QuitFlow(built.getBean(QuitPresenter.class),
                    leavingLosesWork));
            return;
        }
        // Taken away rather than left, since a stage that showed the shell can come back here on a
        // repair that itself failed. The failure screen holds no running work and nothing typed.
        stage.setOnCloseRequest(null);
        final var presenter = UiBootstrap.reportAndPresent(startupFailure);
        stage.setScene(StartupFailureWindow.scene(presenter, new StartupFailureActions(
                () -> this.retryRun(stage),
                property -> this.retryAfterRepair(stage, () -> UiBootstrap.removeSetting(property)),
                () -> this.retryAfterRepair(stage, UiBootstrap::setAside))));
    }

    /**
     * Puts the saved look in force when there is a context to read it from.
     *
     * <p>Silent when there is none, rather than falling back to a guess. A context that never
     * started has not read the user's config file, so no saved choice exists to honour and the
     * desktop's own scheme is all there is.
     */
    private void applySavedThemeIfSettingsAreReadable() {
        final ConfigurableApplicationContext built = this.context;
        if (built != null) {
            built.getBean(SettingsPresenter.class).applySavedTheme();
        }
    }

    /**
     * The busy-root retry: the context is already up, so running the sequence again is the whole
     * of it. A refused claim leaves everything exactly as it was, since a failed
     * {@code StartupSequence.run()} stops before touching a file.
     *
     * @param stage {@link Stage} the stage to redraw
     */
    private void retryRun(final Stage stage) {
        final StartupSequence sequence = this.startup;
        if (sequence != null) {
            try {
                sequence.run();
                this.failure = null;
            } catch (final RuntimeException | Error e) {
                this.failure = e;
            }
        }
        this.present(stage);
    }

    /**
     * A config-repair retry: the file first, then the whole startup path again, since no context
     * exists yet to run a sequence against. A repair action that itself fails is shown the same
     * way any other unclassified failure is, with its own trace to report from.
     *
     * @param stage {@link Stage} the stage to redraw
     * @param repair the repair to run before retrying
     */
    private void retryAfterRepair(final Stage stage, final Runnable repair) {
        try {
            repair.run();
        } catch (final RuntimeException | Error e) {
            this.failure = e;
            this.present(stage);
            return;
        }
        this.buildContextAndRun();
        this.present(stage);
    }
}
