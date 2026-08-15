package photos.sluice.adapter.ui.view;

import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Arrays;
import java.util.List;

/**
 * The product mark: the one inside a window, and the icon the desktop shows for that window.
 *
 * <p>The two are built differently and carry the same colours. The in-window mark is drawn from
 * {@link #DROPLET} and sized by the stylesheet; the icon is a file. Both are the brand's green and
 * white, which the stylesheet holds outside the two looks so that a dark desktop does not show one
 * version of the mark in the window and another on the taskbar.
 *
 * <p>The shape itself originates on the website, where the same path draws both the favicon and the
 * sharing image. Nothing checks that the three copies agree, and each side regenerates its own
 * images with its own tool.
 */
final class BrandMark {

    // The droplet, in the 32x32 box the website's favicon draws it in. Read by the development tool
    // that regenerates the icon files, so that the shape is not drawn out a second time by hand.
    static final String DROPLET = "M16 6c-5 6-8 9.5-8 13.5A8 8 0 0 0 24 19.5C24 15.5 21 12 16 6Z";

    // What the path is scaled by inside the window. Tuned against the square the stylesheet gives
    // it, so it is a companion to `-fx-min-width` on `.brand-mark` rather than a free choice.
    private static final double GLYPH_SCALE = 0.62;

    // The sizes the icon files come in. A window hands the platform all of them and the platform
    // picks. The tool that draws them and the test that checks they shipped each list them again,
    // deliberately. Reading one list from another would let a size disappear from both at once.
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /**
     * Prevents instantiation of this static factory class.
     */
    private BrandMark() {
    }

    /**
     * The mark as it appears inside a window, taking its size and colours from the stylesheet.
     *
     * @return {@link StackPane} the mark
     */
    static StackPane styled() {
        final var droplet = new SVGPath();
        droplet.setContent(DROPLET);
        droplet.setScaleX(GLYPH_SCALE);
        droplet.setScaleY(GLYPH_SCALE);
        droplet.getStyleClass().add("brand-mark-glyph");

        final var mark = new StackPane(droplet);
        mark.getStyleClass().add("brand-mark");
        return mark;
    }

    /**
     * The icon at every size this app ships, for a window to hand to the platform.
     *
     * <p>Files rather than anything drawn here. A window icon has to be a decoded image: an image
     * rendered in this process is ignored, measured on Windows, and nothing is reported when that
     * happens. The failure looks like the call never ran.
     *
     * @return a {@link List} of {@link Image} the icon, smallest first
     */
    static List<Image> icons() {
        return Arrays.stream(ICON_SIZES).mapToObj(BrandMark::load).toList();
    }

    /**
     * Loads one icon file off the classpath.
     *
     * @param size int the width and height to load
     * @return {@link Image} the icon
     */
    private static Image load(final int size) {
        final var file = "/ui/icons/icon-" + size + "x" + size + ".png";
        final var resource = BrandMark.class.getResource(file);
        if (resource == null) {
            throw new IllegalStateException("The icon " + file + " is missing from the build.");
        }
        // By URL rather than by stream. Given a stream, the toolkit reads it and leaves it open,
        // so seven would be stranded on every start.
        return new Image(resource.toExternalForm());
    }
}
