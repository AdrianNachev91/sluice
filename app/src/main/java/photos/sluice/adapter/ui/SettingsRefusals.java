package photos.sluice.adapter.ui;

import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.UnusableSettingsException;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.UncheckedIOException;

/**
 * Turns a refused save into the sentence a settings screen shows for it.
 *
 * <p>Static and holding nothing. Two screens write to the same settings file, and the same fault
 * reaches a reader from either. Worded in one place so a settings file another program is holding
 * does not read as two different problems.
 *
 * <p>Each caller names what its own save was carrying, because that is the half a reader
 * recognises. Everything after it is the same sentence whichever screen asked.
 */
final class SettingsRefusals {

    private SettingsRefusals() {
    }

    /**
     * What to put at the foot of a settings page for a refusal no field is carrying.
     *
     * <p>Four of these are written for a user and are shown as they are. Each names its own
     * condition and what to do about it, and rewording them here would leave two versions to keep
     * in step.
     *
     * <p>The next two carry a message written for something other than a screen, so the words are
     * composed here. A settings file that cannot be understood is a file to go and look at. A read
     * or write that failed is the filesystem, and trying again is what clears it.
     *
     * <p>Anything else has no message written for a reader, or none at all. An unforeseen failure
     * says so in this app's voice instead, and offers the one thing a user can do about it. A
     * stack's own words under the Save button offer nothing.
     *
     * @param refusal {@link RuntimeException} what the save seam threw
     * @param notSaved {@link String} a whole sentence naming what this screen was saving and saying
     *         it was not, such as "Your theme was not saved." What follows is the same whichever
     *         screen asked, and carries no pronoun back to it, so a subject's number is the
     *         caller's business alone
     * @return {@link String} what to show
     */
    static String wordedForAUser(final RuntimeException refusal, final String notSaved) {
        return switch (refusal) {
            case final JobInProgressException running -> messageOf(running, notSaved);
            case final WorkingRootBusyException held -> messageOf(held, notSaved);
            case final ShuttingDownException closing -> messageOf(closing, notSaved);
            case final UnusableSettingsException unusable -> messageOf(unusable, notSaved);
            case final MalformedSettingsException damaged -> notSaved + " The settings file cannot "
                    + "be read: " + damaged.settingsFile() + ". Nothing you had configured has "
                    + "changed. Open that file to see what is in it, or delete it to start again "
                    + "from the defaults.";
            case final UncheckedIOException _ -> notSaved + " The settings file could not be "
                    + "reached, and another program may have it open. Nothing you had configured "
                    + "has changed. Try again in a moment.";
            default -> notSaved + " It's not known why. Nothing you had configured has changed. "
                    + "Report this as a bug, quoting this: " + refusal;
        };
    }

    /**
     * A refusal's own sentence, falling back to this app's words where it carries none.
     *
     * @param refusal {@link RuntimeException} a refusal written for the person meeting it
     * @param notSaved {@link String} the sentence naming what was not saved, for the fallback
     * @return {@link String} what to show
     */
    private static String messageOf(final RuntimeException refusal, final String notSaved) {
        final String said = refusal.getMessage();
        return said == null || said.isBlank()
                ? notSaved + " It's not known why. Nothing you had configured has changed."
                : said;
    }
}
