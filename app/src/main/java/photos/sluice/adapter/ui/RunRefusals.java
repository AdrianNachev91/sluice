package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.NoteIsNotTextException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.UnrecognisedProviderException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Turns what a run refused or threw into the sentence the dashboard shows for it.
 *
 * <p>Static and holding nothing. The same failure reaches a reader two ways. A refusal raised before
 * a job exists lands as a line on the launcher. One raised by a job already under way lands on the
 * result card. Both say it in these words, so one fault reads one way.
 */
final class RunRefusals {

    private RunRefusals() {
    }

    /**
     * What to tell the reader, and where to send them if anywhere.
     *
     * @param sentence {@link String} the words to show
     * @param location {@link Location} the screen that can be done something about, or null where
     *         the sentence names nowhere to go
     */
    record Refusal(String sentence, @Nullable Location location) {
    }

    /**
     * A failure as a sentence, falling back to the type where it carries none.
     *
     * @param failure {@link Throwable} what went wrong
     * @return {@link String} the sentence to show
     */
    static String refuseSentence(final Throwable failure) {
        return said(failure).sentence();
    }

    /**
     * A failure as a line a screen reports, dressed as a refusal.
     *
     * @param failure {@link Throwable} what went wrong
     * @return {@link Message} the line, carrying any location the sentence earned
     */
    static Message refuseMessage(final Throwable failure) {
        final Refusal refusal = said(failure);
        return new Message(refusal.sentence(), true, refusal.location());
    }

    /**
     * A failure as a sentence and, where the fault can be acted on, the screen to act on it from.
     *
     * <p>Most of what reaches here was written for the person reading it, and those messages are
     * better than anything composed out here. What has none would otherwise render as a blank, so
     * the type's own name stands in as something to search for.
     *
     * <p>One switch answers both halves. Split across two, an exception type added later takes an
     * arm in one and falls through the other. The reader is then either sent somewhere the sentence
     * never names, or left with no way to the screen that would fix it.
     *
     * @param failure {@link Throwable} what went wrong
     * @return {@link Refusal} the sentence, and the location where there is one
     */
    static Refusal said(final Throwable failure) {
        return switch (failure) {
            // These three are refusals this app writes for the person meeting them, and each says
            // what to do about itself.
            case final JobInProgressException refused -> refusalWithoutLocation(messageOf(refused));
            case final ShuttingDownException closing -> refusalWithoutLocation(messageOf(closing));
            case final ImportSourceException unusable -> refusalWithoutLocation(messageOf(unusable));
            // This one carries a message built for a log, down to the configuration key that is
            // wrong. Which folder is at fault is the part a reader needs, in the words the rest of
            // this app calls that folder by.
            case final PathsMisconfiguredException misconfigured -> foldersAtFault(misconfigured);
            // Several throw sites raise this, and their messages differ: one names the environment
            // variable that would override the key, another only the provider. Its type and its id
            // are the contract, so the words are composed here instead of taken from any of them.
            case final MissingCredentialException _ -> new Refusal("A sift cannot be started because "
                    + "your provider key is not set.", Location.SETTINGS);
            // A store that answers neither yes nor no. Worded as Settings words the same fault, and
            // carrying the same remedy, since one broken store must not read as two problems.
            case final SecretStoreException broken -> new Refusal("Your key could not be read. "
                    + "The credential store on this computer refused to answer. Nothing else you "
                    + "have configured is affected. Saving your key again often fixes it on its own. "
                    + "If it keeps happening, report this as a bug, quoting this: "
                    + broken.getMessage(), Location.SETTINGS);
            case final Pipeline.ScopeOccupiedException occupied -> scopeOccupiedRefusal(occupied);
            case final Pipeline.ScopeOverlapsException overlaps -> scopeOverlapsRefusal(overlaps);
            // Written for the person meeting it, like the three above. A run finishing between a
            // screen being drawn and its button being pressed is the ordinary way here.
            case final Pipeline.RunAlreadyFinishedException finished -> refusalWithoutLocation(messageOf(finished));
            case final Pipeline.NothingToRedoException nothing -> refusalWithoutLocation(messageOf(nothing));
            case final Pipeline.ScopeUnreadableException unreadable -> refusalWithoutLocation("Cannot "
                    + "determine the sifts for this timeframe: " + unreadable.prepDir()
                    + " cannot be read. Most likely the folder is held by another process or not "
                    + "there anymore.");
            // Discarding is one of the calls that raise this, so offering a discard here would name
            // the press that just refused.
            case final Pipeline.RunOutsideWorkingRootException outside -> refusalWithoutLocation("This sift is "
                    + "at " + outside.prepDir() + ", which is not inside the folders currently "
                    + "saved. Point your working folder back at the one holding it to work on it "
                    + "again.");
            case final MalformedPrepJsonException _ -> refusalWithoutLocation("That sift's own records could "
                    + "not be read, because what is in them is damaged.");
            // Says nothing about which answer is wrong. What this carries is the engine's own list,
            // written for a report rather than for a reader.
            case final ApplyException _ -> refusalWithoutLocation("This sift's answers do not hold together, "
                    + "so nothing was moved. Your photos are still in Sorted.");
            case final NoteIsNotTextException note -> refusalWithoutLocation(noteNotTextSentence(note.file()));
            case final UnrecognisedProviderException unrecognised -> providerUnrecognised(unrecognised);
            // Above the arm below it, since a file whose bytes are not text was reached, and
            // neither of the reasons that one offers is true of it.
            case final UncheckedIOException damaged
                    when damaged.getCause() instanceof CharacterCodingException ->
                    refusalWithoutLocation(fileNotTextSentence(String.valueOf(damaged.getCause())));
            case final UncheckedIOException failed -> refusalWithoutLocation(fileOutOfReach(failed.getMessage()));
            case final IOException failed -> refusalWithoutLocation(fileOutOfReach(failed.toString()));
            // Nothing here was written for a reader, so the words are the app's own and the
            // technical text rides along verbatim. Quoting it is what makes the bug report worth
            // filing, and the dashboard is where the user can copy it from.
            default -> refusalWithoutLocation("That did not work, and it's not known why. "
                    + "Report this as a bug, quoting this: " + failure);
        };
    }

    /**
     * What to say where the chosen timeframe shares photos with unfinished sifts without being one of
     * them.
     *
     * @param across a {@link List} of {@link String} the scopes in the way, as their runs name them
     * @param chosen {@link String} the timeframe the reader picked
     * @return {@link Refusal} the sentence, and the runs screen to deal with them from
     */
    static Refusal overlapUnfinishedRefusal(final CullScope.Year chosen, final List<CullScope.Year> across) {
        return new Refusal(RunWords.spelledScope(chosen) + " overlaps "
                + RunWords.listed(across.stream().map(RunWords::spelledScope).toList())
                + ", which " + (across.size() == 1 ? "is a sift" : "are sifts")
                + " you have not finished. Finish or discard "
                + (across.size() == 1 ? "it" : "them") + " first.", Location.RUNS);
    }

    /**
     * What to say where the configured vision provider is one this build has never heard of.
     *
     * <p>Names the ids that would have worked. Settings shows a provider it recognises whatever is
     * configured, so a reader sent there with nothing else to go on finds a screen that looks
     * right.
     *
     * @param unrecognised {@link UnrecognisedProviderException} the refused lookup, carrying the id
     *         asked for and the ids this build answers to
     * @return {@link Refusal} the sentence, and the screen the provider is chosen on
     */
    private static Refusal providerUnrecognised(final UnrecognisedProviderException unrecognised) {
        return new Refusal("There is no vision provider called '" + unrecognised.provider()
                + "'. The ones Sluice has are "
                + RunWords.listed(unrecognised.registered().stream().sorted().toList())
                + ". Pick one of those in Settings.", Location.SETTINGS);
    }

    /**
     * A sentence that names no screen anything can be done from.
     *
     * @param sentence {@link String} the words to show
     * @return {@link Refusal} those words, naming no location
     */
    private static Refusal refusalWithoutLocation(final String sentence) {
        return new Refusal(sentence, null);
    }

    /**
     * What to say where the filesystem would not answer.
     *
     * <p>Names no file. This one sentence answers for every file this app opens, moves or writes,
     * and only the call that failed knows which of those it was after.
     *
     * @param quoting {@link String} the technical text worth putting in a bug report
     * @return {@link String} the sentence to show
     */
    private static String fileOutOfReach(final String quoting) {
        return "A file could not be reached. Another program may have it open, or it is not there "
                + "anymore. If it keeps happening, report it, quoting this: " + quoting;
    }

    /**
     * What to say where a file was read and turned out not to hold text.
     *
     * @param quoting {@link String} the technical text worth putting in a bug report
     * @return {@link String} the sentence to show
     */
    private static String fileNotTextSentence(final String quoting) {
        return "A file that had to be read holds something other than text. Something else may "
                + "have written over it. If it keeps happening, report it, quoting this: " + quoting;
    }

    /**
     * What to say where the file that does not hold text is one this app wrote, and is known.
     *
     * @param note {@link Path} the note whose bytes are not text
     * @return {@link String} the sentence to show
     */
    private static String noteNotTextSentence(final Path note) {
        return "The note at " + note + " holds something other than the text that should be in it. "
                + "Open it to see what is there, and delete it if it is not worth keeping.";
    }

    /**
     * What to say about a timeframe a sift is already sitting on.
     *
     * <p>The exception's own message names the prep dir and the raw state, which is what a log
     * needs. A reader needs to know their earlier sift is still there, and why this one stopped.
     *
     * <p>Names the runs screen, where that earlier sift can be carried on or thrown away. The
     * launcher offers the same thing on its own button wherever it can see the run coming. A reader
     * meeting this sentence has usually arrived another way. A press on a finished sort's card, or
     * a run that appeared between the screen being drawn and the button being pressed.
     *
     * @param occupied {@link Pipeline.ScopeOccupiedException} the refusal, carrying the run
     * @return {@link Refusal} the sentence, and the runs screen to deal with it from
     */
    private static Refusal scopeOccupiedRefusal(final Pipeline.ScopeOccupiedException occupied) {
        return new Refusal("You already have a sift of " + occupied.occupant().scope()
                + " that has not finished. Another cannot be started for the same timeframe while "
                + "that one is there. Continue it or discard it first.", Location.RUNS);
    }

    /**
     * What to say about a refused overlap, once the runs in the way have been read back as scopes.
     *
     * <p>A run whose folder name this app did not build reads back as nothing, and a sentence
     * cannot name what it could not read. Where that leaves nothing to name, the refusal falls
     * back to its own message rather than saying a timeframe overlaps an empty list.
     *
     * @param overlaps {@link Pipeline.ScopeOverlapsException} the refusal
     * @return {@link Refusal} the sentence, and the location where the runs could be named
     */
    private static Refusal scopeOverlapsRefusal(final Pipeline.ScopeOverlapsException overlaps) {
        final List<CullScope.Year> across = overlaps.across().stream()
                .map(run -> CullScope.yearScopeOf(run.scope()))
                .filter(Objects::nonNull)
                .toList();
        // Nothing to send the reader to where none of them could be read back.
        return across.isEmpty()
                ? refusalWithoutLocation(messageOf(overlaps))
                : overlapUnfinishedRefusal(overlaps.chosen(), across);
    }

    /**
     * A refusal's own sentence, falling back to its type where it carries none.
     *
     * @param refusal {@link RuntimeException} a refusal written for the person meeting it
     * @return {@link String} the sentence to show
     */
    private static String messageOf(final RuntimeException refusal) {
        final String said = refusal.getMessage();
        return said == null || said.isBlank()
                ? "That did not work, and it's not known why."
                : said;
    }

    /**
     * Which folders a refused run found unusable, named the way the rest of this app names them.
     *
     * <p>The exception's own message is built for a log and carries the configuration key rather
     * than the folder. A reader has never seen that key and cannot act on it.
     *
     * @param misconfigured {@link PathsMisconfiguredException} what the facade refused with
     * @return {@link Refusal} the sentence, and the screen the folders are set on
     */
    private static Refusal foldersAtFault(final PathsMisconfiguredException misconfigured) {
        final List<String> folders = misconfigured.violations().stream()
                .flatMap(violation -> rolesIn(violation).stream())
                .distinct()
                .sorted()
                .map(PathRoleLabels::of)
                .toList();
        return new Refusal(folders.isEmpty()
                ? "Your folder settings cannot be used."
                : "Your " + RunWords.listed(folders) + " cannot be used any more.",
                Location.SETTINGS);
    }

    /**
     * Which folders one violation is about.
     *
     * <p>An overlap is the one kind that is about two of them, and neither is at fault on its own.
     * The pair are named together, and the sentence they land in says only to go and look.
     *
     * @param violation {@link PathViolation} what was found wrong
     * @return a {@link List} of {@link PathRole} the folders it names
     */
    private static List<PathRole> rolesIn(final PathViolation violation) {
        return switch (violation) {
            case PathViolation.NotConfigured(final PathRole role) -> List.of(role);
            case PathViolation.NotAPath(final PathRole role, String _) -> List.of(role);
            case PathViolation.NotADirectory(final PathRole role, Path _) -> List.of(role);
            case PathViolation.Unreadable(final PathRole role, Path _) -> List.of(role);
            case PathViolation.Overlap(final PathRole first, final PathRole second) ->
                    List.of(first, second);
        };
    }
}
