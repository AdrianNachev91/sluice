package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LaunchPromptTest {

    @Test
    void theInstructionsNameTheFolderTheSheetsAreIn() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains(Path.of("logs", "sift-prep", "2019").toString());
    }

    @Test
    void everyCategoryTheRunWasPreppedWithIsExplained() {
        final String prompt = LaunchPrompt.forRun(prep(junk(), scenery()));

        assertThat(prompt).contains("junk").contains("Screenshots, blurred shots and photos of paper")
                .contains("scenery").contains("A landscape with nobody in it");
    }

    @Test
    void aCategorysExamplesAreOfferedWhereItHasThem() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("For example: a receipt, a blurred shot");
    }

    @Test
    void aCategoryWithNoExamplesOffersNoneRatherThanAnEmptyLine() {
        final String prompt = LaunchPrompt.forRun(prep(scenery()));

        assertThat(prompt).doesNotContain("For example:");
    }

    @Test
    void theShapeOfADecisionFileIsSpelledOut() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("decisions-NNN.json").contains("montage-NNN.jpg")
                .contains("montage-NNN.json").contains("near-dup-chosen")
                .contains("near-dup-reject").contains("chosen_reason");
    }

    // The instructions and the validator are one contract described twice. Asserting the literal
    // strings only proves this file agrees with itself, so the example is put through the real
    // validator instead.
    @Test
    void theExampleTheInstructionsGiveIsOneTheValidatorAccepts() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));
        final Path photo = Path.of("D:", "Photos", "Sorted", "2019", "06", "IMG_1.jpg");

        final ValidationReport report = new ShardValidator().validate(
                List.of(new ShardValidator.ShardFile("montage-001", new DecisionShard("montage-001",
                        List.of(new Decision.NearDupChosen(photo, groupNameFrom(prompt), "sharpest"),
                                new Decision.NearDupReject(photo.resolveSibling("IMG_2.jpg"),
                                        groupNameFrom(prompt), "eyes closed"))),
                        List.of(photo, photo.resolveSibling("IMG_2.jpg")))),
                List.of(photo, photo.resolveSibling("IMG_2.jpg")), List.of("junk"), List.of());

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void theGroupNameRuleIsStatedRatherThanLeftToBeDiscoveredAtApply() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("lower case letters, digits and single hyphens")
                .contains("no longer than 24 characters");
    }

    @Test
    void aPhotoWorthKeepingIsSaidToNeedAnEntryOfItsOwn() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("Every photo on the sheet gets exactly one entry, keepers included")
                .contains("\"action\": \"keep\"");
    }

    @Test
    void theRunsOwnSizeIsStatedRatherThanLeftToBeCounted() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("There are 2 sheets there, holding 50 photos.");
    }

    @Test
    void aRunOfOneSheetAndOnePhotoSaysSoInTheSingular() {
        final PrepDir smallest = new PrepDir("2019", List.of(junk()), Path.of("Sorted", "Photos"), 1,
                List.of(), 1, Path.of("logs", "sift-prep", "2019"), List.of("montage-001"));

        assertThat(LaunchPrompt.forRun(smallest)).contains("There is 1 sheet there, holding 1 photo.");
    }

    @Test
    void photosNoSheetCouldShowAreAccountedForSoTheCountsAgree() {
        final PrepDir withSkipped = new PrepDir("2019", List.of(junk()), Path.of("Sorted", "Photos"), 50,
                List.of(Path.of("Sorted", "Photos", "2019", "06", "broken.jpg")), 2,
                Path.of("logs", "sift-prep", "2019"), List.of("montage-001", "montage-002"));

        assertThat(LaunchPrompt.forRun(withSkipped))
                .contains("One more photo could not be turned into a tile and is on no sheet");
    }

    @Test
    void severalPhotosNoSheetCouldShowAreCountedRatherThanNamed() {
        final PrepDir withSkipped = new PrepDir("2019", List.of(junk()), Path.of("Sorted", "Photos"), 50,
                List.of(Path.of("a.jpg"), Path.of("b.jpg"), Path.of("c.jpg")), 2,
                Path.of("logs", "sift-prep", "2019"), List.of("montage-001", "montage-002"));

        assertThat(LaunchPrompt.forRun(withSkipped))
                .contains("Another 3 photos could not be turned into tiles and are on no sheet");
    }

    @Test
    void aRunNothingWasSkippedInSaysNothingAboutSkippedPhotos() {
        assertThat(LaunchPrompt.forRun(prep(junk()))).doesNotContain("on no sheet");
    }

    @Test
    void theGridIsNeverStated() {
        assertThat(LaunchPrompt.forRun(prep(junk()))).doesNotContain("rows of");
    }

    @Test
    void theRedoInstructionsNameWhatWasWrongAndRepeatTheWholeContract() {
        final String prompt = LaunchPrompt.forRedo(prep(junk()),
                List.of(new Finding.PhotosNotJudged("montage-002", List.of("IMG_9.jpg"))));

        assertThat(prompt).contains("montage-002: no verdict for 1 of its photos (IMG_9.jpg)")
                .contains("Every photo on the sheet gets exactly one entry, keepers included")
                .contains("junk");
    }

    @Test
    void theRedoInstructionsLeaveOutAProblemNoSheetCouldAnswerFor() {
        final String prompt = LaunchPrompt.forRedo(prep(junk()),
                List.of(new Finding.CorruptIndex(Path.of("logs", "sift-prep", "2019", "index.json"))));

        assertThat(prompt).doesNotContain("index.json");
    }

    @Test
    void onlySheetScopedProblemsNameASheetToWriteAgain() {
        assertThat(LaunchPrompt.sheetsToRedo(List.of(
                new Finding.PhotosNotJudged("montage-002", List.of("IMG_9.jpg")),
                new Finding.MissingReason("montage-002", 3),
                new Finding.CorruptIndex(Path.of("index.json")),
                new Finding.MissingSource(Path.of("a.jpg"), Path.of("moves.log")))))
                .containsExactly("montage-002");
    }

    @Test
    void everyProblemIsEitherAboutOneSheetOrAboutTheWholeRun() {
        assertThat(sheetScoped()).allSatisfy(finding ->
                assertThat(LaunchPrompt.sheetOf(finding)).isEqualTo("montage-003"));
        assertThat(runScoped()).allSatisfy(finding ->
                assertThat(LaunchPrompt.sheetOf(finding)).isNull());
        assertThat(sheetScoped().size() + runScoped().size())
                .isEqualTo(Finding.class.getPermittedSubclasses().length);
    }

    private static List<Finding> sheetScoped() {
        final Path file = Path.of("Sorted", "Photos", "2019", "06", "IMG_1.jpg");
        return List.of(
                new Finding.PhotosNotJudged("montage-003", List.of("IMG_1.jpg")),
                new Finding.PhotoFromAnotherSheet("montage-003", 7, file),
                new Finding.MissingMontageField("montage-003"),
                new Finding.MontageFieldMismatch("montage-003", "montage-004"),
                new Finding.InvalidCategory("montage-003", 1, "pets", "allowed: junk"),
                new Finding.MissingReason("montage-003", 2),
                new Finding.MissingGroup("montage-003", 3),
                new Finding.MissingChosenReason("montage-003", 4),
                new Finding.WrongChosenCount("montage-003", "beach", 2),
                new Finding.TooFewRejects("montage-003", "beach", 0),
                new Finding.InvalidGroupSlug("montage-003", "Beach Day", 24),
                new Finding.MissingFile("montage-003", 5),
                new Finding.FileOutOfScope("montage-003", 6, file),
                new Finding.CorruptShard("montage-003", "decisions-003.json"));
    }

    private static List<Finding> runScoped() {
        final Path file = Path.of("Sorted", "Photos", "2019", "06", "IMG_1.jpg");
        return List.of(
                new Finding.GroupSpansMultipleMontages("beach", List.of("montage-001", "montage-003")),
                new Finding.DuplicateFileReference(file.toString(), 2),
                new Finding.VerdictUnreviewableOverlap(new Decision.Classification(file, "junk", "blurry")),
                new Finding.SourceOutsideSorted(file, Path.of("Sorted")),
                new Finding.StrayShard("decisions-009.json"),
                new Finding.MissingShard("montage-003", "decisions-003.json"),
                new Finding.CorruptIndex(Path.of("index.json")),
                new Finding.UnreadablePrepDir(Path.of("logs", "sift-prep", "2019")),
                new Finding.CorruptSidecar("montage-003"),
                new Finding.MissingSource(file, Path.of("moves.log")));
    }

    private static String groupNameFrom(final String prompt) {
        final Matcher matcher = Pattern.compile("\"group\": \"([^\"]+)\"").matcher(prompt);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static PrepDir prep(final CullCategory... categories) {
        return new PrepDir("2019", List.of(categories), Path.of("Sorted", "Photos"), 50,
                List.of(), 2, Path.of("logs", "sift-prep", "2019"),
                List.of("montage-001", "montage-002"));
    }

    private static CullCategory junk() {
        return new CullCategory("junk", "Screenshots, blurred shots and photos of paper.",
                List.of("a receipt", "a blurred shot"), true);
    }

    private static CullCategory scenery() {
        return new CullCategory("scenery", "A landscape with nobody in it.", List.of(), true);
    }
}
