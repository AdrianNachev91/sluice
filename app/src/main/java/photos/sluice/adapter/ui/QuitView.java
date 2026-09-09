package photos.sluice.adapter.ui;

/**
 * What the two dialogs a quit puts up say, chosen from a {@link QuitPresenter} and carrying only
 * display-ready values.
 *
 * <p>Two dialogs rather than one, and the split is what keeps the first one true. Nothing has been
 * asked to stop while the question is on screen, so keeping the run really does keep it. A single
 * dialog offering a wait would have to start that wait as it opened, and then the way out of it
 * would put nothing back.
 *
 * @param heading {@link String} what the question is about
 * @param question {@link String} the question, in the terms the choices answer it
 * @param stopAndQuit {@link String} what the choice that goes ahead says
 * @param keepRunning {@link String} what the way out says
 * @param stopAndQuitLeads boolean whether quitting is the loud choice, the one Enter lands on
 * @param waitingHeading {@link String} what the second dialog is headed once the first is answered
 * @param waiting {@link String} what the second dialog says while the run stops
 * @param forceQuit {@link String} what the control that gives up on the wait says
 */
public record QuitView(String heading, String question, String stopAndQuit, String keepRunning,
                       boolean stopAndQuitLeads, String waitingHeading, String waiting,
                       String forceQuit) {
}
