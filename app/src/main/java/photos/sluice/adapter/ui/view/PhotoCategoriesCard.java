package photos.sluice.adapter.ui.view;

import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/**
 * The PHOTO CATEGORIES card on the Settings screen: one line about what the rules are, and the way
 * into the screen that edits them.
 *
 * <p>Its own screen rather than another card here. A card holds one control per setting, and this
 * holds a variable number of cards, each with three fields of its own. It is reached from here
 * rather than from the sidebar, which is settled at three destinations.
 */
final class PhotoCategoriesCard {

    private PhotoCategoriesCard() {}

    /**
     * Builds the card.
     *
     * @param onOpen {@link Runnable} opens the photo categories screen
     * @return {@link VBox} the card
     */
    static VBox build(final Runnable onOpen) {
        final var open = new Button("Edit photo categories");
        open.setId("settings-open-photo-categories");
        open.setOnAction(_ -> onOpen.run());
        return SettingsRows.card("PHOTO CATEGORIES",
                "Say what belongs in each category, so the agent looking at your photos knows "
                        + "where to put them.",
                open);
    }
}
