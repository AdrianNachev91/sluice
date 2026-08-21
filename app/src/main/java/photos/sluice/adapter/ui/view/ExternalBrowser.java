package photos.sluice.adapter.ui.view;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * How this app hands an address to the user's own browser.
 *
 * <p>Held here rather than passed down. Only the desktop launch has what opens a browser, and the
 * screens that draw a link sit several layers below it. Threading it through all of them would put
 * a parameter on scenes that draw no link at all.
 *
 * <p>A screen drawing a link is not required to be running inside a launched app. A gallery render
 * and a test both build one, and neither has a browser to hand. So an unset opener is a state to
 * tolerate rather than refuse. The address is on screen either way, since a link is only ever put
 * on text that already shows it.
 */
final class ExternalBrowser {

    private static @Nullable Consumer<String> opener;

    private ExternalBrowser() {}

    /**
     * Says what opens an address from now on.
     *
     * @param toOpenWith a {@link Consumer} of {@link String} hands the address to a browser
     */
    static void openWith(final Consumer<String> toOpenWith) {
        opener = toOpenWith;
    }

    /**
     * Forgets whatever was opening addresses, so a test cannot decide what the next one does.
     */
    static void clear() {
        opener = null;
    }

    /**
     * Opens one address, or does nothing when nothing is set to open it.
     *
     * @param address {@link String} the address to open
     */
    static void open(final String address) {
        final Consumer<String> toOpenWith = opener;
        if (toOpenWith != null) {
            toOpenWith.accept(address);
        }
    }
}
