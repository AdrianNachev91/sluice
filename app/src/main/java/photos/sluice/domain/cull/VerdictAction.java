package photos.sluice.domain.cull;

import java.util.Set;

/**
 * The action word a verdict carries as it travels between a vision provider and this app. Three are
 * fixed by {@link Decision}'s own shapes. Any other word a verdict carries is a classification
 * category name.
 *
 * <p>Here so the classes that have to agree on these words read one definition. A schema asks a
 * model for them, a codec reads them back off disk, and {@link CategoryName} refuses a category
 * named after one.
 *
 * <p>A category sharing a word with a verdict would be unreachable. The word resolves to the
 * verdict wherever a response is read, so nothing could route a photo to the category of that
 * name. A run judging such a response pays for the refusal.
 */
public final class VerdictAction {

    // The photo stays where it is. Never recorded, so a shard carries no keep.
    public static final String KEEP = "keep";
    public static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    public static final String NEAR_DUP_REJECT = "near-dup-reject";

    private static final Set<String> ALL = Set.of(KEEP, NEAR_DUP_CHOSEN, NEAR_DUP_REJECT);

    /**
     * Prevents instantiation of this static utility class.
     */
    private VerdictAction() {
    }

    /**
     * Whether a word names one of the fixed verdict actions rather than a category.
     *
     * @param word {@link String} the candidate word
     * @return boolean true when this is a verdict action
     */
    public static boolean isVerdictWord(final String word) {
        return ALL.contains(word);
    }
}
