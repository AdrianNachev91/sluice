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
                                        groupNameFrom(prompt), "eyes closed"))))),
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
    void aPhotoWorthKeepingIsSaidToNeedNoEntry() {
        final String prompt = LaunchPrompt.forRun(prep(junk()));

        assertThat(prompt).contains("A photo worth keeping is not listed at all");
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
