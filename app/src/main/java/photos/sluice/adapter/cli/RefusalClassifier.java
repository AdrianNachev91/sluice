package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.domain.paths.PathViolation;

import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Says whether a failure is something the app refused, and if so which refusal it was.
 *
 * <p>Which refusal it is, is decided by the exception's type alone. Matching on a message would
 * break silently the first time somebody improved a sentence. Every refusal this recognises
 * carries a type of its own for that reason.
 *
 * <p>Some branches do go on to use the message, once the type has settled what the refusal is.
 * That is the sentence being carried through rather than read. Those exceptions word themselves
 * for a person to see, and rewriting them here would leave two versions to keep in step.
 *
 * <p>One arm classifies nothing. A scope argument a verb refused already carries its own refusal,
 * worked out where the rule that refused it lives. It comes through here so that a caller meets it
 * on the same two streams, in the same document, as every refusal the app raises further down.
 *
 * <p>Anything it does not recognise gets no answer here. That is what
 * {@link CommandStatus#FAILED} is for.
 */
@Component
@Profile("cli")
public class RefusalClassifier {

    /**
     * Bounds the unwrapping, so a cause chain that holds itself still terminates.
     */
    private static final int MAX_WRAPPERS = 100;

    private final SecretStore secrets;

    /**
     * Creates the classifier.
     *
     * @param secrets {@link SecretStore} asked which places hold a credential, when one is missing
     */
    public RefusalClassifier(final SecretStore secrets) {
        this.secrets = secrets;
    }

    /**
     * The refusal this failure is, or null when the app never classified it.
     *
     * @param failure {@link Throwable} what the command raised
     * @return {@link Refusal} the refusal, or null when nothing here recognises it
     */
    public @Nullable Refusal refusalFor(final Throwable failure) {
        return this.branchFor(unwrapped(failure));
    }

    /**
     * The failure a job actually met, from inside whatever the waiting machinery wrapped it in.
     *
     * <p>Waiting on a job hands back the failure wrapped, so a typed refusal arrives one layer
     * down.
     *
     * @param failure {@link Throwable} what the command raised
     * @return {@link Throwable} the failure underneath the wrappers
     */
    static Throwable unwrapped(final Throwable failure) {
        Throwable current = failure;
        int remaining = MAX_WRAPPERS;
        while (remaining > 0 && current.getCause() != null
                && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
            remaining--;
        }
        return current;
    }

    /**
     * The refusal one exception names, or null when it names none.
     *
     * @param failure {@link Throwable} the unwrapped failure
     * @return {@link Refusal} the refusal, or null
     */
    private @Nullable Refusal branchFor(final Throwable failure) {
        return switch (failure) {
            case final ScopeRefusedException scope -> scope.refusal();
            case final PathsMisconfiguredException paths -> foldersUnusable(paths);
            case final WorkingRootBusyException busy -> workingRootBusy(busy);
            case final JobInProgressException busy -> Refusal.of(RefusalKind.JOB_IN_PROGRESS, busy.getMessage());
            case final MissingCredentialException missing -> this.credentialMissing(missing);
            case final SecretStoreException broken -> credentialStoreFailed(broken);
            default -> null;
        };
    }

    /**
     * The refusal for folder roots the app cannot work in.
     *
     * @param paths {@link PathsMisconfiguredException} what the facade refused on
     * @return {@link Refusal} the refusal
     */
    private static Refusal foldersUnusable(final PathsMisconfiguredException paths) {
        return new Refusal(RefusalKind.FOLDERS_UNUSABLE, paths.getMessage(),
                Fields.of("violations", paths.violations().stream().map(RefusalClassifier::violation).toList()));
    }

    /**
     * One folder-root violation, as the document names it.
     *
     * @param violation {@link PathViolation} why one root cannot be worked in
     * @return a {@link SequencedMap} of {@link String} to {@link Object} that violation's fields
     */
    private static SequencedMap<String, Object> violation(final PathViolation violation) {
        return switch (violation) {
            case final PathViolation.NotConfigured v -> Fields.of("type", "NotConfigured", "role", v.role(),
                    "property", PathsMisconfiguredException.property(v.role()));
            case final PathViolation.NotAPath v -> Fields.of("type", "NotAPath", "role", v.role(),
                    "property", PathsMisconfiguredException.property(v.role()), "value", v.value());
            case final PathViolation.NotADirectory v -> Fields.of("type", "NotADirectory", "role", v.role(),
                    "property", PathsMisconfiguredException.property(v.role()), "path", v.path().toString());
            case final PathViolation.Unreadable v -> Fields.of("type", "Unreadable", "role", v.role(),
                    "property", PathsMisconfiguredException.property(v.role()), "path", v.path().toString());
            case final PathViolation.Overlap v -> Fields.of("type", "Overlap", "first", v.first(),
                    "firstProperty", PathsMisconfiguredException.property(v.first()), "second", v.second(),
                    "secondProperty", PathsMisconfiguredException.property(v.second()));
        };
    }

    /**
     * The refusal for a working root another process holds.
     *
     * @param busy {@link WorkingRootBusyException} the refused claim
     * @return {@link Refusal} the refusal
     */
    private static Refusal workingRootBusy(final WorkingRootBusyException busy) {
        return new Refusal(RefusalKind.WORKING_ROOT_BUSY, busy.getMessage(),
                Fields.of("workingRoot", busy.workingRoot().toString()));
    }

    /**
     * The refusal for a provider that needs a credential nothing holds.
     *
     * <p>It leads with the environment variable rather than with the settings screen. A machine
     * reaching this surface may have no screen to open, and the variable is the route that works
     * on every one of them.
     *
     * <p>The places are reported alongside, because "nothing holds one" and "the one place that
     * could hold one refused the question" send somebody to different work.
     *
     * @param missing {@link MissingCredentialException} the provider's own refusal
     * @return {@link Refusal} the refusal
     */
    private Refusal credentialMissing(final MissingCredentialException missing) {
        final SecretId id = missing.id();
        final List<SecretHolding> places = this.secrets.holdings(id);
        return new Refusal(RefusalKind.CREDENTIAL_MISSING,
                Refusal.sentences(List.of(missing.getMessage(),
                        "Set " + id.environmentVariable() + ", or store a key in Settings.")),
                Fields.of("provider", id.provider(),
                        "environmentVariable", id.environmentVariable(),
                        "places", places.stream().map(RefusalClassifier::place).toList()));
    }

    /**
     * One place a credential could sit, and what it answered.
     *
     * @param holding {@link SecretHolding} the place and its answer
     * @return a {@link SequencedMap} of {@link String} to {@link Object} that place's fields
     */
    private static SequencedMap<String, Object> place(final SecretHolding holding) {
        return Fields.of("location", holding.location().getClass().getSimpleName(),
                "holding", holding.holding());
    }

    /**
     * The refusal for a credential store that could not say what it holds.
     *
     * @param broken {@link SecretStoreException} the store's own failure
     * @return {@link Refusal} the refusal
     */
    private static Refusal credentialStoreFailed(final SecretStoreException broken) {
        return new Refusal(RefusalKind.CREDENTIAL_STORE_FAILED, broken.getMessage(),
                Fields.of("tier", broken.tier()));
    }
}
