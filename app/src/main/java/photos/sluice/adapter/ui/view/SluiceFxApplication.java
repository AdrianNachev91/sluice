package photos.sluice.adapter.ui.view;

import javafx.application.Application;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import photos.sluice.SluiceApplication;
import photos.sluice.adapter.ui.ShellPresenter;
import photos.sluice.adapter.ui.StartupSequence;
import photos.sluice.adapter.ui.UiBootstrap;

import java.util.Objects;

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
        this.present(stage);
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

    /**
     * Builds the Spring context and runs the startup sequence against it, recording whatever
     * stopped either one rather than letting it escape. Shared by {@code init()}'s own first
     * attempt and a config repair's retry. Both have to redo the whole thing, since a context that
     * failed to build the first time never left anything behind to retry against.
     */
    private void buildContextAndRun() {
        try {
            final var built = new SpringApplicationBuilder(SluiceApplication.class)
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
     * @param stage {@link Stage} the stage to draw into
     */
    private void present(final Stage stage) {
        final Throwable startupFailure = this.failure;
        if (startupFailure == null) {
            final var built = Objects.requireNonNull(this.context, "no failure means a context was built");
            stage.setScene(MainWindow.scene(built.getBean(ShellPresenter.class)));
            return;
        }
        final var presenter = UiBootstrap.reportAndPresent(startupFailure);
        stage.setScene(StartupFailureWindow.scene(presenter, new StartupFailureActions(
                () -> this.retryRun(stage),
                property -> this.retryAfterRepair(stage, () -> UiBootstrap.removeSetting(property)),
                () -> this.retryAfterRepair(stage, UiBootstrap::setAside))));
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
