package photos.sluice.adapter.ui;

/**
 * The question put to a reader leaving a screen that holds work nobody has saved.
 *
 * <p>One wording for every such screen. Naming the controls would mean a sentence per screen, and
 * each would go stale as its screen gained a field. What the reader has to decide is the same
 * either way: go back and save, or lose it.
 */
public final class LeavingUnsaved {

    private static final RunSetupPresenter.Confirmation QUESTION =
            new RunSetupPresenter.Confirmation("Leave without saving?",
                    "What you changed here is gone if you leave.", "Leave anyway", "Stay", false);

    private LeavingUnsaved() {}

    /**
     * What to ask before leaving with unsaved work on screen.
     *
     * @return {@link RunSetupPresenter.Confirmation} the question, with Stay leading
     */
    public static RunSetupPresenter.Confirmation question() {
        return QUESTION;
    }
}
