package photos.sluice.adapter.ui.view;

import photos.sluice.adapter.ui.Location;

/**
 * What a screen calls to take the reader to another one.
 */
@FunctionalInterface
interface ScreenNavigation {

    /**
     * Shows another screen.
     *
     * @param there {@link Location} the screen to open
     */
    void setScreen(Location there);
}
