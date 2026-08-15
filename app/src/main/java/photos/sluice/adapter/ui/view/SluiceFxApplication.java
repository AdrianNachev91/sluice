package photos.sluice.adapter.ui.view;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import photos.sluice.SluiceApplication;
import photos.sluice.adapter.ui.StartupFailurePresenter;
import photos.sluice.adapter.ui.StartupSequence;

/**
 * The desktop window's own lifecycle, wrapped around the Spring context it needs.
 *
 * <p>JavaFX starts first and Spring is built inside {@code init()}, which runs before any window
 * exists but after the toolkit is up. That ordering is what gives a startup failure somewhere to be
 * reported. The other way round, a context that refuses to start has no window to say so in, and
 * the app simply never appears.
 *
 * <p>A failure here is held rather than rethrown, so {@code start()} can show it. Anything thrown
 * out of {@code init()} takes the toolkit down with it.
 */
public class SluiceFxApplication extends Application {

    // Written on the thread that runs init(), read on the FX application thread in start() and
    // stop(). JavaFX orders those calls, but nothing in this class says so, and volatile costs
    // nothing to stop the question coming up again.
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
        try {
            final var built = new SpringApplicationBuilder(SluiceApplication.class)
                    .run(this.getParameters().getRaw().toArray(String[]::new));
            this.context = built;
            final var sequence = built.getBean(StartupSequence.class);
            this.startup = sequence;
            sequence.run();
        } catch (final RuntimeException | Error e) {
            this.failure = e;
        }
    }

    /**
     * Shows the app, or the reason it could not start.
     *
     * @param stage {@link Stage} the primary stage JavaFX supplies
     */
    @Override
    public void start(final Stage stage) {
        final Throwable startupFailure = this.failure;
        final Scene scene = startupFailure == null
                ? MainWindow.scene()
                : StartupFailureWindow.scene(new StartupFailurePresenter(startupFailure));
        stage.setTitle("Sluice");
        stage.getIcons().setAll(BrandMark.icons());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Winds the app down, then closes the Spring context. Called by JavaFX as the last window
     * closes, and the ordinary way a run ends.
     *
     * <p>What winding down means is {@link StartupSequence#shutdown}'s to say, and it is the
     * mirror of what that class does at boot. This method's own contribution is the ordering
     * against the context: the context closes last, because a job still finishing is running
     * against beans inside it.
     *
     * <p>It closes even when winding down fails, so one stuck step cannot leave a whole context
     * running behind a window that is gone.
     */
    @Override
    public void stop() {
        final StartupSequence runningStartup = this.startup;
        try (final ConfigurableApplicationContext _ = this.context) {
            if (runningStartup != null) {
                runningStartup.shutdown();
            }
        }
    }
}
