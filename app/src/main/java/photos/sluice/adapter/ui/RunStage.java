package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

/**
 * Which of the dashboard's three faces is up: setting a run up, watching one work, or reading how
 * one ended.
 *
 * <p>Sealed so that a screen has to answer for each. The three are exclusive, which is what keeps a
 * finished run's report and a refusal about the next run off the page together. The screen fills
 * one face and only one.
 *
 * <p>Each face carries its own refusals. The launcher's are on {@link RunSetupPresenter}, and a
 * card's arrive with {@link Finished}. A refusal has to appear where the reader who caused it is
 * looking, and these two faces are never up together.
 *
 * <p>The presenter can hold a finished run's report and a launcher refusal at once, and that is
 * fine. A sift applies decisions that empty the year its scope named, so the launcher behind a
 * finished run's card really does refuse that scope. What stops the two being read as one statement
 * is that nobody sees them together.
 */
public sealed interface RunStage {

    /**
     * No run is working, so the launcher is up.
     *
     * <p>Carries nothing. What the launcher draws is {@link RunSetupPresenter#view()}, which
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
     * <p>The message is about a press on this card rather than about the run. Offering to sift is
     * an ordinary press to refuse. The timeline can already have an unfinished sift on it, it can
     * overlap one, or another job can have started in between.
     *
     * @param result {@link RunResultView} what the result card draws
     * @param message {@link RunLauncherView.Message} what the card has to report about a press on
     *     it, or null where it has nothing to say
     */
    record Finished(RunResultView result, RunLauncherView.@Nullable Message message)
            implements RunStage {
    }
}
