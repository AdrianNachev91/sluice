package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.NoteIsNotTextException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.UnrecognisedProviderException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.secrets.SecretHolding;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStore;
import photos.sluice.secrets.SecretStoreException;

import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * Says whether a failure is something the app refused, and if so which refusal it was.
 *
 * <p>Which refusal it is, is decided by the exception's type alone. Matching on a message would
 * break silently the first time somebody improved a sentence.
 *
 * <p>Some branches then carry that exception's own message through. What decides it is who the
 * message was written for. One written for a person is carried. One written for a log, or for a
 * screen this surface has not got, is replaced by words composed here that say why in their own
 * place.
 *
 * <p>One arm classifies nothing. A scope argument a verb refused already carries its own refusal,
 * worked out where the rule that refused it lives. It comes through here so that a caller meets it
 * on the same two streams, in the same document, as every refusal the app raises further down.
 */
@Component
@Profile("cli")
public class RefusalClassifier {

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
        return this.recognisedRefusal(JobHandle.failureIn(failure));
    }

    /**
     * The refusal one exception names, or null when it names none.
     *
     * @param failure {@link Throwable} the unwrapped failure
     * @return {@link Refusal} the refusal, or null
     */
    private @Nullable Refusal recognisedRefusal(final Throwable failure) {
        return switch (failure) {
            case final ScopeRefusedException scope -> scope.refusal();
            case final PathsMisconfiguredException paths -> foldersUnusable(paths);
            case final WorkingRootBusyException busy -> workingRootBusy(busy);
            // The condition is what every site raising this shares, and all a caller here can act on.
            case final JobInProgressException _ -> Refusal.of(RefusalKind.JOB_IN_PROGRESS,
                    "Something else is running. Wait for it to finish, then try again.");
            case final MissingCredentialException missing -> this.credentialMissing(missing);
            case final SecretStoreException broken -> credentialStoreFailed(broken);
            case final Pipeline.ScopeOccupiedException occupied -> scopeOccupied(occupied);
            case final Pipeline.ScopeOverlapsException overlapped -> scopeOverlaps(overlapped);
            case final Pipeline.ScopeUnreadableException unreadable -> scopeUnreadable(unreadable);
            case final Pipeline.RunOutsideWorkingRootException outside -> runOutsideWorkingRoot(outside);
            case final Pipeline.RunAlreadyFinishedException finished ->
                    Refusal.of(RefusalKind.RUN_ALREADY_FINISHED, finished.getMessage());
            case final Pipeline.NothingToRedoException nothing ->
                    Refusal.of(RefusalKind.NOTHING_TO_REDO, nothing.getMessage());
            case final ConfirmationRequiredException unconfirmed ->
                    Refusal.of(RefusalKind.CONFIRMATION_REQUIRED, unconfirmed.getMessage());
            case final AnswerNotApplicableException notApplicable ->
                    Refusal.of(RefusalKind.ANSWER_NOT_APPLICABLE, notApplicable.getMessage());
            case final ImportSourceException refused -> Refusal.of(RefusalKind.IMPORT_SOURCE_REFUSED,
                    refused.getMessage());
            case final UnrecognisedProviderException unrecognised -> providerUnrecognised(unrecognised);
            case final ApplyException _ -> Refusal.of(RefusalKind.ANSWERS_DO_NOT_HOLD,
                    "This sift's answers do not hold together, so nothing was moved. "
                            + "Run 'troubleshoot' on it to see what is wrong.");
            case final ShuttingDownException _ -> Refusal.of(RefusalKind.SHUTTING_DOWN,
                    "Shutdown in progress. This was not started.");
            case final NoteIsNotTextException note -> noteNotText(note);
            // These three are one type family, most specific first. All three are an
            // UncheckedIOException, so a broader arm placed above a narrower one swallows it.
            case final MalformedPrepJsonException malformed -> runRecordsUnreadable(malformed);
            case final UncheckedIOException io when io.getCause() instanceof final NoSuchFileException missing ->
                    folderNotFound(missing);
            case final UncheckedIOException io -> fileUnreachable(io);
            default -> null;
        };
    }

    /**
     * The refusal for a run whose own records could not be read.
     *
     * <p>The path is a file inside a run rather than anything a caller typed, so this names a
     * record rather than a folder.
     *
     * @param malformed {@link MalformedPrepJsonException} the read that could not be made sense of
     * @return {@link Refusal} the refusal
     */
    private static Refusal runRecordsUnreadable(final MalformedPrepJsonException malformed) {
        return new Refusal(RefusalKind.RUN_RECORDS_UNREADABLE,
                "This sift's own records could not be read, so how far it got is unknown. "
                        + "Often another program has them open. Run 'troubleshoot' on it to see what "
                        + "can be repaired.",
                Fields.of("problem", String.valueOf(malformed.getMessage())));
    }

    /**
     * The refusal for a file the command could not reach, for a reason other than its absence.
     *
     * @param unreachable {@link UncheckedIOException} the read or write that could not be done
     * @return {@link Refusal} the refusal
     */
    private static Refusal fileUnreachable(final UncheckedIOException unreachable) {
        return new Refusal(RefusalKind.FILE_UNREACHABLE,
                "A file could not be reached. Another program may have it open, or a drive may not be "
                        + "reachable.",
                Fields.of("problem", String.valueOf(unreachable.getMessage())));
    }

    /**
     * The refusal for a note whose bytes are not the text this app wrote there.
     *
     * <p>Names the file, since a folder can hold several notes.
     *
     * @param note {@link NoteIsNotTextException} the read that came back as something else
     * @return {@link Refusal} the refusal
     */
    private static Refusal noteNotText(final NoteIsNotTextException note) {
        return new Refusal(RefusalKind.NOTE_IS_NOT_TEXT,
                note.file() + " holds something other than the text Sluice wrote there. Open it to "
                        + "see what is in it, and delete it if it is not worth keeping.",
                Fields.of("file", note.file().toString()));
    }

    /**
     * The refusal for a folder the command was asked to work on that is not there.
     *
     * @param missing {@link NoSuchFileException} the read failure naming the missing folder
     * @return {@link Refusal} the refusal
     */
    private static Refusal folderNotFound(final NoSuchFileException missing) {
        final String path = missing.getFile();
        return new Refusal(RefusalKind.FOLDER_NOT_FOUND,
                "No such folder: " + path + ". Check the folder name and try again.",
                Fields.of("path", path));
    }

    /**
     * The refusal for a sift whose exact scope already occupies a prep dir.
     *
     * @param occupied {@link Pipeline.ScopeOccupiedException} the refused claim
     * @return {@link Refusal} the refusal
     */
    private static Refusal scopeOccupied(final Pipeline.ScopeOccupiedException occupied) {
        return new Refusal(RefusalKind.SCOPE_OCCUPIED,
                "A sift of " + occupied.occupant().scope() + " has not finished. Resume, troubleshoot or "
                        + "discard it before starting another for the same scope.",
                Fields.of("occupant", CullPayloads.run(occupied.occupant())));
    }

    /**
     * The refusal for a sift whose timeframe shares months with unfinished sifts under other tags.
     *
     * @param overlapped {@link Pipeline.ScopeOverlapsException} the refused claim
     * @return {@link Refusal} the refusal
     */
    private static Refusal scopeOverlaps(final Pipeline.ScopeOverlapsException overlapped) {
        final List<String> tags = overlapped.across().stream().map(CullRunSummary::scope).sorted().toList();
        final String them = tags.size() == 1 ? "it" : "them";
        return new Refusal(RefusalKind.SCOPE_OVERLAPS,
                CullScope.tag(overlapped.chosen()) + " covers months already in " + String.join(", ", tags)
                        + ", which " + (tags.size() == 1 ? "has" : "have") + " not finished. Finish or "
                        + "discard " + them + " first.",
                Fields.of("chosen", CullScope.tag(overlapped.chosen()),
                        "overlapping", overlapped.across().stream().map(CullPayloads::run).toList()));
    }

    /**
     * The refusal for a prep dir that could not be read, so whether it is occupied is unknown.
     *
     * @param unreadable {@link Pipeline.ScopeUnreadableException} the refused claim
     * @return {@link Refusal} the refusal
     */
    private static Refusal scopeUnreadable(final Pipeline.ScopeUnreadableException unreadable) {
        return new Refusal(RefusalKind.SCOPE_UNREADABLE,
                "Whether a sift is already running for that scope is unknown, because "
                        + unreadable.prepDir() + " cannot be read. Try again once whatever is holding "
                        + "it clears.",
                Fields.of("prepDir", unreadable.prepDir().toString()));
    }

    /**
     * The refusal for a run named outside the working root now configured.
     *
     * @param outside {@link Pipeline.RunOutsideWorkingRootException} the refused claim
     * @return {@link Refusal} the refusal
     */
    private static Refusal runOutsideWorkingRoot(final Pipeline.RunOutsideWorkingRootException outside) {
        return new Refusal(RefusalKind.RUN_OUTSIDE_WORKING_ROOT,
                "This sift is at " + outside.prepDir() + ", which is not inside the folders currently set "
                        + "up. Point sluice.paths.working-root back at the folder holding it to work on it "
                        + "again.",
                Fields.of("prepDir", outside.prepDir().toString()));
    }

    /**
     * The refusal for a configured provider this build has never heard of.
     *
     * <p>Worded here rather than taken from the exception, whose own message is written for a log.
     *
     * @param unrecognised {@link UnrecognisedProviderException} the refused lookup
     * @return {@link Refusal} the refusal
     */
    private static Refusal providerUnrecognised(final UnrecognisedProviderException unrecognised) {
        final List<String> registered = unrecognised.registered().stream().sorted().toList();
        return new Refusal(RefusalKind.PROVIDER_UNRECOGNISED,
                "There is no vision provider called '" + unrecognised.provider() + "'. Set sluice.cull.provider "
                        + "to one of: " + String.join(", ", registered) + ".",
                Fields.of("provider", unrecognised.provider(), "registered", registered));
    }

    /**
     * The refusal for folder roots the app cannot work in.
     *
     * <p>Opened here rather than taken from the exception, whose own opening is written for a log.
     * The clauses after it are the exception's own.
     *
     * @param paths {@link PathsMisconfiguredException} what the facade refused on
     * @return {@link Refusal} the refusal
     */
    private static Refusal foldersUnusable(final PathsMisconfiguredException paths) {
        return new Refusal(RefusalKind.FOLDERS_UNUSABLE,
                "Unusable folder settings. " + paths.violations().stream()
                        .map(PathsMisconfiguredException::clause).collect(Collectors.joining(" ")),
                Fields.of("violations", paths.violations().stream().map(RefusalClassifier::violationFields).toList()));
    }

    /**
     * One folder-root violation, as the document names it.
     *
     * @param violation {@link PathViolation} why one root cannot be worked in
     * @return a {@link SequencedMap} of {@link String} to {@link Object} that violation's fields
     */
    private static SequencedMap<String, Object> violationFields(final PathViolation violation) {
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
                "No key is stored for the '" + id.name() + "' vision provider. Set "
                        + id.environmentVariable() + ", or store a key in Settings.",
                Fields.of("provider", id.name(),
                        "environmentVariable", id.environmentVariable(),
                        "places", places.stream().map(RefusalClassifier::placeFields).toList()));
    }

    /**
     * One place a credential could sit, and what it answered.
     *
     * @param holding {@link SecretHolding} the place and its answer
     * @return a {@link SequencedMap} of {@link String} to {@link Object} that place's fields
     */
    private static SequencedMap<String, Object> placeFields(final SecretHolding holding) {
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
        return new Refusal(RefusalKind.CREDENTIAL_STORE_FAILED,
                Refusal.sentences(List.of("Your key could not be read, and nothing else you have "
                        + "configured is affected.", "Set the provider's environment variable to get past "
                        + "it. If it keeps happening, report this as a bug, quoting: "
                        + broken.getMessage())),
                Fields.of("tier", broken.tier()));
    }
}
