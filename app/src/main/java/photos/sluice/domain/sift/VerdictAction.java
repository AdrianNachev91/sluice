package photos.sluice.domain.sift;

import java.util.Set;

/**
 * The action word a verdict carries as it travels between a vision provider and this app. Three are
 * fixed by {@link Verdict}'s own shapes. Any other word a verdict carries is a classification
 * category name.
 *
 * <p>Here so everything that has to agree on these words reads one definition.
 *
 * <p>A category sharing a word with a verdict would be unreachable. The word resolves to the
 * verdict wherever a response is read, so nothing could route a photo to the category of that
 * name. A run judging such a response pays for the refusal.
 */
public final class VerdictAction {

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
