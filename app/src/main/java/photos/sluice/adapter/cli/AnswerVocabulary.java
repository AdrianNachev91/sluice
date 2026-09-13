package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.sift.ChoiceAnswer;
import photos.sluice.domain.sift.CorruptSidecarResolution;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.OverlapResolution;

import java.nio.file.Path;
import java.util.List;

/**
 * How an open finding is addressed and answered from this surface. The key a caller names it by,
 * the option ids it accepts, and what each one resolves to.
 */
final class AnswerVocabulary {

    /**
     * The option id that gives a run up entirely, for the one finding whose only answer is that.
     */
    static final String DISCARD_OPTION = "DISCARD";

    private static final String SKIP_OPTION = "SKIP";
    private static final String SET_ASIDE_OPTION = "SET_ASIDE";

    /**
     * Looks at the file again rather than recording anything. A photo reported missing may have
     * been put back since the pass ran, and the desktop offers the same answer under Look again.
     */
    static final String RECHECK_OPTION = "RECHECK";

    private AnswerVocabulary() {
    }

    /**
     * The key an open finding is addressed by, or null when it carries no answer at all.
     *
     * @param finding {@link Finding} the open finding
     * @return {@link String} the key, or null when the finding is informational only
     */
    static @Nullable String keyFor(final Finding finding) {
        return answerability(finding).key();
    }

    /**
     * The option ids the key answers to.
     *
     * @param finding {@link Finding} the open finding
     * @return a {@link List} of {@link String} the option ids, empty when the finding carries no answer
     */
    static List<String> optionsFor(final Finding finding) {
        return answerability(finding).options();
    }

    /**
     * The answer one key and option resolve to, against the findings currently open on a run.
     *
     * @param prepDir {@link Path} the run, carried only so a discard answer can name it
     * @param key {@link String} the key the caller named
     * @param option {@link String} the option the caller chose
     * @param open a {@link List} of {@link Finding} every finding currently open on the run
     * @return {@link Answer} what to do about it
     */
    static Answer resolve(final Path prepDir, final String key, final String option, final List<Finding> open) {
        for (final Finding finding : open) {
            if (optionsFor(finding).contains(option) && key.equals(keyFor(finding))) {
                return answerFor(prepDir, finding, option);
            }
        }
        return new Answer.NoMatch();
    }

    /**
     * The key and options an open finding carries, in one switch so both stay in one place.
     *
     * @param finding {@link Finding} the open finding
     * @return {@link Answerability} its key and options, both empty when it carries no answer
     */
    private static Answerability answerability(final Finding finding) {
        return switch (finding) {
            case final Finding.VerdictUnreviewableOverlap f -> new Answerability(text(f.verdict().file()),
                    List.of(OverlapResolution.TRUST_DECISION.name(), OverlapResolution.TREAT_AS_UNREVIEWABLE.name()));
            case final Finding.CorruptSidecar f -> new Answerability(f.montage(),
                    List.of(CorruptSidecarResolution.SET_ASIDE.name(), CorruptSidecarResolution.APPLY_ANYWAY.name()));
            case final Finding.MissingSource f -> new Answerability(text(f.file()),
                    List.of(RECHECK_OPTION, SKIP_OPTION));
            case final Finding.StrayShard f -> new Answerability(f.shardFile(), List.of(SET_ASIDE_OPTION));
            case final Finding.CorruptIndex f -> new Answerability(text(f.indexPath()), List.of(DISCARD_OPTION));
            // Listed rather than a default arm, so a new Finding variant forces a choice here
            // instead of compiling silently unanswerable.
            case Finding.MissingMontageField _, Finding.MontageFieldMismatch _, Finding.InvalidCategory _,
                 Finding.MissingReason _, Finding.FillerReason _, Finding.MissingGroup _, Finding.MissingChosenReason _,
                 Finding.WrongChosenCount _, Finding.TooFewRejects _, Finding.InvalidGroupSlug _,
                 Finding.DuplicateFileReference _, Finding.GroupSpansMultipleMontages _, Finding.MissingFile _,
                 Finding.PhotosNotJudged _, Finding.PhotoFromAnotherSheet _, Finding.FileOutOfScope _,
                 Finding.SourceOutsideSorted _, Finding.MissingShard _, Finding.CorruptShard _,
                 Finding.UnreadablePrepDir _ -> new Answerability(null, List.of());
        };
    }

    /**
     * The answer one open finding gives back for a chosen option, once the key has already matched.
     *
     * @param prepDir {@link Path} the run, carried only so a discard answer can name it
     * @param finding {@link Finding} the finding the key named
     * @param option {@link String} the option the caller chose
     * @return {@link Answer} what to do about it
     */
    private static Answer answerFor(final Path prepDir, final Finding finding, final String option) {
        return switch (finding) {
            case final Finding.VerdictUnreviewableOverlap f -> new Answer.Choice(
                    new ChoiceAnswer.ResolveOverlap(f.verdict().file(), OverlapResolution.valueOf(option)));
            case final Finding.CorruptSidecar f -> new Answer.Choice(
                    new ChoiceAnswer.ResolveCorruptSidecar(f.montage(), CorruptSidecarResolution.valueOf(option)));
            case final Finding.MissingSource f -> RECHECK_OPTION.equals(option)
                    ? new Answer.Recheck(f.file())
                    : new Answer.Choice(new ChoiceAnswer.SkipMissingSource(f.file()));
            case final Finding.StrayShard f -> new Answer.Choice(new ChoiceAnswer.SetAsideStrayShard(f));
            case final Finding.CorruptIndex ignored -> new Answer.Discard(prepDir);
            default -> new Answer.NoMatch();
        };
    }

    /**
     * Writes a path the way this machine spells it.
     *
     * @param path {@link Path} the path to write
     * @return {@link String} the path as text
     */
    private static String text(final Path path) {
        return path.toString();
    }

    /**
     * The key and options one finding carries.
     *
     * @param key {@link String} the key, or null when the finding carries no answer
     * @param options a {@link List} of {@link String} the option ids, empty when the finding
     *        carries no answer
     */
    private record Answerability(@Nullable String key, List<String> options) {
    }

    /**
     * What resolving a key and option against a run's open findings comes to.
     */
    sealed interface Answer {

        /**
         * The key and option name an answerable finding. Carries the answer to give the facade.
         *
         * @param answer {@link ChoiceAnswer} what to tell {@code Pipeline.answer}
         */
        record Choice(ChoiceAnswer answer) implements Answer {
        }

        /**
         * The key and option name a finding whose only answer is giving the run up entirely.
         *
         * @param prepDir {@link Path} the run to discard
         */
        record Discard(Path prepDir) implements Answer {
        }

        /**
         * The key and option ask for the file to be looked at again, which records nothing.
         *
         * @param file {@link Path} the photo reported missing
         */
        record Recheck(Path file) implements Answer {
        }

        /**
         * The key and option name no open finding on the run.
         */
        record NoMatch() implements Answer {
        }
    }
}
