package photos.sluice.application.port.out;

/**
 * The settings the app is running on right now, and the one seam that replaces them.
 *
 * <p>Every setting takes effect the moment it is saved. Nothing restarts. That works because the
 * app reads settings through interfaces rather than holding bound values, so swapping what sits
 * behind those interfaces reaches every reader at once.
 *
 * <p>The seam is a port rather than a method on the class that binds the config file, which only
 * the wiring layer may name. Nothing else could then have reached it at all.
 *
 * <p>A port is reachable by any adapter, and {@link #apply} on its own writes nothing to disk and
 * takes no working-root claim. So an adapter must go through the settings use case instead, and
 * {@code ArchitectureTest} keeps this interface out of the adapter layer entirely.
 */
public interface LiveSettings {

    /**
     * The settings in force at this moment.
     *
     * @return {@link Settings} the current settings
     */
    Settings current();

    /**
     * Makes the given settings the ones in force. Callers reading through the settings interfaces
     * see the new values on their next read.
     *
     * @param settings {@link Settings} the settings to put in force
     */
    void apply(Settings settings);
}
