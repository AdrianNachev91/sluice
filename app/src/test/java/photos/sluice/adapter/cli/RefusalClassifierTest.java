package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretHolding.Holding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.UnrecognisedProviderException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.SequencedMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class RefusalClassifierTest {

    private static final SecretId KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final Path WORKING_ROOT = Path.of("D:", "Photos");

    private final RefusalClassifier classifier = new RefusalClassifier(new NoSecrets(List.of(
            new SecretHolding(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"), Holding.EMPTY),
            new SecretHolding(new SecretStatus.InKeyring(), Holding.COULD_NOT_BE_ASKED))));

    @Test
    void aFailureThisAppHasNoReadingForIsNotARefusal() {
        assertThat(this.classifier.refusalFor(new IllegalArgumentException("something else"))).isNull();
    }

    @Test
    void unusableFolderRootsAreRefusedWithEveryViolationAsAValue() {
        final Refusal refusal = this.classifier.refusalFor(new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.LIBRARY_ROOT),
                        new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX))));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.FOLDERS_UNUSABLE);
        assertThat(refusal.detail()).containsOnlyKeys("violations");
        assertThat((List<?>) refusal.detail().get("violations")).hasSize(2);
    }

    @Test
    void unusableFolderRootsOpenInThisSurfacesOwnWordsAndThenNameEverySettingAtFault() {
        final Refusal refusal = this.classifier.refusalFor(new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.WORKING_ROOT),
                        new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX))));

        assertThat(refusal).isNotNull();
        assertThat(refusal.sentence()).isEqualTo("Unusable folder settings. sluice.paths.repo-root is "
                + "not set. sluice.paths.library-root and sluice.paths.inbox must not contain each other.");
    }

    @Test
    void everyViolationNamesTheSettingToEditAsWellAsTheRoleItPlays() {
        final Refusal refusal = this.classifier.refusalFor(new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.LIBRARY_ROOT))));

        assertThat(refusal).isNotNull();
        assertThat(violations(refusal).getFirst())
                .containsEntry("role", PathRole.LIBRARY_ROOT)
                .containsEntry("property", "sluice.paths.library-root");
    }

    @Test
    void anOverlapNamesTheSettingForBothRootsItInvolves() {
        final Refusal refusal = this.classifier.refusalFor(new PathsMisconfiguredException(
                List.of(new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX))));

        assertThat(refusal).isNotNull();
        assertThat(violations(refusal).getFirst())
                .containsEntry("firstProperty", "sluice.paths.repo-root")
                .containsEntry("secondProperty", "sluice.paths.inbox");
    }

    // The property a reader has to edit is spelled differently from the role it plays, and the two
    // are free to be renamed apart. A test that read one from the other would pass either way.
    @Test
    void theSettingNameIsNotDerivableFromTheRoleName() {
        assertThat(PathsMisconfiguredException.property(PathRole.WORKING_ROOT))
                .isEqualTo("sluice.paths.repo-root")
                .doesNotContain("working");
    }

    @SuppressWarnings("unchecked")
    private static List<SequencedMap<String, Object>> violations(final Refusal refusal) {
        return (List<SequencedMap<String, Object>>) refusal.detail().get("violations");
    }

    @Test
    void aBusyWorkingRootIsRefusedNamingTheFolderTheOtherProcessHolds() {
        final Refusal refusal = this.classifier.refusalFor(
                new WorkingRootBusyException(WORKING_ROOT));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.WORKING_ROOT_BUSY);
        assertThat(refusal.detail()).containsExactly(entry("workingRoot", WORKING_ROOT.toString()));
    }

    @Test
    void aSecondJobIsRefusedInThisSurfacesOwnWordsRatherThanTheCallersMessage() {
        final Refusal refusal = this.classifier.refusalFor(
                new JobInProgressException("Sluice is running a job. Finish it before changing "
                        + "where its folders are."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.JOB_IN_PROGRESS);
        assertThat(refusal.sentence())
                .isEqualTo("Something else is running. Wait for it to finish, then start this one.");
    }

    @Test
    void aMissingCredentialLeadsWithTheEnvironmentVariable() {
        final Refusal refusal = this.classifier.refusalFor(
                new MissingCredentialException(KEY, "No API key for anthropic."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.sentence()).contains("ANTHROPIC_API_KEY");
        assertThat(refusal.detail()).containsEntry("provider", "anthropic")
                .containsEntry("environmentVariable", "ANTHROPIC_API_KEY");
    }

    @Test
    void aMissingCredentialReportsWhatEachPlaceAnswered() {
        final Refusal refusal = this.classifier.refusalFor(
                new MissingCredentialException(KEY, "No API key for anthropic."));

        assertThat(refusal).isNotNull();
        assertThat((List<?>) refusal.detail().get("places")).hasSize(2);
        assertThat(refusal.detail().get("places").toString())
                .contains("InEnvironment").contains("EMPTY")
                .contains("InKeyring").contains("COULD_NOT_BE_ASKED");
    }

    @Test
    void aCredentialStoreThatCannotAnswerIsRefusedNamingWhichPlaceFailed() {
        final Refusal refusal = this.classifier.refusalFor(
                new SecretStoreException(SecretStoreException.Tier.KEYRING, "the keyring refused"));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.CREDENTIAL_STORE_FAILED);
        assertThat(refusal.detail()).containsExactly(entry("tier", SecretStoreException.Tier.KEYRING));
    }

    @Test
    void aRefusalWrappedByTheWaitingMachineryIsStillThatRefusal() {
        final Refusal refusal = this.classifier.refusalFor(new CompletionException(
                new WorkingRootBusyException(WORKING_ROOT)));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.WORKING_ROOT_BUSY);
    }

    @Test
    void aRefusalWrappedTwiceOverIsStillThatRefusal() {
        final Refusal refusal = this.classifier.refusalFor(new CompletionException(
                new CompletionException(new JobInProgressException("A sort is running."))));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.JOB_IN_PROGRESS);
    }

    @Test
    void aRefusalWrappedByTheOtherWaitingMachineryIsAlsoStillThatRefusal() {
        final Refusal refusal = this.classifier.refusalFor(
                new ExecutionException(new WorkingRootBusyException(WORKING_ROOT)));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.WORKING_ROOT_BUSY);
    }

    @Test
    void aWrapperWithNothingInsideItIsNotARefusal() {
        assertThat(this.classifier.refusalFor(new CompletionException("empty", null))).isNull();
    }

    @Test
    void anOccupiedScopeNamesTheRunAlreadyThereWithoutItsInternalState() {
        final CullRunSummary occupant = new CullRunSummary("2019", Path.of("logs", "sift-prep", "2019"),
                new PrepDirHealth(PrepDirHealth.State.WAITING, List.of()), new ShardTally(1, 0, 2),
                Instant.parse("2026-08-20T10:15:30Z"));

        final Refusal refusal = this.classifier.refusalFor(new Pipeline.ScopeOccupiedException(occupant));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_OCCUPIED);
        assertThat(refusal.sentence()).contains("2019").doesNotContain("WAITING");
        assertThat(refusal.detail()).containsOnlyKeys("occupant");
    }

    @Test
    void overlappingScopesNameTheChosenTimeframeAndEveryRunInTheWay() {
        final CullRunSummary blocking = new CullRunSummary("2019-06", Path.of("logs", "sift-prep", "2019-06"),
                new PrepDirHealth(PrepDirHealth.State.READY, List.of()), new ShardTally(1, 1, 1),
                Instant.parse("2026-08-20T10:15:30Z"));

        final Refusal refusal = this.classifier.refusalFor(
                new Pipeline.ScopeOverlapsException(new CullScope.Year(2019, null), List.of(blocking)));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_OVERLAPS);
        assertThat(refusal.detail()).containsEntry("chosen", "2019");
        assertThat((List<?>) refusal.detail().get("overlapping")).hasSize(1);
    }

    @Test
    void anUnreadablePrepDirNamesTheFolderThatCouldNotBeRead() {
        final Path prepDir = Path.of("logs", "sift-prep", "2019");

        final Refusal refusal = this.classifier.refusalFor(
                new Pipeline.ScopeUnreadableException(prepDir, new UncheckedIOException(
                        new IOException("access denied"))));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.SCOPE_UNREADABLE);
        assertThat(refusal.detail()).containsEntry("prepDir", prepDir.toString());
    }

    @Test
    void aRunOutsideTheWorkingRootNamesItsFolder() {
        final Path prepDir = Path.of("D:", "Elsewhere", "2019");

        final Refusal refusal = this.classifier.refusalFor(new Pipeline.RunOutsideWorkingRootException(prepDir));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.RUN_OUTSIDE_WORKING_ROOT);
        assertThat(refusal.detail()).containsEntry("prepDir", prepDir.toString());
    }

    @Test
    void aDiscardOnAFinishedRunCarriesTheFacadesOwnSentence() {
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final Refusal refusal = this.classifier.refusalFor(new Pipeline.RunAlreadyFinishedException(prepDir));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.RUN_ALREADY_FINISHED);
        assertThat(refusal.sentence()).contains(prepDir.toString());
    }

    @Test
    void aRedoWithNothingToJudgeAgainCarriesTheFacadesOwnSentence() {
        final Path prepDir = Path.of("D:", "Sift", "2019");

        final Refusal refusal = this.classifier.refusalFor(new Pipeline.NothingToRedoException(prepDir));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.NOTHING_TO_REDO);
        assertThat(refusal.sentence()).contains(prepDir.toString());
    }

    @Test
    void aDestructiveCommandWithoutYesCarriesWhatWouldBeLost() {
        final Refusal refusal = this.classifier.refusalFor(
                new ConfirmationRequiredException("2 sheet decisions are set aside with it."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.CONFIRMATION_REQUIRED);
        assertThat(refusal.sentence()).isEqualTo("2 sheet decisions are set aside with it.");
    }

    @Test
    void aKeyAndOptionAnsweringNothingOpenCarriesWhatWasNamed() {
        final Refusal refusal = this.classifier.refusalFor(
                new AnswerNotApplicableException("Nothing open on this sift answers to montage-001 with SET_ASIDE."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.ANSWER_NOT_APPLICABLE);
        assertThat(refusal.sentence()).contains("montage-001", "SET_ASIDE");
    }

    @Test
    void anImportSourceRefusalCarriesTheSentenceWritingForThePersonWhoChoseIt() {
        final Refusal refusal = this.classifier.refusalFor(
                new ImportSourceException("Sluice can't import from that: it no longer exists."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.IMPORT_SOURCE_REFUSED);
        assertThat(refusal.sentence()).isEqualTo("Sluice can't import from that: it no longer exists.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnrecognisedProviderNamesWhatIsConfiguredAndWhatIsRegistered() {
        final Refusal refusal = this.classifier.refusalFor(
                new UnrecognisedProviderException("ollama", Set.of("anthropic", "external-agent")));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.PROVIDER_UNRECOGNISED);
        assertThat(refusal.sentence()).contains("ollama");
        assertThat(refusal.detail()).containsEntry("provider", "ollama");
        assertThat((List<String>) refusal.detail().get("registered")).containsExactlyInAnyOrder("anthropic",
                "external-agent");
    }

    @Test
    void aFolderThatIsNotThereIsRefusedRatherThanLeakingTheReadFailure() {
        final Path missing = Path.of("D:", "Photos", "Review", "DoesNotExist");

        final Refusal refusal = this.classifier.refusalFor(
                new UncheckedIOException(new NoSuchFileException(missing.toString())));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.FOLDER_NOT_FOUND);
        assertThat(refusal.sentence()).contains(missing.toString());
        assertThat(refusal.detail()).containsEntry("path", missing.toString());
    }

    @Test
    void anUncheckedIoFailureWithADifferentCauseIsAFileNothingCouldReach() {
        final Refusal refusal = this.classifier.refusalFor(
                new UncheckedIOException(new IOException("disk is unplugged")));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.FILE_UNREACHABLE);
        assertThat(refusal.detail()).containsEntry("problem", "java.io.IOException: disk is unplugged");
    }

    @Test
    void aRunWhoseOwnRecordsCannotBeReadIsNamedAsRecordsRatherThanAsAFolder() {
        final Refusal refusal = this.classifier.refusalFor(new MalformedPrepJsonException(
                "index.json is not readable JSON", new NoSuchFileException("index.json")));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.RUN_RECORDS_UNREADABLE);
        assertThat(refusal.sentence()).doesNotContain("folder name");
    }

    @Test
    void aChainThatHoldsItselfStillAnswers() {
        final var looping = new CompletionException("loops", null) {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(this.classifier.refusalFor(looping)).isNull();
    }

}
