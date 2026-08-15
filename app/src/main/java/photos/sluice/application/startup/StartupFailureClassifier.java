package photos.sluice.application.startup;

/**
 * Turns whatever stopped the app starting into a {@link StartupFailure}.
 *
 * <p>An interface because the only implementation reads the framework's own exceptions, which puts
 * it in the wiring layer. The surfaces that need an answer may not reach in there.
 */
@FunctionalInterface
public interface StartupFailureClassifier {

    /**
     * Says what kind of failure this is.
     *
     * @param failure {@link Throwable} what was raised while starting
     * @return {@link StartupFailure} the classified failure
     */
    StartupFailure classify(Throwable failure);
}
