package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.out.ConfigFileRepairPort;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailureClassifier;

/**
 * What the desktop window needs before a Spring context exists, and cannot be handed on the way in.
 *
 * <p>The toolkit instantiates the window class itself, so there is no constructor for the wiring
 * layer to pass anything to. A startup failure has to be explained after the context that would
 * have carried these has died. So the wiring layer puts them here first, and the window reads them
 * back when it has a failure to present.
 *
 * <p>This is also where a startup failure reaches the log. The framework reports the failures that
 * stopped its own context starting, and nothing reports the ones raised after it was up. Those
 * would otherwise have the screen as their only account anywhere. A reader chasing a doubled trace
 * in the log is looking at one of the first kind, reported by both.
 */
public final class UiBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UiBootstrap.class);

    private static volatile @Nullable StartupFailureClassifier classifier;
    private static volatile @Nullable ConfigFileRepairPort repair;

    /**
     * Prevents instantiation of this static holder.
     */
    private UiBootstrap() {
    }

    /**
     * Hands the window what it will need if startup fails.
     *
     * @param failureClassifier {@link StartupFailureClassifier} says what kind of failure it was
     * @param configFileRepair {@link ConfigFileRepairPort} puts the user's config file right
     */
    public static void install(final StartupFailureClassifier failureClassifier,
                               final ConfigFileRepairPort configFileRepair) {
        classifier = failureClassifier;
        repair = configFileRepair;
    }

    /**
     * Records a startup failure and answers with the presenter that explains it.
     *
     * <p>A window still comes up when nothing was installed, saying what little can be said without
     * a classifier. The alternative is failing inside the code whose whole job is to explain a
     * failure, which would leave the user with no window at all.
     *
     * @param failure {@link Throwable} what stopped the app starting
     * @return {@link StartupFailurePresenter} the presenter over that failure
     */
    public static StartupFailurePresenter reportAndPresent(final Throwable failure) {
        log.error("Sluice could not start", failure);
        final StartupFailureClassifier installed = classifier;
        return new StartupFailurePresenter(installed == null
                ? new Unclassified("")
                : installed.classify(failure));
    }

    /**
     * The repair for the user's config file.
     *
     * @return {@link ConfigFileRepairPort} the repair, or null when nothing was installed
     */
    public static @Nullable ConfigFileRepairPort repair() {
        return repair;
    }

    /**
     * Forgets what was installed. Here so a harness sharing one process can put this back as it
     * found it. What is installed outlives the window it was installed for, so a test leaving its
     * own classifier behind is what lets the next one pass without installing anything.
     */
    public static void clear() {
        classifier = null;
        repair = null;
    }
}
