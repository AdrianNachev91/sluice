package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.sift.ChoiceAnswer;
import photos.sluice.domain.sift.CorruptSidecarResolution;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.OverlapResolution;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class AnswerVocabularyTest {

    @Test
    void aFindingWithNoAnswerCarriesNoKeyAndNoOptions() {
        final Finding informational = new Finding.MissingReason("montage-001", 3);

        assertThat(AnswerVocabulary.keyFor(informational)).isNull();
        assertThat(AnswerVocabulary.optionsFor(informational)).isEmpty();
    }

    @Test
    void anOverlapIsKeyedByItsDecisionsFileAndOffersBothResolutions() {
        final var decision = new Decision.Classification(Path.of("IMG_1.jpg"), "keeper", "sharp");
        final Finding overlap = new Finding.VerdictUnreviewableOverlap(decision);

        assertThat(AnswerVocabulary.keyFor(overlap)).isEqualTo("IMG_1.jpg");
        assertThat(AnswerVocabulary.optionsFor(overlap)).containsExactly("TRUST_DECISION", "TREAT_AS_UNREVIEWABLE");
    }

    @Test
    void aCorruptSidecarIsKeyedByItsMontageAndOffersBothResolutions() {
        final Finding sidecar = new Finding.CorruptSidecar("montage-001");

        assertThat(AnswerVocabulary.keyFor(sidecar)).isEqualTo("montage-001");
        assertThat(AnswerVocabulary.optionsFor(sidecar)).containsExactly("SET_ASIDE", "APPLY_ANYWAY");
    }

    @Test
    void aMissingSourceIsKeyedByItsFileAndOffersBothAnswers() {
        final Finding missing = new Finding.MissingSource(Path.of("gone.jpg"), Path.of("move-record.log"));

        assertThat(AnswerVocabulary.keyFor(missing)).isEqualTo("gone.jpg");
        assertThat(AnswerVocabulary.optionsFor(missing)).containsExactly("RECHECK", "SKIP");
    }

    @Test
    void aStrayShardIsKeyedByItsFilenameAndOffersOnlySetAside() {
        final Finding stray = new Finding.StrayShard("decisions-999.json");

        assertThat(AnswerVocabulary.keyFor(stray)).isEqualTo("decisions-999.json");
        assertThat(AnswerVocabulary.optionsFor(stray)).containsExactly("SET_ASIDE");
    }

    @Test
    void aCorruptIndexIsKeyedByItsPathAndOffersOnlyDiscard() {
        final Finding corrupt = new Finding.CorruptIndex(Path.of("index.json"));

        assertThat(AnswerVocabulary.keyFor(corrupt)).isEqualTo("index.json");
        assertThat(AnswerVocabulary.optionsFor(corrupt)).containsExactly(AnswerVocabulary.DISCARD_OPTION);
    }

    @Test
    void resolvingAMatchingKeyAndOptionBuildsTheChoice() {
        final Finding.StrayShard stray = new Finding.StrayShard("decisions-999.json");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "decisions-999.json", "SET_ASIDE",
                List.of(stray));

        assertThat(answer).asInstanceOf(type(AnswerVocabulary.Answer.Choice.class))
                .extracting(AnswerVocabulary.Answer.Choice::answer)
                .isEqualTo(new ChoiceAnswer.SetAsideStrayShard(stray));
    }

    @Test
    void resolvingAnOverlapBuildsItsResolveOverlapAnswer() {
        final Path file = Path.of("IMG_1.jpg");
        final var decision = new Decision.Classification(file, "keeper", "sharp");
        final Finding overlap = new Finding.VerdictUnreviewableOverlap(decision);
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "IMG_1.jpg", "TRUST_DECISION",
                List.of(overlap));

        assertThat(answer).asInstanceOf(type(AnswerVocabulary.Answer.Choice.class))
                .extracting(AnswerVocabulary.Answer.Choice::answer)
                .isEqualTo(new ChoiceAnswer.ResolveOverlap(file, OverlapResolution.TRUST_DECISION));
    }

    @Test
    void resolvingACorruptSidecarBuildsItsResolveCorruptSidecarAnswer() {
        final Finding sidecar = new Finding.CorruptSidecar("montage-001");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "montage-001", "APPLY_ANYWAY",
                List.of(sidecar));

        assertThat(answer).asInstanceOf(type(AnswerVocabulary.Answer.Choice.class))
                .extracting(AnswerVocabulary.Answer.Choice::answer)
                .isEqualTo(new ChoiceAnswer.ResolveCorruptSidecar("montage-001", CorruptSidecarResolution.APPLY_ANYWAY));
    }

    @Test
    void resolvingACorruptIndexWithDiscardBuildsADiscardAnswerNamingTheRun() {
        final Finding corrupt = new Finding.CorruptIndex(Path.of("index.json"));
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "index.json",
                AnswerVocabulary.DISCARD_OPTION, List.of(corrupt));

        assertThat(answer).isEqualTo(new AnswerVocabulary.Answer.Discard(prepDir));
    }

    @Test
    void resolvingAMissingSourceBuildsItsSkipAnswer() {
        final Path missing = Path.of("gone.jpg");
        final Finding source = new Finding.MissingSource(missing, Path.of("move-record.log"));
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "gone.jpg", "SKIP",
                List.of(source));

        assertThat(answer).asInstanceOf(type(AnswerVocabulary.Answer.Choice.class))
                .extracting(AnswerVocabulary.Answer.Choice::answer)
                .isEqualTo(new ChoiceAnswer.SkipMissingSource(missing));
    }

    // RECHECK records nothing, so it resolves to its own answer rather than to a ChoiceAnswer the
    // ledger would take. A resolution that came back as a Choice would write the skip away.
    @Test
    void resolvingAMissingSourceWithRecheckAsksForAnotherLook() {
        final Path missing = Path.of("gone.jpg");
        final Finding source = new Finding.MissingSource(missing, Path.of("move-record.log"));
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "gone.jpg", "RECHECK",
                List.of(source));

        assertThat(answer).isEqualTo(new AnswerVocabulary.Answer.Recheck(missing));
    }

    @Test
    void resolveFindsAMatchAmongSeveralOpenFindings() {
        final Finding.CorruptSidecar sidecar = new Finding.CorruptSidecar("montage-002");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "montage-002", "SET_ASIDE",
                List.of(new Finding.StrayShard("decisions-999.json"), sidecar));

        assertThat(answer).asInstanceOf(type(AnswerVocabulary.Answer.Choice.class))
                .extracting(AnswerVocabulary.Answer.Choice::answer)
                .isEqualTo(new ChoiceAnswer.ResolveCorruptSidecar("montage-002", CorruptSidecarResolution.SET_ASIDE));
    }

    @Test
    void discardDoesNotAnswerAFindingThatDoesNotOfferIt() {
        final Finding sidecar = new Finding.CorruptSidecar("montage-001");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "montage-001",
                AnswerVocabulary.DISCARD_OPTION, List.of(sidecar));

        assertThat(answer).isEqualTo(new AnswerVocabulary.Answer.NoMatch());
    }

    @Test
    void aKeyNotAmongTheOpenFindingsIsNoMatch() {
        final Finding stray = new Finding.StrayShard("decisions-999.json");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "decisions-001.json", "SET_ASIDE",
                List.of(stray));

        assertThat(answer).isEqualTo(new AnswerVocabulary.Answer.NoMatch());
    }

    @Test
    void anOptionTheFindingDoesNotOfferIsNoMatch() {
        final Finding stray = new Finding.StrayShard("decisions-999.json");
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final AnswerVocabulary.Answer answer = AnswerVocabulary.resolve(prepDir, "decisions-999.json",
                "APPLY_ANYWAY", List.of(stray));

        assertThat(answer).isEqualTo(new AnswerVocabulary.Answer.NoMatch());
    }
}
