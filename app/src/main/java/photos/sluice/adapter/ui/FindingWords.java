package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Verdict;

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
     * @param problem {@link String} what went wrong, worded for one of them
     * @param forSeveral the same thing worded for several, with {@code {}} where the count goes,
     *     or null for a fault a run can only hold one of
     * @param about what it happened to, or null where the problem is about the whole run
     * @param choices a {@link List} of {@link Choice} the answers on offer, empty where there are
     *     none
     */
    record Statement(String problem, @Nullable String forSeveral, @Nullable String about,
                     List<Choice> choices) {

        /**
         * Defensively copies the mutable list.
         *
         * @param problem {@link String} what went wrong, worded for one of them
         * @param forSeveral the same thing worded for several, with a place for the count, or null
         * @param about what it happened to, or null
         * @param choices a {@link List} of {@link Choice} the answers on offer
         */
        Statement {
            choices = List.copyOf(choices);
        }

        /**
         * How to head a set of these, where there is more than one.
         *
         * @param count int how many were found
         * @return {@link String} the heading, or null where this fault carries no plural wording
         */
        @Nullable String heading(final int count) {
            return this.forSeveral == null ? null : this.forSeveral.replace("{}",
                    RunWords.grouped(count));
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
     * @return {@link Statement} what to say and what to offer
     */
    static Statement of(final Finding finding) {
        return switch (finding) {
            case final Finding.MissingSource missing -> new Statement(
                    "A photo this sift wants to move is not where it was.",
                    "{} photos this sift wants to move are not where they were.",
                    missing.file().toString(),
                    List.of(new Choice(Answer.RECHECK, "Look again", true, null),
                            new Choice(Answer.SKIP_FILE, "Go on without this photo", false, null)));
            case final Finding.VerdictUnreviewableOverlap overlap -> new Statement(
                    "One photo was both judged and listed as one nobody could judge.",
                    "{} photos were both judged and listed as ones nobody could judge.",
                    overlap.verdict().file().toString(),
                    List.of(new Choice(Answer.TRUST_DECISION, whatTrustingDoes(overlap.verdict()), true, null),
                            new Choice(Answer.TREAT_AS_UNREVIEWABLE, "Leave the photo unjudged",
                                    false, null)));
            case final Finding.CorruptSidecar sidecar -> new Statement(
                    "The record of which photos this sheet contained is damaged.",
                    "{} sheets have a damaged record of which photos they contained.",
                    sheet(sidecar.montage()) + ", " + sidecar.montage() + ".json",
                    List.of(new Choice(Answer.SET_ASIDE_SHEET, SET_ASIDE_SHEET, true, null),
                            new Choice(Answer.APPLY_SHEET_ANYWAY, APPLY_SHEET_ANYWAY, false,
                                    APPLY_ANYWAY_ASK)));
            case final Finding.StrayShard stray -> new Statement(
                    "There is a set of answers that belongs to no sheet in this sift.",
                    "There are {} sets of answers that belong to no sheet in this sift.",
                    stray.shardFile(),
                    List.of(new Choice(Answer.SET_ASIDE_STRAY_ANSWERS, "Go on without them", true, null)));
            case final Finding.PhotosNotJudged notJudged -> new Statement(
                    "Some of the photos on a sheet were never judged.",
                    "Some of the photos on {} sheets were never judged.",
                    sheet(notJudged.montage()) + ", "
                            + RunWords.counted(notJudged.photos().size(), "photo", "photos")
                            + " with no verdict",
                    List.of());
            case final Finding.MissingShard missing -> new Statement("One sheet has not been judged yet.",
                    "{} sheets have not been judged yet.", sheet(missing.montage()), List.of());
            case final Finding.CorruptShard corrupt -> new Statement("One sheet's answers cannot be read.",
                    "The answers on {} sheets cannot be read.", sheet(corrupt.montage()), List.of());
            // A diagnosis builds CorruptIndex and UnreadablePrepDir as the sole finding it answers
            // with, so neither can ever turn up beside another of its own kind.
            case final Finding.CorruptIndex corrupt -> new Statement(
                    "The record of what this sift covers is damaged.",
                    null, corrupt.indexPath().toString(), List.of());
            case final Finding.UnreadablePrepDir unreadable -> new Statement(
                    "This sift's records could not be read at all.",
                    null, unreadable.prepDir().toString(), List.of());
            case final Finding.MissingMontageField missing -> new Statement(
                    "A set of answers does not say which sheet it is for.",
                    "{} sets of answers do not say which sheet they are for.",
                    sheet(missing.montage()), List.of());
            case final Finding.MontageFieldMismatch mismatch -> new Statement(
                    "A sheet's answers say they are for a different sheet.",
                    "{} sheets carry answers that say they are for another sheet.",
                    sheet(mismatch.montage()) + ", answering for " + mismatch.declared(), List.of());
            case final Finding.InvalidCategory invalid -> new Statement(
                    "One photo was put in a category this sift does not have.",
                    "{} photos were put in categories this sift does not have.",
                    photoOn(invalid.montage(), invalid.index()) + ", category "
                            + invalid.category(), List.of());
            case final Finding.MissingReason missing -> new Statement(
                    "One photo was judged with no reason given.",
                    "{} photos were judged with no reason given.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.MissingGroup missing -> new Statement(
                    "One photo was called a near-duplicate without saying which group it is in.",
                    "{} photos were called near-duplicates without saying which group they are in.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.MissingChosenReason missing -> new Statement(
                    "One group of near-duplicates says which photo to keep but not why.",
                    "{} groups of near-duplicates say which photo to keep but not why.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.WrongChosenCount wrong -> new Statement(
                    "A group of near-duplicates does not name exactly one photo to keep.",
                    "{} groups of near-duplicates do not name exactly one photo to keep.",
                    group(wrong.montage(), wrong.group()), List.of());
            case final Finding.TooFewRejects tooFew -> new Statement(
                    "A group of near-duplicates has a photo to keep and nothing to keep it over.",
                    "{} groups of near-duplicates have a photo to keep and nothing to keep it over.",
                    group(tooFew.montage(), tooFew.group()), List.of());
            case final Finding.InvalidGroupSlug invalid -> new Statement(
                    "A group of near-duplicates has a name that cannot become a folder.",
                    "{} groups of near-duplicates have names that cannot become folders.",
                    group(invalid.montage(), invalid.group()), List.of());
            case final Finding.GroupSpansMultipleMontages spanning -> new Statement(
                    "One group of near-duplicates is spread over more than one sheet.",
                    "{} groups of near-duplicates are spread over more than one sheet.",
                    "Group " + spanning.group(), List.of());
            case final Finding.DuplicateFileReference duplicate -> new Statement(
                    "One photo was answered for more than once.",
                    "{} photos were answered for more than once.", duplicate.file(), List.of());
            case final Finding.MissingFile missing -> new Statement(
                    "One answer does not say which photo it is about.",
                    "{} answers do not say which photo they are about.",
                    photoOn(missing.montage(), missing.index()), List.of());
            case final Finding.PhotoFromAnotherSheet other -> new Statement(
                    "One photo was judged by a sheet that did not contain it.",
                    "{} photos were judged by sheets that did not contain them.",
                    photoOn(other.montage(), other.index()) + ", " + other.file(), List.of());
            case final Finding.FileOutOfScope outOfScope -> new Statement(
                    "An answer names a photo this sift never showed.",
                    "{} answers name photos this sift never showed.",
                    photoOn(outOfScope.montage(), outOfScope.index()) + ", "
                            + outOfScope.file(), List.of());
            case final Finding.SourceOutsideSorted outside -> new Statement(
                    "A photo this sift wants to move is not under the Sorted folder currently saved.",
                    "{} photos this sift wants to move are not under the Sorted folder currently saved.",
                    outside.file().toString(), List.of());
        };
    }

    /**
     * What a reader is told once an answer has been given.
     *
     * @param answer {@link Answer} what they chose
     * @return {@link String} what to report, or null where the answer settles
     *     nothing and the run is only looked at again
     */
    static @Nullable String settled(final Answer answer) {
        return switch (answer) {
            case RECHECK -> null;
            case SKIP_FILE -> "The sift will go on without that photo. Nothing has touched it, so "
                    + "a later sift can still pick it up.";
            case TRUST_DECISION -> "The judgement stands, and this sift will do what it says.";
            case TREAT_AS_UNREVIEWABLE -> "The judgement is dropped, and the photo joins the ones a "
                    + "sift could not judge.";
            case SET_ASIDE_SHEET -> "The sift will go on without that sheet. Its photos stay "
                    + "in Sorted, so a later sift can judge them afresh.";
            case APPLY_SHEET_ANYWAY -> "That sheet's answers will be used as they are.";
            case SET_ASIDE_STRAY_ANSWERS -> "The sift will go on without those answers.";
        };
    }

    /**
     * What using the judgement will do to the photo, which is not the same thing for every verdict.
     *
     * <p>A near-duplicate keeper is copied and its source never removed, so it stays in Sorted
     * exactly as a keep does. Only a classification and a rejected near-duplicate move.
     *
     * @param verdict {@link Verdict} what the sift said about the photo
     * @return {@link String} what the button says
     */
    private static String whatTrustingDoes(final Verdict verdict) {
        return switch (verdict) {
            case Verdict.Keep _, Decision.NearDupChosen _ -> "Keep it in Sorted";
            case Decision.Classification _, Decision.NearDupReject _ -> "Move it where the sift said";
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
