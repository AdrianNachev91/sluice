package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Verdict;

import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class FindingWordsTest {

    private static final Path PHOTO = Path.of("D:", "Sorted", "Photos", "2019", "06", "a.jpg");

    // One of every variant of the sealed type. A variant added later fails to compile in
    // FindingWords itself, and this is what then holds its wording to the same bar as the rest.
    private static final List<Finding> EVERY_KIND = List.of(
            new Finding.MissingMontageField("montage-001"),
            new Finding.MontageFieldMismatch("montage-001", "montage-009"),
            new Finding.InvalidCategory("montage-001", 3, "sunsets", "allowed: keep, junk"),
            new Finding.MissingReason("montage-001", 3),
            new Finding.MissingGroup("montage-001", 3),
            new Finding.MissingChosenReason("montage-001", 3),
            new Finding.WrongChosenCount("montage-001", "beach-run", 2),
            new Finding.TooFewRejects("montage-001", "beach-run", 0),
            new Finding.InvalidGroupSlug("montage-001", "Beach Run", 40),
            new Finding.DuplicateFileReference(PHOTO.toString(), 3),
            new Finding.VerdictUnreviewableOverlap(
                    new Decision.Classification(PHOTO, "junk", "blurry")),
            new Finding.GroupSpansMultipleMontages("beach-run", List.of("montage-001", "montage-002")),
            new Finding.MissingFile("montage-001", 3),
            new Finding.PhotosNotJudged("montage-001", List.of("a.jpg", "b.jpg")),
            new Finding.PhotoFromAnotherSheet("montage-001", 3, PHOTO),
            new Finding.FileOutOfScope("montage-001", 3, PHOTO),
            new Finding.SourceOutsideSorted(PHOTO, Path.of("D:", "Sorted")),
            new Finding.StrayShard("decisions-009.json"),
            new Finding.MissingShard("montage-003", "decisions-003.json"),
            new Finding.CorruptShard("montage-002", "decisions-002.json"),
            new Finding.CorruptIndex(Path.of("logs", "sift-prep", "2019", "index.json")),
            new Finding.UnreadablePrepDir(Path.of("logs", "sift-prep", "2019")),
            new Finding.CorruptSidecar("montage-002"),
            new Finding.MissingSource(PHOTO, Path.of("move-records.log")));

    @Test
    void everyKindOfProblemHasSomethingToSayAboutItself() {
        assertThat(EVERY_KIND).allSatisfy(finding ->
                assertThat(FindingWords.of(finding).problem()).isNotBlank().endsWith("."));
    }

    // A sentence whose placeholder is misspelt loses the count silently rather than failing, and
    // the heading then claims a set of one.
    @Test
    void everyKindThatCanStackCountsItselfInOneSentence() {
        assertThat(EVERY_KIND).allSatisfy(finding -> {
            final String heading = FindingWords.of(finding).heading(1234);
            if (heading != null) {
                assertThat(heading).contains("1,234").doesNotContain("{}").endsWith(".");
            }
        });
    }

    // An arm that lost its plural would draw a row per finding again, so the ones that legitimately
    // have none are pinned by name.
    @Test
    void onlyTheTwoFaultsARunCanHoldOneOfHaveNoPluralAtAll() {
        assertThat(EVERY_KIND).filteredOn(finding -> FindingWords.of(finding).heading(3) == null)
                .hasSize(2)
                .allSatisfy(finding -> assertThat(finding)
                        .isInstanceOfAny(Finding.CorruptIndex.class, Finding.UnreadablePrepDir.class));
    }

    // The engine's own words for these are montage, shard and cull.
    @Test
    void noProblemSentenceUsesTheEnginesOwnVocabulary() {
        assertThat(EVERY_KIND).allSatisfy(finding -> {
            final FindingWords.Statement statement = FindingWords.of(finding);
            assertThat(statement.problem().toLowerCase())
                    .doesNotContain("montage").doesNotContain("shard").doesNotContain("cull");
            final String forSeveral = statement.forSeveral();
            if (forSeveral != null) {
                assertThat(forSeveral.toLowerCase())
                        .doesNotContain("montage").doesNotContain("shard").doesNotContain("cull");
            }
        });
    }

    @Test
    void everyKindOfProblemNamesWhatItHappenedTo() {
        assertThat(EVERY_KIND).allSatisfy(finding ->
                assertThat(FindingWords.of(finding).subject()).isNotBlank());
    }

    @Test
    void aSheetIsNamedByItsNumberRatherThanByItsIdOnDisk() {
        assertThat(FindingWords.of(new Finding.MissingShard("montage-004", "decisions-004.json")).subject())
                .isEqualTo("Sheet 4");
        assertThat(FindingWords.of(new Finding.MissingReason("montage-012", 3)).subject())
                .startsWith("Sheet 12,");
    }

    // Nothing produces an id with no number in it today, so the fixture is built rather than
    // observed.
    @Test
    void aSheetWhoseIdCarriesNoNumberIsNamedByThatIdInstead() {
        assertThat(FindingWords.of(new Finding.MissingShard("montage-extra", "decisions-extra.json")).subject())
                .isEqualTo("Sheet montage-extra");
    }

    // A filename is exempt from the sheet wording the way an on-screen path is: it is what a reader
    // would type to go and look.
    @Test
    void theDamagedRecordIsNamedSoAReaderCanGoAndLookAtIt() {
        assertThat(FindingWords.of(new Finding.CorruptSidecar("montage-002")).subject())
                .isEqualTo("Sheet 2, montage-002.json");
    }

    @Test
    void theUncheckedAnswersQuestionNamesBothChecksTheDamagedRecordCosts() {
        final FindingWords.Choice useAnyway =
                FindingWords.of(new Finding.CorruptSidecar("montage-002")).choices().getLast();

        assertThat(requireNonNull(useAnyway.confirm()).detail())
                .contains("about the photos this sheet contained")
                .contains("whether they cover all of them");
    }

    @Test
    void theFourProblemsAReaderCanAnswerAreTheOnesCarryingAnswers() {
        assertThat(EVERY_KIND.stream()
                .filter(finding -> !FindingWords.of(finding).choices().isEmpty())
                .toList())
                .containsExactlyInAnyOrder(
                        new Finding.VerdictUnreviewableOverlap(
                                new Decision.Classification(PHOTO, "junk", "blurry")),
                        new Finding.StrayShard("decisions-009.json"),
                        new Finding.CorruptSidecar("montage-002"),
                        new Finding.MissingSource(PHOTO, Path.of("move-records.log")));
    }

    @Test
    void theSafeAnswerLeadsAndOnlyTheRiskyOneIsAskedAbout() {
        final List<FindingWords.Choice> choices =
                FindingWords.of(new Finding.CorruptSidecar("montage-002")).choices();

        assertThat(choices).extracting(FindingWords.Choice::answer)
                .containsExactly(Answer.SET_ASIDE_SHEET, Answer.APPLY_SHEET_ANYWAY);
        assertThat(choices.getFirst().leading()).isTrue();
        assertThat(choices.getFirst().confirm()).isNull();
        assertThat(requireNonNull(choices.getLast().confirm()).goAheadLeads()).isFalse();
    }

    @Test
    void everyAnswerThatSettlesSomethingSaysWhatItSettled() {
        assertThat(List.of(Answer.SKIP_FILE, Answer.TRUST_DECISION, Answer.TREAT_AS_UNREVIEWABLE,
                Answer.SET_ASIDE_SHEET, Answer.APPLY_SHEET_ANYWAY, Answer.SET_ASIDE_STRAY_ANSWERS))
                .allSatisfy(answer -> assertThat(FindingWords.settled(answer)).isNotBlank());
    }

    @Test
    void lookingAgainSettlesNothing() {
        assertThat(FindingWords.settled(Answer.RECHECK)).isNull();
    }

    // Pinned rather than described. These labels follow four rules no assertion can check. Each
    // says what pressing it does. None is a statement about what the reader has already done.
    // None names the app's own filing. The three that leave something out of the sift say so the
    // same way. Listing phrasings to forbid catches only the ones somebody thought of, and the
    // next bad label uses different words. So the set is written down, and changing any of it
    // fails here until somebody has read this again.
    @Test
    void everyAnswerCarriesExactlyTheseLabels() {
        assertThat(EVERY_KIND.stream()
                .flatMap(finding -> FindingWords.of(finding).choices().stream())
                .map(FindingWords.Choice::label))
                .containsExactlyInAnyOrder(
                        "Look again",
                        "Go on without this photo",
                        "Move it where the sift said",
                        "Leave the photo unjudged",
                        "Go on without this sheet",
                        "Use this sheet's answers as they are",
                        "Go on without them");
    }

    // The same finding, and the press does opposite things to the photo. A shared label would have
    // a reader pressing for a move and getting the photo left alone.
    @Test
    void anOverlapOnAKeptPhotoSaysThePhotoStaysRatherThanMoves() {
        final Finding kept = new Finding.VerdictUnreviewableOverlap(new Verdict.Keep(PHOTO));

        assertThat(FindingWords.of(kept).choices())
                .extracting(FindingWords.Choice::label)
                .containsExactly("Keep it in Sorted", "Leave the photo unjudged");
    }

    @Test
    void leavingASheetOutSaysItsPhotosSurvive() {
        assertThat(requireNonNull(FindingWords.settled(Answer.SET_ASIDE_SHEET)))
                .contains("Its photos stay in Sorted");
    }
}
