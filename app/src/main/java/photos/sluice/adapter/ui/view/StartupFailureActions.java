package photos.sluice.adapter.ui.view;

import java.util.function.Consumer;

/**
 * What a button on the failure screen can ask the app to do. Plain callbacks, so the view forwards
 * a click without holding an opinion about what running it does.
 *
 * @param retry re-runs the startup sequence, for the busy-root card
 * @param removeSetting takes one setting out of the config file, named by the property passed in,
 *     then retries
 * @param setAside renames the whole config file aside, then retries
 */
record StartupFailureActions(Runnable retry, Consumer<String> removeSetting, Runnable setAside) {
}
