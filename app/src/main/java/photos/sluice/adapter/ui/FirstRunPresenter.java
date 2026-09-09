package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import java.util.List;

/**
 * Decides which resting state the Dashboard pane opens on, and what the first-run card says while
 * it is still the one showing.
 */
@Component
@Profile("!cli")
public class FirstRunPresenter {

    private final PathValidationUseCase pathValidation;

    /**
     * Creates the presenter over the use case that checks the roots in force.
     *
     * @param pathValidation {@link PathValidationUseCase} answers what is wrong with the roots
     *     the app is running on, if anything
     */
    public FirstRunPresenter(final PathValidationUseCase pathValidation) {
        this.pathValidation = pathValidation;
    }

    /**
     * Whether any folder root is still unset.
     *
     * <p>Any rather than all. Somebody who chose one folder and closed the window has an install
     * that cannot run, and the card holding the three pickers is where they finish. Sent to the
     * Dashboard instead, they would meet a screen saying nothing about what is missing.
     *
     * <p>Unset is the whole trigger, and a root set to somewhere unusable does not count. That root
     * has been chosen, so its owner is past this card. Which one is at fault, and why, is what a
     * refusal from the facade says.
     *
     * @return boolean true while the first-run card is still the Dashboard's resting state
     */
    public boolean unfinished() {
        return !this.rootsStillUnset().isEmpty();
    }

    /**
     * The line under the card's headline: what the card is for, or what it is still waiting on.
     *
     * <p>An install where nothing is chosen reads the first. Nobody has been asked for a folder
     * yet, so naming all three as missing would be a demand rather than an invitation.
     *
     * <p>An install part way through reads the second, and it is the same sentence a save would
     * have left. Saving one folder and coming back to the window later leave one state. Telling
     * only the first of them what is left would make it read as two.
     *
     * @return {@link String} the line to draw
     */
    public String opening() {
        final String needed = this.stillNeeded();
        return needed == null || this.everyRootIsUnset() ? "Pick three folders, choose what looks at your "
                + "photos, and Sluice is ready. You can change any of this later in Settings." : needed;
    }

    /**
     * What to say when a save from this card would move a library root that is already set.
     *
     * <p>Moving a library reads and copies across every folder Sluice has, so it is refused while
     * any of them is still unset. That is the whole time this card is on screen. Asking the question
     * here would open a dialog whose every answer ends in a refusal. The card answers it outright
     * instead, in words that say what to do next.
     *
     * <p>Changed rather than moved, because emptying a set library root raises the same refusal and
     * is not a move. One sentence covers both, and the reader's next action is the same either
     * way.
     *
     * @return {@link String} the refusal
     */
    public String libraryRootMoveRefusal() {
        return "Your Library folder cannot be changed until all three folders are chosen, so nothing "
                + "was saved. Choose the ones still needed and save those first. You can change your "
                + "Library folder in Settings afterwards.";
    }

    /**
     * What to say once a save has landed and some roots are still unset.
     *
     * <p>Such a save rebuilds a card that looks much as it did. The fields it filled in hold the
     * text the user had just typed into them. Without a line saying so, a save that worked is
     * indistinguishable from one that did nothing.
     *
     * <p>Two things can make the confirmation itself wrong, so neither is said blindly. A save from
     * an untouched card stores no folder at all, and "Saved." above a demand for all three reads as
     * a claim about the folders. And a save that carried a library move has already reported what it
     * did with the files, which is a better confirmation than this one.
     *
     * @param reported what the save had to say for itself, or null where it had nothing
     * @return {@link IncompleteSave} the line to show and whether it is good news, or null once
     *     every root is set
     */
    public @Nullable IncompleteSave savedWhileStillIncomplete(final @Nullable String reported) {
        final String needed = this.stillNeeded();
        if (needed == null) {
            return null;
        }
        if (reported != null) {
            return new IncompleteSave(reported + " " + needed, true);
        }
        return this.everyRootIsUnset()
                ? new IncompleteSave(needed, false)
                : new IncompleteSave("Saved. " + needed, true);
    }

    /**
     * What a save that left the setup unfinished has to say, and whether it went well.
     *
     * <p>Both cases leave the reader on the same card wanting the same folders, so the words alone
     * do not separate them. A save from an untouched card wrote nothing at all, and a screen that
     * reports that in the colour it uses for a confirmation is calling a dead end good news.
     *
     * @param message {@link String} the line to show
     * @param anythingWasStored boolean whether that save put a folder anywhere
     */
    public record IncompleteSave(String message, boolean anythingWasStored) {
    }

    /**
     * What the card still has to ask for, named in the order it draws the rows.
     *
     * @return {@link String} the sentence naming them, or null once every root is set
     */
    private @Nullable String stillNeeded() {
        final List<PathRole> missing = this.rootsStillUnset();
        if (missing.isEmpty()) {
            return null;
        }
        return "You still need to set up your " + RunWords.listed(missing.stream().map(PathRoleLabels::of).toList())
                + " before any work can start on your photos.";
    }

    /**
     * Whether nothing at all has been chosen, which a save from this card cannot have changed.
     *
     * @return boolean true when all three roots are unset
     */
    private boolean everyRootIsUnset() {
        return this.rootsStillUnset().size() == PathRole.values().length;
    }

    /**
     * The roots nothing has been chosen for yet, in the order the card draws them.
     *
     * @return a {@link List} of {@link PathRole} the unset roots, empty when all three are set
     */
    private List<PathRole> rootsStillUnset() {
        return this.pathValidation.violationsInForce().stream()
                .filter(NotConfigured.class::isInstance)
                .map(NotConfigured.class::cast)
                .map(NotConfigured::role)
                .sorted()
                .toList();
    }
}
