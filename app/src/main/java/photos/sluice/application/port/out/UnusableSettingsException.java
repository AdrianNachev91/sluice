package photos.sluice.application.port.out;

/**
 * A settings value the app will not run on, with the refusal already worded for whoever meets it.
 *
 * <p>What the type adds over {@link IllegalArgumentException} is a name a surface can match on. The
 * same refusal reaches two very different readers. A save on the settings screen reports it and
 * carries on. A launch has nowhere to carry on to.
 *
 * <p>{@link #getMessage()} is written for a reader rather than for a log, the startup window having
 * nothing else to show.
 */
public class UnusableSettingsException extends IllegalArgumentException {

    /**
     * Creates the refusal.
     *
     * @param message {@link String} what is wrong, worded for whoever meets it
     */
    public UnusableSettingsException(final String message) {
        super(message);
    }
}
