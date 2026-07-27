package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.Finding.DecisionUnreviewableOverlap;
import photos.sluice.domain.cull.Finding.DuplicateFileReference;
import photos.sluice.domain.cull.Finding.FileOutOfScope;
import photos.sluice.domain.cull.Finding.GroupSpansMultipleMontages;
import photos.sluice.domain.cull.Finding.InvalidCategory;
import photos.sluice.domain.cull.Finding.InvalidGroupSlug;
import photos.sluice.domain.cull.Finding.MissingChosenReason;
import photos.sluice.domain.cull.Finding.MissingFile;
import photos.sluice.domain.cull.Finding.MissingGroup;
import photos.sluice.domain.cull.Finding.MissingMontageField;
import photos.sluice.domain.cull.Finding.MissingReason;
import photos.sluice.domain.cull.Finding.MontageFieldMismatch;
import photos.sluice.domain.cull.Finding.TooFewRejects;
import photos.sluice.domain.cull.Finding.WrongChosenCount;
import photos.sluice.domain.cull.ShardValidator.ShardFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShardValidatorTest {

    private static final List<String> CATEGORIES = List.of("junk", "scenery", "food", "funny");

    private static final Path A = Path.of("Sorted/Photos/2016/08/a.jpg");
    private static final Path B = Path.of("Sorted/Photos/2016/08/b.jpg");
    private static final Path C = Path.of("Sorted/Photos/2016/08/c.jpg");
    private static final Path NO_FILE = Path.of("");
    private static final List<Path> SCOPE = List.of(A, B, C);

    @Test
    void aWellFormedShardIsValidAndMergesItsDecisions() {
        var report = validate(shardFile("montage-001",
                new Classification(A, "junk", "photo of a screen"),
                new Classification(B, "food", "restaurant meal")));

        assertThat(report.valid()).isTrue();
        assertThat(report.findings()).isEmpty();
        assertThat(report.heals()).isEmpty();
        assertThat(report.decisions()).hasSize(2);
    }

    @Test
    void anEmptyDecisionsShardIsValid() {
        var report = validate(shardFile("montage-001"));

        assertThat(report.valid()).isTrue();
        assertThat(report.decisions()).isEmpty();
    }

    @Test
    void aCompleteNearDupGroupIsValid() {
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, "g1", "sharpest of the three"),
                new NearDupReject(B, "g1", "slightly blurred"),
                new NearDupReject(C, "g1", "eyes closed")));

        assertThat(report.valid()).isTrue();
        assertThat(report.decisions()).hasSize(3);
    }

    @Test
    void anUnconfiguredCategoryIsReported() {
        var report = validate(shardFile("montage-001",
                new Classification(A, "meme", "funny caption")));

        assertThat(report.findings()).contains(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
    }

    @Test
    void aBlankClassificationReasonIsReported() {
        var report = validate(shardFile("montage-001",
                new Classification(A, "junk", "")));

        assertThat(report.findings()).contains(new MissingReason("montage-001", 1));
    }

    @Test
    void aNearDupChosenMissingItsGroupAndReasonIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, "", "")));

        assertThat(report.findings()).contains(
                new MissingGroup("montage-001", 1),
                new MissingChosenReason("montage-001", 1));
    }

    @Test
    void aNearDupRejectMissingItsGroupAndReasonIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupReject(A, "", "")));

        assertThat(report.findings()).contains(
                new MissingGroup("montage-001", 1),
                new MissingReason("montage-001", 1));
    }

    @Test
    void aBlankFileIsReported() {
        var report = validate(shardFile("montage-001",
                missingFile()));

        assertThat(report.findings()).contains(new MissingFile("montage-001", 1));
    }

    @Test
    void aFileOutsideTheScopeIsReported() {
        var stray = Path.of("Sorted/Photos/2016/08/z.jpg");
        var report = validate(shardFile("montage-001",
                new Classification(stray, "junk", "screenshot")));

        assertThat(report.findings()).contains(new FileOutOfScope("montage-001", 1, stray));
    }

    @Test
    void aDriftedPathHealsToItsUniqueSidecarSource() {
        var drifted = Path.of("Sorted/Photos/2016/09/a.jpg"); // wrong month, unique basename a.jpg
        var report = validate(shardFile("montage-001",
                new Classification(drifted, "junk", "screenshot")));

        assertThat(report.valid()).isTrue();
        assertThat(report.heals()).contains("montage-001[#1]: '" + drifted + "' -> '" + A + "'");
        assertThat(report.decisions().getFirst().file()).isEqualTo(A);
    }

    @Test
    void anAmbiguousBasenameIsNotHealed() {
        var dupA = Path.of("Sorted/Photos/2016/08/dup.jpg");
        var dupB = Path.of("Sorted/Photos/2016/09/dup.jpg");
        var drifted = Path.of("Sorted/Photos/2016/10/dup.jpg");
        var report = validator().validate(
                List.of(shardFile("montage-001", new Classification(drifted, "junk", "screenshot"))),
                List.of(dupA, dupB),
                CATEGORIES,
                List.of());

        assertThat(report.heals()).isEmpty();
        assertThat(report.findings()).contains(new FileOutOfScope("montage-001", 1, drifted));
    }

    @Test
    void aNearDupGroupWithNoChosenIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupReject(A, "g1", "blurred")));

        assertThat(report.findings()).contains(new WrongChosenCount("montage-001", "g1", 0));
    }

    @Test
    void aNearDupGroupWithTwoChosenIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, "g1", "sharp"),
                new NearDupChosen(B, "g1", "also sharp"),
                new NearDupReject(C, "g1", "blurred")));

        assertThat(report.findings()).contains(new WrongChosenCount("montage-001", "g1", 2));
    }

    @Test
    void aNearDupGroupWithNoRejectIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, "g1", "sharp")));

        assertThat(report.findings()).contains(new TooFewRejects("montage-001", "g1", 0));
    }

    @Test
    void aGroupSlugWithUppercaseOrUnderscoresIsReported() {
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, "Beach_Day", "sharp"),
                new NearDupReject(B, "Beach_Day", "blurred")));

        assertThat(report.findings()).contains(new InvalidGroupSlug("montage-001", "Beach_Day", 24));
    }

    @Test
    void aGroupSlugLongerThanTwentyFourCharsIsReported() {
        var tooLong = "a-very-long-birthday-slug"; // 25 chars
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, tooLong, "sharp"),
                new NearDupReject(B, tooLong, "blurred")));

        assertThat(report.findings()).contains(new InvalidGroupSlug("montage-001", tooLong, 24));
    }

    @Test
    void aHyphenatedSlugAtTheLengthLimitIsValid() {
        var atLimit = "birthday-cake-candles-24"; // 24 chars exactly
        var report = validate(shardFile("montage-001",
                new NearDupChosen(A, atLimit, "sharp"),
                new NearDupReject(B, atLimit, "blurred")));

        assertThat(report.valid()).isTrue();
    }

    @Test
    void aMontageFieldNotMatchingTheFilenameIsReported() {
        var report = validate(new ShardFile("montage-001",
                new DecisionShard("montage-002", List.of(new Classification(A, "junk", "screenshot")))));

        assertThat(report.findings()).contains(new MontageFieldMismatch("montage-001", "montage-002"));
    }

    @Test
    void aBlankMontageFieldIsReported() {
        var report = validate(new ShardFile("montage-001",
                new DecisionShard("", List.of(new Classification(A, "junk", "screenshot")))));

        assertThat(report.findings()).contains(new MissingMontageField("montage-001"));
    }

    @Test
    void aFileActedOnByTwoShardsIsReported() {
        var report = validate(
                shardFile("montage-001", new Classification(A, "junk", "screenshot")),
                shardFile("montage-002", new Classification(A, "food", "meal")));

        assertThat(report.findings()).contains(new DuplicateFileReference(A.toString(), 2));
    }

    @Test
    void aNearDupGroupIdReusedAcrossTwoShardsIsReported() {
        var d = Path.of("Sorted/Photos/2016/08/d.jpg");
        var report = validator().validate(
                List.of(
                        shardFile("montage-001",
                                new NearDupChosen(A, "g1", "sharp"),
                                new NearDupReject(B, "g1", "blurred")),
                        shardFile("montage-002",
                                new NearDupChosen(C, "g1", "sharp"),
                                new NearDupReject(d, "g1", "blurred"))),
                List.of(A, B, C, d),
                CATEGORIES,
                List.of());

        assertThat(report.findings()).contains(
                new GroupSpansMultipleMontages("g1", List.of("montage-001", "montage-002")));
    }

    @Test
    void aHealThatCollidesWithAnAlreadyListedFileIsCaughtAsADuplicate() {
        var driftedA = Path.of("Sorted/Photos/2016/09/a.jpg"); // heals to A
        var report = validate(
                shardFile("montage-001", new Classification(A, "junk", "screenshot")),
                shardFile("montage-002", new Classification(driftedA, "food", "meal")));

        assertThat(report.heals()).hasSize(1);
        assertThat(report.findings()).contains(new DuplicateFileReference(A.toString(), 2));
    }

    @Test
    void aFileListedBothAsADecisionAndAsUnreviewableIsReportedAsAResolvableOverlap() {
        var decision = new Classification(A, "junk", "screenshot");
        var report = validator().validate(
                List.of(shardFile("montage-001", decision)),
                SCOPE,
                CATEGORIES,
                List.of(A));

        assertThat(report.findings()).containsExactly(new DecisionUnreviewableOverlap(decision));
    }

    // Distinguishes the resolvable one-decision-plus-one-unreviewable overlap above from every other
    // multi-reference shape, which has no such resolution and stays the general DuplicateFileReference.
    @Test
    void aFileListedTwiceAsADecisionAndOnceAsUnreviewableIsReportedAsAPlainDuplicate() {
        var report = validator().validate(
                List.of(
                        shardFile("montage-001", new Classification(A, "junk", "screenshot")),
                        shardFile("montage-002", new Classification(A, "food", "meal"))),
                SCOPE,
                CATEGORIES,
                List.of(A));

        assertThat(report.findings()).contains(new DuplicateFileReference(A.toString(), 3));
    }

    @Test
    void aFileListedTwiceWithinTheUnreviewableListIsReported() {
        var report = validator().validate(
                List.of(shardFile("montage-001")),
                SCOPE,
                CATEGORIES,
                List.of(A, A));

        assertThat(report.findings()).contains(new DuplicateFileReference(A.toString(), 2));
    }

    @Test
    void anEmptyCategorySetMakesEveryClassificationInvalid() {
        var report = validator().validate(
                List.of(shardFile("montage-001", new Classification(A, "junk", "screenshot"))),
                SCOPE,
                List.of(),
                List.of());

        assertThat(report.findings()).contains(
                new InvalidCategory("montage-001", 1, "junk", "no categories configured"));
    }

    // A decision with several faults reports all of them at once, not just the first - a bad category
    // does not mask the blank reason on the same decision. This is the whole point of aggregating.
    @Test
    void everyProblemIsReportedNotJustTheFirst() {
        var report = validate(shardFile("montage-001",
                new Classification(A, "meme", ""),       // unknown category + blank reason
                missingFile())); // blank file

        assertThat(report.findings()).contains(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"),
                new MissingReason("montage-001", 1),
                new MissingFile("montage-001", 2));
    }

    @Test
    void findingsAreOrderedByMontageRegardlessOfInputOrder() {
        var report = validate(
                shardFile("montage-002", missingFile()),
                shardFile("montage-001", missingFile()));

        assertThat(report.findings()).containsExactly(
                new MissingFile("montage-001", 1),
                new MissingFile("montage-002", 1));
    }

    // Proves describe() renders the exact prose an aggregated ApplyException reports, for a
    // representative sample rather than checking all thirteen finding shapes twice.
    @Test
    void describeRendersTheExactProseApplyExceptionReports() {
        assertThat(new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny").describe())
                .isEqualTo("montage-001[#1]: invalid action 'meme' (allowed: junk, scenery, food, funny)");
        assertThat(new MissingFile("montage-001", 2).describe())
                .isEqualTo("montage-001[#2]: missing 'file'");
        assertThat(new MontageFieldMismatch("montage-001", "montage-002").describe())
                .isEqualTo("montage-001: 'montage' is 'montage-002', expected 'montage-001'");
        assertThat(new GroupSpansMultipleMontages("g1", List.of("montage-001", "montage-002")).describe())
                .isEqualTo("near-dup group 'g1' spans 2 shards (montage-001, montage-002); a group must stay within one montage");
    }

    @Test
    void decisionUnreviewableOverlapDescribesTheFileAndCarriesTheChoiceRemedy() {
        var overlap = new DecisionUnreviewableOverlap(new Classification(A, "junk", "screenshot"));

        assertThat(overlap.describe()).isEqualTo("file listed both as a decision and as unreviewable: " + A);
        assertThat(overlap.remedy()).isEqualTo(Finding.Remedy.CHOICE);
    }

    private ShardValidator validator() {
        return new ShardValidator();
    }

    private Classification missingFile() {
        return new Classification(NO_FILE, "junk", "screenshot");
    }

    private ShardFile shardFile(String montage, Decision... decisions) {
        return new ShardFile(montage, new DecisionShard(montage, List.of(decisions)));
    }

    private ValidationReport validate(ShardFile... shards) {
        return validator().validate(List.of(shards), SCOPE, CATEGORIES, List.of());
    }
}
