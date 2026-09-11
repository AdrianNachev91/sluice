package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The instructions a reader hands to the agent they drive themselves, built from one waiting run.
 *
 * <p>Sluice ships no culler. On this route the reader supplies the intelligence, and the app
 * supplies only the folder and the contract. So this text is the whole of what stands between a
 * folder of sheets and an agent that can answer it.
 *
 * <p>Names the categories the run was prepped with rather than the ones configured now. A run waits
 * while somebody works, and the two moments can be days apart. {@link ShardValidator} judges the
 * answer against the recorded set. A prompt built from live config could ask for a category the
 * answer would then be refused for.
 *
 * <p>States the run's own size: how many sheets, how many photos, and how many were never put on a
 * sheet. An agent left to list the folder and count for itself cannot tell a run it has finished
 * from one it stopped part way through.
 *
 * <p>Says nothing about the grid. Nothing on disk records how many tiles wide a sheet was rendered.
 * A run prepped at five wide and read back after that setting moved to seven would state a layout
 * its sheets do not have.
 *
 * <p>Written for a person to paste somewhere, so it names the files literally and describes them in
 * the words the rest of the app uses. A sheet is what a reader calls it. {@code montage-001.jpg} is
 * what they will find in the folder.
 */
public final class LaunchPrompt {

    private static final String WHOLE_MESSAGE_NOTE = "Everything below is the contract those files have "
            + "to meet. Read it before you start, and if you hand this work to another agent, hand "
            + "it this whole message rather than a summary of it.";

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
        return opening(prep) + body(prep);
    }

    /**
     * The instructions for writing a run's rejected answers again.
     *
     * <p>The whole contract over again rather than a note pointing back at the first prompt. The
     * agent this reaches may be new to the run, or may have lost the conversation it ran in.
     *
     * @param prep {@link PrepDir} the run, as its own index records it
     * @param findings a {@link List} of {@link Finding} what the diagnosis said about it
     * @return {@link String} the text to hand an agent
     */
    public static String forRedo(final PrepDir prep, final List<Finding> findings) {
        final String problems = findings.stream()
                .filter(finding -> sheetOf(finding) != null)
                .map(finding -> "- " + finding.describe())
                .collect(Collectors.joining("\n"));
        return """
                Some of the answers under %s could not be used, and those decision files have been \
                discarded. Write them again.

                What was wrong with them:

                %s

                %s
                """.formatted(prep.prepDir(), problems, WHOLE_MESSAGE_NOTE) + body(prep);
    }

    /**
     * Which sheet a finding is about, or null where it is about the run rather than one sheet.
     *
     * <p>Only a sheet-scoped finding can be answered by writing one decision file again. Writing a
     * sheet again fixes none of the others.
     *
     * <p>Exhaustive over the sealed type on purpose. A finding added later fails to compile here
     * until somebody says whether redoing a sheet could answer it.
     *
     * @param finding {@link Finding} the problem
     * @return {@link String} the montage id it names, or null where it names none
     */
    public static @Nullable String sheetOf(final Finding finding) {
        return switch (finding) {
            case final Finding.PhotosNotJudged f -> f.montage();
            case final Finding.PhotoFromAnotherSheet f -> f.montage();
            case final Finding.MissingMontageField f -> f.montage();
            case final Finding.MontageFieldMismatch f -> f.montage();
            case final Finding.InvalidCategory f -> f.montage();
            case final Finding.MissingReason f -> f.montage();
            case final Finding.FillerReason f -> f.montage();
            case final Finding.MissingGroup f -> f.montage();
            case final Finding.MissingChosenReason f -> f.montage();
            case final Finding.WrongChosenCount f -> f.montage();
            case final Finding.TooFewRejects f -> f.montage();
            case final Finding.InvalidGroupSlug f -> f.montage();
            case final Finding.MissingFile f -> f.montage();
            case final Finding.FileOutOfScope f -> f.montage();
            case final Finding.CorruptShard f -> f.montage();
            // A group two sheets share names both, and picking which to rewrite is a choice
            // nothing here can make.
            case Finding.GroupSpansMultipleMontages _, Finding.DuplicateFileReference _,
                 Finding.VerdictUnreviewableOverlap _, Finding.SourceOutsideSorted _,
                 Finding.StrayShard _, Finding.MissingShard _, Finding.CorruptIndex _,
                 Finding.UnreadablePrepDir _, Finding.CorruptSidecar _, Finding.MissingSource _ ->
                    null;
        };
    }

    /**
     * The sheets a diagnosis blames, in the order they were first named, with no repeats.
     *
     * @param findings a {@link List} of {@link Finding} what the diagnosis said
     * @return a {@link List} of {@link String} the montage ids worth writing again
     */
    public static List<String> sheetsToRedo(final List<Finding> findings) {
        return findings.stream()
                .map(LaunchPrompt::sheetOf)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * How a fresh run's instructions open: where the sheets are, and how much there is to do.
     *
     * <p>Ends by saying the rest is not optional, and that a reader passing this work on passes the
     * whole of it. This paragraph reads as a complete brief on its own. An agent that summarises
     * before delegating drops the contract, and what arrives is a request nobody can answer.
     *
     * @param prep {@link PrepDir} the run
     * @return {@link String} the opening, ending on a blank line
     */
    private static String opening(final PrepDir prep) {
        return """
                Sift the photo sheets in %s

                There %s there, holding %s.%s Each sheet needs a decisions file of its own.

                %s
                """.formatted(prep.prepDir(), sheets(prep.montages()), photos(prep.photos()),
                unreviewableNote(prep.unreviewable().size()), WHOLE_MESSAGE_NOTE);
    }

    /**
     * Everything both prompts say: the files, the contract, how to judge, and the categories.
     *
     * @param prep {@link PrepDir} the run
     * @return {@link String} the body
     */
    private static String body(final PrepDir prep) {
        return """
                Each montage-NNN.jpg is a contact sheet of photos, laid out left to right and top \
                to bottom, with each photo's file name printed under it. The montage-NNN.json \
                beside it lists exactly the photos on that sheet, in the same order, under a \
                "photos" key. Each entry carries the photo's absolute path as "src", its file name \
                as "name", when it was taken as "time", and whether it arrived over a messaging \
                app as "received". A photo no sheet could show is in no sidecar either, so a \
                sidecar and its sheet always hold the same photos.

                For each sheet, decide what should happen to every photo on it, and write a \
                decisions-NNN.json beside its montage-NNN.jpg:

                {
                  "montage": "montage-001",
                  "decisions": [
                    {"file": "<absolute path>", "action": "keep"},
                    {"file": "<absolute path>", "action": "junk", "reason": "photo of a receipt"},
                    {"file": "<absolute path>", "action": "near-dup-chosen", "group": "harbour", \
                "chosen_reason": "the sharpest of the three"},
                    {"file": "<absolute path>", "action": "near-dup-reject", "group": "harbour", \
                "reason": "same shot, eyes closed"}
                  ]
                }

                How to write one:

                - Every photo on the sheet gets exactly one entry, keepers included. A photo left \
                out is refused, and so is an entry for a photo on another sheet.
                - Copy "file" exactly as the sidecar spells its "src". Match a tile to its sidecar \
                entry by the file name printed under it, rather than by counting positions.
                - "montage" is the name of the sheet this file is for, matching its own number \
                rather than the one in the example. The sidecar has a field of the same name \
                holding a full path. That is not this one.
                - "action" is keep, near-dup-chosen, near-dup-reject, or one of these category \
                names: %s. What each one means is at the end.
                - A keep carries nothing but its file and its action. Every other entry needs a \
                reason, and a near-dup-chosen carries chosen_reason instead. Only the two near-dup \
                actions carry a group. Any key beyond the ones its own action shows above is \
                refused.
                - Each reason is about the photo it is written for. The reasons in the example are \
                there to show the shape.
                - A reason that is only a filler word is refused, and its sheet comes back to be \
                written again. These are refused when one of them is the whole reason: %s. The \
                same word inside a real description is fine, so "unknown person, back to camera" \
                passes.
                - A near-duplicate group is one group name shared by exactly one keeper and its \
                rejects. A group name belongs to a single sheet. Two photos you cannot choose \
                between are two ordinary keeps rather than a group.
                - A group name is lower case letters, digits and single hyphens, and no longer \
                than %d characters. "harbour-at-sunset" works, "Harbour at sunset" does not.
                - Write each decisions-NNN.json as you finish its sheet rather than all of them at \
                the end, so stopping part-way loses nothing.
                - A decisions file that is already there is one to replace.
                - Change nothing else in the folder.

                How to judge:

                %s

                What each category means here. A category is about what a photo is of: a clear \
                photo of people stays a keeper with a landscape behind them or a meal in front of \
                them, and only a photo whose subject is the landscape or the meal belongs in one. \
                Where none of these fits a photo, it is a keeper.

                %s
                """.formatted(categoryNames(prep.categories()), String.join(", ", ShardValidator.fillerWords()),
                ShardValidator.GROUP_SLUG_MAX_LENGTH, CullJudgement.TEXT, categories(prep.categories()));
    }

    /**
     * The category names on one line, where the format rules first offer them as values.
     *
     * <p>Named there as well as described at the end. A reader meeting {@code action} then learns
     * what it may hold without carrying the question to the bottom of the page.
     *
     * @param categories a {@link List} of {@link CullCategory} the set the run was prepped under
     * @return {@link String} the names, comma-separated
     */
    private static String categoryNames(final List<CullCategory> categories) {
        return categories.stream().map(CullCategory::name).collect(Collectors.joining(", "));
    }

    /**
     * How many sheets there are, carrying the verb so the sentence agrees with the count.
     *
     * @param montages int how many sheets the run rendered
     * @return {@link String} the phrase
     */
    private static String sheets(final int montages) {
        return montages == 1 ? "is 1 sheet" : "are " + montages + " sheets";
    }

    /**
     * How many photos are on those sheets, as a phrase.
     *
     * @param photos int how many photos the run found
     * @return {@link String} the phrase
     */
    private static String photos(final int photos) {
        return photos == 1 ? "1 photo" : photos + " photos";
    }

    /**
     * The sentence about photos that reached no sheet, or nothing at all where every photo did.
     *
     * <p>Said because the run's photo count and what the sheets actually show disagree otherwise.
     * An agent that notices the gap has no way to know it is expected.
     *
     * @param unreviewable int how many candidates could not be rendered as a tile
     * @return {@link String} the sentence, opening on a space, or empty
     */
    private static String unreviewableNote(final int unreviewable) {
        if (unreviewable == 0) {
            return "";
        }
        final String count = unreviewable == 1
                ? "One more photo could not be turned into a tile and is"
                : "Another " + unreviewable + " photos could not be turned into tiles and are";
        return " " + count + " on no sheet. Decide only about photos you can see.";
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
