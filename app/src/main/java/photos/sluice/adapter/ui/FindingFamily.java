package photos.sluice.adapter.ui;

import photos.sluice.domain.cull.Finding;

import java.util.List;

/**
 * The kind of trouble a blocked sift ran into, in the terms a reader would use.
 *
 * <p>A finding names one exact fault, down to the field of the shard it came out of. A card has room
 * for one clause, so it says which family went wrong rather than which finding.
 *
 * <p>This lives beside the screens rather than on {@link Finding}. The grouping is chosen for what a
 * sentence can say, not for how the engine treats a fault. Two findings in one family can have
 * different remedies.
 */
enum FindingFamily {

    /** Some photos on a sheet were never judged, so the sift does not know what to do with them. */
    COVERAGE("Some of the sheets were not fully judged", "this sift"),

    /** The agent's answers are unusable: absent, malformed, or contradicting each other. */
    DECISIONS("There were structural problems with the decisions", "this sift"),

    /** Apply could not get from a decision to a usable photo: blank, out of scope, or gone. */
    PHOTOS("Some decisions could not be matched to a photo", "this sift"),

    /** The sift's own stored files are damaged or missing. */
    RECORDS("This sift's records could not be read", "it"),

    /** More than one of the above, so no single clause is true of all of them. */
    MIXED("There were problems with the sift", "it");

    private final String clause;
    private final String theSift;

    FindingFamily(final String clause, final String theSift) {
        this.clause = clause;
        this.theSift = theSift;
    }

    /**
     * Which family a finding belongs to.
     *
     * <p>Exhaustive over the sealed type on purpose. A finding added later fails to compile here
     * until somebody says which of these a reader should be told about.
     *
     * @param finding {@link Finding} the fault
     * @return {@link FindingFamily} its family, never MIXED
     */
    static FindingFamily of(final Finding finding) {
        return switch (finding) {
            case Finding.PhotosNotJudged _ -> COVERAGE;
            case Finding.MissingMontageField _, Finding.MontageFieldMismatch _,
                 Finding.InvalidCategory _, Finding.MissingReason _, Finding.MissingGroup _,
                 Finding.MissingChosenReason _, Finding.WrongChosenCount _, Finding.TooFewRejects _,
                 Finding.InvalidGroupSlug _, Finding.DuplicateFileReference _,
                 Finding.DecisionUnreviewableOverlap _, Finding.GroupSpansMultipleMontages _,
                 Finding.PhotoFromAnotherSheet _ ->
                    DECISIONS;
            case Finding.MissingFile _, Finding.FileOutOfScope _, Finding.SourceOutsideSorted _,
                 Finding.MissingSource _ -> PHOTOS;
            case Finding.StrayShard _, Finding.MissingShard _, Finding.CorruptShard _,
                 Finding.CorruptIndex _, Finding.UnreadablePrepDir _, Finding.CorruptSidecar _ ->
                    RECORDS;
        };
    }

    /**
     * The one family a whole list belongs to, or MIXED where it spans several.
     *
     * @param findings a {@link List} of {@link Finding} what the diagnosis reported
     * @return {@link FindingFamily} the family, MIXED for an empty list as well as a spanning one
     */
    static FindingFamily of(final List<Finding> findings) {
        return findings.stream().map(FindingFamily::of).distinct().reduce((_, _) -> MIXED)
                .orElse(MIXED);
    }

    /**
     * What went wrong, in the terms a reader could act on.
     *
     * <p>Uncounted on purpose. How many faults a diagnosis found changes nothing a reader can do
     * about them.
     *
     * @param findings a {@link List} of {@link Finding} what the diagnosis reported
     * @return {@link String} a clause such as "There were structural problems with the decisions"
     */
    static String wentWrong(final List<Finding> findings) {
        return of(findings).clause;
    }

    /**
     * The whole sentence, clause and consequence together.
     *
     * <p>Composed here because what the second half calls the sift depends on the first half. Two
     * of the clauses name it themselves, and the rest do not.
     *
     * @param findings a {@link List} of {@link Finding} what the diagnosis reported
     * @return {@link String} the sentence a blocked card carries under its headline
     */
    static String nothingMoved(final List<Finding> findings) {
        final FindingFamily family = of(findings);
        return family.clause + ", so none of the photos in " + family.theSift + " were moved.";
    }
}
