package photos.sluice.adapter.ui;

import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;

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
     * A failure as a sentence, falling back to the type where it carries none.
     *
     * <p>Most of what reaches here was written for the person reading it, and those messages are
     * better than anything composed out here. What has none would otherwise render as a blank, so
     * the type's own name stands in as something to search for.
     *
     * @param failure {@link Throwable} what went wrong
     * @return {@link String} the sentence to show
     */
    static String plainly(final Throwable failure) {
        return switch (failure) {
            // These three are refusals this app writes for the person meeting them, and each says
            // what to do about itself.
            case final JobInProgressException refused -> messageOf(refused);
            case final ShuttingDownException closing -> messageOf(closing);
            case final ImportSourceException unusable -> messageOf(unusable);
            // This one carries a message built for a log, down to the configuration key that is
            // wrong. Which folder is at fault is the part a reader needs, in the words the rest of
            // this app calls that folder by.
            case final PathsMisconfiguredException misconfigured -> foldersAtFault(misconfigured);
            // Several throw sites raise this, and their messages differ: one names the environment
            // variable that would override the key, another only the provider. Its type and its id
            // are the contract, so the words are composed here instead of taken from any of them.
            case final MissingCredentialException _ -> "A sift cannot be started because your "
                    + "provider key is not set. Add one in Settings.";
            // A store that answers neither yes nor no. Worded as Settings words the same fault, and
            // carrying the same remedy, since one broken store must not read as two problems.
            case final SecretStoreException broken -> "Sluice could not read your key. The credential "
                    + "store on this computer refused to answer. Nothing else you have configured is "
                    + "affected. Open Settings and save your key again: that alone often fixes it. "
                    + "If it keeps happening, report this as a bug in Sluice, quoting this: "
                    + broken.getMessage();
            case final Pipeline.ScopeOccupiedException occupied -> occupiedBy(occupied);
            case final Pipeline.ScopeOverlapsException overlaps -> overlapping(overlaps);
            // Written for the person meeting it, like the three above. A run finishing between a
            // screen being drawn and its button being pressed is the ordinary way here.
            case final Pipeline.RunAlreadyFinishedException finished -> messageOf(finished);
            case final Pipeline.ScopeUnreadableException unreadable -> "Sluice doesn't know whether a "
                    + "sift is already running for that timeline, because " + unreadable.prepDir()
                    + " cannot be read. Most likely the folder is held by another process or not "
                    + "there anymore.";
            case final Pipeline.RunOutsideWorkingRootException outside -> "That sift is at "
                    + outside.prepDir() + ", which is not inside the folders Sluice is set up with "
                    + "now. Point your working folder back at the one holding it, or discard the sift.";
            // Nothing here was written for a reader, so the words are the app's own and the
            // technical text rides along verbatim. Quoting it is what makes the bug report worth
            // filing, and the dashboard is where the user can copy it from.
            default -> "Sluice could not do that, and has no plain words for why. "
                    + "Report this as a bug in Sluice, quoting this: " + failure;
        };
    }

    /**
     * A failure's own cause where it has one, since what a job threw is usually a wrapper.
     *
     * @param failure {@link Throwable} what the job's promise completed with
     * @return {@link Throwable} the one carrying the sentence worth showing
     */
    static Throwable rootOf(final Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
    }

    /**
     * What to say about a timeline a sift is already sitting on.
     *
     * <p>The exception's own message names the prep dir and the raw state, which is what a log
     * needs. A reader needs to know their earlier sift is still there, and why this one stopped.
     *
     * <p>Names the runs screen, where that earlier sift can be carried on or thrown away. The
     * launcher offers the same thing on its own button wherever it can see the run coming. A reader
     * meeting this sentence has usually arrived another way: a press on a finished sort's card, or
     * a run that appeared between the screen being drawn and the button being pressed.
     *
     * @param occupied {@link Pipeline.ScopeOccupiedException} the refusal, carrying the run
     * @return {@link String} the sentence to show
     */
    private static String occupiedBy(final Pipeline.ScopeOccupiedException occupied) {
        return "You already have a sift of " + occupied.occupant().scope() + " that has not finished. "
                + "Another cannot be started for the same timeline while that one is there. "
                + "Open Runs to continue or discard it.";
    }

    /**
     * What to say about a refused overlap, once the runs in the way have been read back as scopes.
     *
     * <p>A run whose folder name this app did not build reads back as nothing, and a sentence
     * cannot name what it could not read. Where that leaves nothing to name, the refusal falls
     * back to its own message rather than saying a timeline overlaps an empty list.
     *
     * @param overlaps {@link Pipeline.ScopeOverlapsException} the refusal
     * @return {@link String} the sentence to show
     */
    private static String overlapping(final Pipeline.ScopeOverlapsException overlaps) {
        final List<CullScope.Year> across = overlaps.across().stream()
                .map(run -> CullScope.yearScopeOf(run.scope()))
                .filter(Objects::nonNull)
                .toList();
        return across.isEmpty() ? messageOf(overlaps) : coveringUnfinished(overlaps.chosen(), across);
    }

    /**
     * What to say where the chosen timeline shares photos with unfinished sifts without being one of
     * them.
     *
     * <p>Public and taking the scopes rather than the refusal, because two callers word it. The
     * facade refuses on the same fault, and the launcher greys Start before anybody presses it. One
     * sentence, so the screen and the refusal cannot drift apart.
     *
     * <p>Names every one of them rather than the first. A reader told about one deals with it,
     * comes back, and is refused by the next.
     *
     * @param across a {@link List} of {@link String} the scopes in the way, as their runs name them
     * @param chosen {@link String} the timeline the reader picked
     * @return {@link String} the sentence to show
     */
    static String coveringUnfinished(final CullScope.Year chosen, final List<CullScope.Year> across) {
        return RunWords.spelledScope(chosen) + " overlaps "
                + RunWords.listed(across.stream().map(RunWords::spelledScope).toList())
                + ", which " + (across.size() == 1 ? "is a sift" : "are sifts")
                + " you have not finished. Finish or discard "
                + (across.size() == 1 ? "it" : "them") + " in Runs, then you can sift this.";
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
                ? "Sluice stopped, and said nothing about why."
                : said;
    }

    /**
     * Which folders a refused run found unusable, named the way the rest of this app names them.
     *
     * <p>The exception's own message is built for a log and carries the configuration key rather
     * than the folder. A reader has never seen that key and cannot act on it.
     *
     * @param misconfigured {@link PathsMisconfiguredException} what the facade refused with
     * @return {@link String} the sentence to show
     */
    private static String foldersAtFault(final PathsMisconfiguredException misconfigured) {
        final List<String> folders = misconfigured.violations().stream()
                .flatMap(violation -> rolesIn(violation).stream())
                .distinct()
                .sorted()
                .map(PathRoleLabels::of)
                .toList();
        return folders.isEmpty()
                ? "Sluice cannot work with your folder settings. Check them in Settings."
                : "Sluice cannot use your " + RunWords.listed(folders) + " any more. "
                        + (folders.size() == 1 ? "Check it in Settings." : "Check them in Settings.");
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
