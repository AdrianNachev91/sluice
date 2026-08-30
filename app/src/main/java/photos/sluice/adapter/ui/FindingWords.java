package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.domain.cull.Finding;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One finding in the terms a reader would use, with the answers they can give it.
 *
 * <p>{@link Finding#describe()} is the other audience. It is the engine's own prose, naming shard
 * fields, decision indexes and absolute paths, for a report somebody technical reads.
 */
final class FindingWords {

    // A sheet's id is montage-004 on disk, and a reader meets it as Sheet 4. The engine's own word
    // and its zero padding are both machine-facing.
    private static final Pattern SHEET_NUMBER = Pattern.compile("^montage-(\\d+)$");

    private static final String SET_ASIDE_SHEET = "Go on without this sheet";

    private static final String APPLY_SHEET_ANYWAY = "Use this sheet's answers as they are";

    // Names both checks the damaged record costs, not only the scope one. Coverage reads the same
    // sidecar, so ShardValidator.checkCoverage is handed an empty sheet list and raises nothing.
    private static final Confirmation APPLY_ANYWAY_ASK = new Confirmation(
            "Use this sheet's answers unchecked?",
            "Every photo still exists, its category is one you configured, and nothing is ever "
                    + "overwritten. What cannot be checked is whether these answers are about the "
                    + "photos this sheet contained, or whether they cover all of them.",
            "Use them", "Cancel", false);

    private FindingWords() {
    }

    /**
     * One finding, and what a reader can do about it.
     *
     * @param problem {@link String} what went wrong
     * @param about what it happened to, or null where the problem is about the whole run
     * @param choices a {@link List} of {@link Choice} the answers on offer, empty where there are
     *     none
     */
    record Told(String problem, @Nullable String about, List<Choice> choices) {

        /**
         * Defensively copies the mutable list.
         *
         * @param problem {@link String} what went wrong
         * @param about what it happened to, or null
         * @param choices a {@link List} of {@link Choice} the answers on offer
         */
        Told {
            choices = List.copyOf(choices);
        }
    }

    /**
     * One answer on offer, in the words the button carries.
     *
     * @param answer {@link Answer} what pressing it settles
     * @param label {@link String} what the button says
     * @param leading boolean whether this is the answer the row is drawn to be reached for
     * @param confirm {@link Confirmation} what to ask first, or null where it needs no asking
     */
    record Choice(Answer answer, String label, boolean leading, @Nullable Confirmation confirm) {
    }

    /**
     * What to tell a reader about one finding.
     *
     * <p>Exhaustive over the sealed type on purpose. A finding added later fails to compile here
     * until somebody says what a reader should be told about it.
     *
     * <p>A finding's own remedy is not what earns a row its buttons. A stray set of answers
     * declares AUTO and reaches here only where the automatic repair declined. So these are the
     * answers left, rather than the ones the finding predicted.
     *
     * @param finding {@link Finding} the fault
     * @return {@link Told} what to say and what to offer
     */
    static Told of(final Finding finding) {
        return switch (finding) {
            case final Finding.MissingSource missing -> new Told(
                    "A photo this sift wants to move is not where it was.",
                    missing.file().toString(),
                    List.of(new Choice(Answer.RECHECK, "Look again", true, null),
                            new Choice(Answer.SKIP_FILE, "Go on without this photo", false, null)));
            case final Finding.DecisionUnreviewableOverlap overlap -> new Told(
                    "One photo was both judged and listed as one nobody could judge.",
                    overlap.decision().file().toString(),
                    List.of(new Choice(Answer.TRUST_DECISION, "Use the judgement", true, null),
                            new Choice(Answer.TREAT_AS_UNREVIEWABLE, "Leave the photo unjudged",
                                    false, null)));
            case final Finding.CorruptSidecar sidecar -> new Told(
                    "The record of which photos this sheet contained is damaged.",
                    sheet(sidecar.montage()) + ", " + sidecar.montage() + ".json",
                    List.of(new Choice(Answer.SET_ASIDE_SHEET, SET_ASIDE_SHEET, true, null),
                            new Choice(Answer.APPLY_SHEET_ANYWAY, APPLY_SHEET_ANYWAY, false,
                                    APPLY_ANYWAY_ASK)));
            case final Finding.StrayShard stray -> new Told(
                    "There is a set of answers that belongs to no sheet in this sift.",
                    stray.shardFile(),
                    List.of(new Choice(Answer.SET_ASIDE_STRAY_ANSWERS, "Go on without them", true, null)));
            case final Finding.PhotosNotJudged notJudged -> new Told(
                    "Some of the photos on a sheet were never judged.",
                    sheet(notJudged.montage()) + ", "
                            + RunWords.counted(notJudged.photos().size(), "photo", "photos")
                            + " with no verdict",
                    List.of());
            case final Finding.MissingShard missing ->
                    new Told("One sheet has not been judged yet.", sheet(missing.montage()), List.of());
            case final Finding.CorruptShard corrupt ->
                    new Told("One sheet's answers cannot be read.", sheet(corrupt.montage()), List.of());
            case final Finding.CorruptIndex corrupt -> new Told(
                    "Sluice cannot read its own record of what this sift covers.",
                    corrupt.indexPath().toString(), List.of());
            case final Finding.UnreadablePrepDir unreadable -> new Told(
                    "This sift's records could not be read at all.",
                    unreadable.prepDir().toString(), List.of());
            case final Finding.MissingMontageField missing -> new Told(
                    "A set of answers does not say which sheet it is for.",
                    sheet(missing.montage()), List.of());
            case final Finding.MontageFieldMismatch mismatch -> new Told(
                    "A sheet's answers say they are for a different sheet.",
                    sheet(mismatch.montage()) + ", answering for " + mismatch.declared(), List.of());
            case final Finding.InvalidCategory invalid -> new Told(
                    "One photo was put in a category this sift does not have.",
                    photoOn(invalid.montage(), invalid.index()) + ", category "
                            + invalid.category(), List.of());
            case final Finding.MissingReason missing -> new Told(
                    "One photo was judged with no reason given.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.MissingGroup missing -> new Told(
                    "One photo was called a near-duplicate without saying which group it is in.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.MissingChosenReason missing -> new Told(
                    "One group of near-duplicates says which photo to keep but not why.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.WrongChosenCount wrong -> new Told(
                    "A group of near-duplicates does not name exactly one photo to keep.",
                    group(wrong.montage(), wrong.group()), List.of());
            case final Finding.TooFewRejects tooFew -> new Told(
                    "A group of near-duplicates has a photo to keep and nothing to keep it over.",
                    group(tooFew.montage(), tooFew.group()), List.of());
            case final Finding.InvalidGroupSlug invalid -> new Told(
                    "A group of near-duplicates has a name that cannot become a folder.",
                    group(invalid.montage(), invalid.group()), List.of());
            case final Finding.GroupSpansMultipleMontages spanning -> new Told(
                    "One group of near-duplicates is spread over more than one sheet.",
                    "Group " + spanning.group(), List.of());
            case final Finding.DuplicateFileReference duplicate -> new Told(
                    "One photo was answered for more than once.", duplicate.file(), List.of());
            case final Finding.MissingFile missing -> new Told(
                    "One answer does not say which photo it is about.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.PhotoFromAnotherSheet other -> new Told(
                    "A sheet judged a photo that a different sheet contained.",
                    photoOn(other.montage(), other.index()) + ", " + other.file(), List.of());
            case final Finding.FileOutOfScope outOfScope -> new Told(
                    "An answer names a photo this sift never showed.",
                    photoOn(outOfScope.montage(), outOfScope.index()) + ", "
                            + outOfScope.file(), List.of());
            case final Finding.SourceOutsideSorted outside -> new Told(
                    "This sift was asked to move a file from outside your Sorted folder.",
                    outside.file().toString(), List.of());
        };
    }

    /**
     * What a reader is told once an answer has been given.
     *
     * @param answer {@link Answer} what they chose
     * @return {@link String} the line the row collapses to, or null where the answer settles
     *     nothing and the run is only looked at again
     */
    static @Nullable String settled(final Answer answer) {
        return switch (answer) {
            case RECHECK -> null;
            case SKIP_FILE -> "The sift will go on without that photo. Nothing has touched it, so "
                    + "a later sift can still pick it up.";
            case TRUST_DECISION -> "The judgement stands, and the photo will be moved with it.";
            case TREAT_AS_UNREVIEWABLE -> "The photo stays where it is, unjudged.";
            case SET_ASIDE_SHEET -> "The sift will go on without that sheet. Its photos stay where "
                    + "they are, so a later sift can judge them afresh.";
            case APPLY_SHEET_ANYWAY -> "That sheet's answers will be used as they are.";
            case SET_ASIDE_STRAY_ANSWERS -> "The sift will go on without those answers.";
        };
    }

    /**
     * A sheet, named by its number.
     *
     * <p>Falls back to the id itself where it carries no number. A reader still gets something
     * naming the sheet rather than a line with a gap in it.
     *
     * @param montage {@link String} the sheet's own id
     * @return {@link String} the line naming it
     */
    private static String sheet(final String montage) {
        final Matcher number = SHEET_NUMBER.matcher(montage);
        return "Sheet " + (number.matches() ? String.valueOf(Integer.parseInt(number.group(1))) : montage);
    }

    /**
     * One photo on a sheet, named by where its answer sits.
     *
     * @param montage {@link String} the sheet's own id
     * @param index int the answer's place in that sheet, counted from one
     * @return {@link String} the line naming it
     */
    private static String photoOn(final String montage, final int index) {
        return sheet(montage) + ", answer " + index;
    }

    /**
     * One group of near-duplicates on a sheet.
     *
     * @param montage {@link String} the sheet's own id
     * @param group {@link String} the group's own id
     * @return {@link String} the line naming it
     */
    private static String group(final String montage, final String group) {
        return sheet(montage) + ", group " + group;
    }
}
