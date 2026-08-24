package photos.sluice.adapter.ui;

/**
 * Which of the dashboard's three faces is up: setting a run up, watching one work, or reading how
 * one ended.
 *
 * <p>Sealed so that a screen has to answer for each. The three are exclusive, which is what keeps a
 * finished run's report and a refusal about the next run off the page together. A report belongs to
 * {@link Finished} and a refusal to {@link Setup}, and the screen fills one face and only one.
 *
 * <p>Both can be true of the presenter at once, and that is fine. A sift applies decisions that
 * empty the year its scope named, so the launcher behind a finished run's card really does refuse
 * that scope. What stops the two being read as one statement is that nobody sees them together.
 */
public sealed interface RunStage {

    /**
     * No run is working, so the launcher is up.
     *
     * <p>Carries nothing. What the launcher draws is {@link RunLauncherPresenter#view()}, which
     * answers whether or not a run has ever been started.
     */
    record Setup() implements RunStage {
    }

    /**
     * A run is working.
     *
     * @param progress {@link RunProgressView} what the progress area draws
     */
    record Running(RunProgressView progress) implements RunStage {
    }

    /**
     * A run has ended and nobody has dismissed its report yet.
     *
     * @param result {@link RunResultView} what the result card draws
     */
    record Finished(RunResultView result) implements RunStage {
    }
}
