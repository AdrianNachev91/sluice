package photos.sluice.domain.cull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The instructions a reader hands to the agent they drive themselves, built from one waiting run.
 *
 * <p>Sluice ships no culler. On this route the reader supplies the intelligence, and the app
 * supplies only the folder and the contract. So this text is the whole of what stands between a
 * folder of sheets and an agent that can answer it. A bare path would leave them to work the
 * contract out from the files.
 *
 * <p>Names the categories the run was prepped with rather than the ones configured now. A run waits
 * while somebody works, and the two moments can be days apart. {@link ShardValidator} judges the
 * answer against the recorded set. A prompt built from live config could ask for a category the
 * answer would then be refused for.
 *
 * <p>Written for a person to paste somewhere, so it names the files literally and describes them in
 * the words the rest of the app uses. A sheet is what a reader calls it. {@code montage-001.jpg} is
 * what they will find in the folder.
 */
public final class LaunchPrompt {

    /**
     * Prevents instantiation of this utility class.
     */
    private LaunchPrompt() {
    }

    /**
     * Builds the instructions for one waiting run.
     *
     * @param prep {@link PrepDir} the run, as its own index records it
     * @return {@link String} the text to hand an agent
     */
    public static String forRun(final PrepDir prep) {
        return """
                Sift the photo sheets in %s

                Each montage-NNN.jpg in that folder is a contact sheet of photos, laid out left to \
                right and top to bottom, with each photo's file name printed under it. The \
                montage-NNN.json beside it lists the same photos in the same order, and carries the \
                absolute path of each one.

                Look at every sheet and decide which photos should not be kept. For each \
                montage-NNN.jpg, write a decisions-NNN.json next to it:

                {
                  "montage": "montage-001",
                  "decisions": [
                    {"file": "<absolute path>", "action": "junk", "reason": "photo of a receipt"},
                    {"file": "<absolute path>", "action": "near-dup-chosen", "group": "harbour", \
                "chosen_reason": "the sharpest of the three"},
                    {"file": "<absolute path>", "action": "near-dup-reject", "group": "harbour", \
                "reason": "same shot, eyes closed"}
                  ]
                }

                How to write one:

                - A photo worth keeping is not listed at all. Only write down what is being set \
                aside or grouped.
                - Each photo gets one entry at most, across all the decision files together. The \
                same file named twice is refused.
                - Copy "file" exactly as the sidecar spells it.
                - "montage" is the sheet's own name, montage-001 and so on. The sidecar has a field \
                of the same name holding a full path. That is not this one.
                - "action" is one of the categories below, or near-dup-chosen or near-dup-reject.
                - Every entry needs a reason. A near-dup-chosen carries chosen_reason instead.
                - A near-duplicate group is one group name shared by exactly one keeper and its \
                rejects. A group name belongs to a single sheet.
                - A group name becomes a folder name, so it has to be lower case letters, digits \
                and single hyphens, and no longer than %d characters. "harbour-at-sunset" works, \
                "Harbour at sunset" does not.
                - Write each decisions-NNN.json as you finish its sheet rather than all of them at \
                the end, so stopping part-way loses nothing.
                - Some photos could not be turned into tiles and are on no sheet. Decide only about \
                photos you can see.
                - Change nothing else in the folder.

                What each category means here:

                %s
                """.formatted(prep.prepDir(), ShardValidator.GROUP_SLUG_MAX_LENGTH,
                categories(prep.categories()));
    }

    /**
     * The category set as the run recorded it, one block each.
     *
     * @param categories a {@link List} of {@link CullCategory} the set the run was prepped under
     * @return {@link String} the blocks, separated by blank lines
     */
    private static String categories(final List<CullCategory> categories) {
        return categories.stream().map(LaunchPrompt::category).collect(Collectors.joining("\n\n"));
    }

    /**
     * One category, with its examples where it offers any.
     *
     * @param category {@link CullCategory} the card
     * @return {@link String} the block
     */
    private static String category(final CullCategory category) {
        final String examples = category.examples().isEmpty()
                ? ""
                : "\n  For example: " + String.join(", ", category.examples());
        return category.name() + "\n  " + category.description() + examples;
    }
}
