package photos.sluice.adapter.ui.view;

import javafx.animation.AnimationTimer;

/**
 * Holds work back until the window has been painted.
 *
 * <p>The wait is a frame rather than a duration. Work started during the pulse that first paints
 * the window would hold that paint back. That trades a slow screen for a slow launch.
 */
final class AfterFirstFrame {

    private AfterFirstFrame() {}

    /**
     * Runs the given work on the FX thread, once the window has been painted.
     *
     * @param work {@link Runnable} what to run
     */
    static void run(final Runnable work) {
        new AnimationTimer() {

            private int pulses;

            @Override
            public void handle(final long now) {
                this.pulses++;
                if (this.pulses > 1) {
                    this.stop();
                    work.run();
                }
            }
        }.start();
    }
}
