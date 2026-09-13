package photos.sluice.domain.sift;

import java.util.List;

/**
 * The category the app supplies, always on and never configured.
 *
 * <p>It carries the verdict that a photo is worthless. Its name, its description and its being on
 * are all fixed here, and no settings file holds any of them.
 */
public final class JunkCategory {

    /** What the folder under the Review root is called, and the action a shard carries. */
    public static final String NAME = "junk";

    private static final String DESCRIPTION = "Objectively worthless photos. Blurry, out of focus, "
            + "or motion-smeared. Accidental shots: pocket, floor, or ceiling shots, a finger over "
            + "the lens, framing of nothing. Badly exposed: essentially all-black or all-white. "
            + "Screenshots and UI captures, unless funny. Photos OF a screen: a phone photo of a "
            + "monitor, laptop, TV, or projector showing code, a webpage, an app or game UI, a "
            + "chat, or stats. These look dim, low-contrast, and angled, with glare and a visible "
            + "bezel. At tile resolution they read as a dull grey rectangle with rows of small "
            + "text or UI chrome. This is the single most-missed junk class. When a faint tile "
            + "looks like that, it is almost always a photo of a screen. Documents and utility "
            + "shots: receipts, whiteboards, paperwork, price tags, parking-spot reminders, "
            + "delivery cards, letters, bills, forms, product and packaging shots. Received images "
            + "and memes that are clearly not the owner's own photos.";

    private static final SiftCategory CATEGORY =
            new SiftCategory(NAME, DESCRIPTION, List.of(), Boolean.TRUE);

    /**
     * Prevents instantiation of this static utility class.
     */
    private JunkCategory() {
    }

    /**
     * The card itself, for the set a run is prepped under.
     *
     * @return {@link SiftCategory} the junk card
     */
    public static SiftCategory category() {
        return CATEGORY;
    }

    /**
     * Whether a name is the one this class owns.
     *
     * @param name {@link String} a candidate category name
     * @return boolean true where it is junk's
     */
    public static boolean isJunkName(final String name) {
        return NAME.equals(name);
    }
}
