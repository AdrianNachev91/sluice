package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretHolding.Holding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.nio.file.Path;
import java.util.List;
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
    void aSecondJobIsRefusedWithTheSentenceTheCallerAlreadyWrote() {
        final Refusal refusal = this.classifier.refusalFor(
                new JobInProgressException("A sort is running: wait for it and start again."));

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(RefusalKind.JOB_IN_PROGRESS);
        assertThat(refusal.sentence()).isEqualTo("A sort is running: wait for it and start again.");
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
