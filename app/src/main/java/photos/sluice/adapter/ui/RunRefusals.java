package photos.sluice.adapter.ui;

import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;

import java.nio.file.Path;
import java.util.List;

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
            // A curate that already moved files before being refused. Said first, because what it
            // did is the part a reader cannot see and would otherwise go looking for.
            case final Pipeline.CurateConflictException conflict -> "Your photos were sorted, and then "
                    + "sifting stopped: " + occupiedBy(conflict) + " The sorting stands.";
            case final Pipeline.ScopeOccupiedException occupied -> occupiedBy(occupied);
            case final Pipeline.ScopeUnreadableException unreadable -> "Sluice could not read "
                    + unreadable.prepDir() + ", so it cannot tell whether a sift is already running "
                    + "for that timeline. Try again once whatever is holding that folder has let go.";
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
     * <p>It names no way out, because today there is none to name. The screen that lists unfinished
     * sifts and offers to continue or discard one arrives with the runs list. Saying so here would
     * be a remedy pointing at nothing.
     *
     * @param occupied {@link Pipeline.ScopeOccupiedException} the refusal, carrying the run
     * @return {@link String} the sentence to show
     */
    private static String occupiedBy(final Pipeline.ScopeOccupiedException occupied) {
        return "You already have a sift of " + occupied.occupant().scope() + " that has not finished. "
                + "Sluice will not start another for the same timeline while that one is there.";
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
