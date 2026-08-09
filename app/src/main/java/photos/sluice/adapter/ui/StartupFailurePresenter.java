package photos.sluice.adapter.ui;

/**
 * Turns a startup failure into the two strings the failure window shows. Bean wiring reports its
 * problems several layers deep, so the message worth reading is rarely the one thrown at the top.
 * Which layer it is takes a rule, and {@link #detail()} owns that rule.
 *
 * <p>A presenter rather than a view: deciding what to show is a decision, and a decision is
 * something a test can hold to account.
 */
public class StartupFailurePresenter {

    private static final String HEADLINE = "Sluice could not start.";
    private static final String NO_DETAIL = "No further detail was reported.";

    // Deep enough that a real wiring failure reaches its own end long first. A longer chain is read
    // down to here and no further. That costs the tail of something already unreadable, and buys
    // termination on a chain that loops.
    private static final int MAX_CAUSE_DEPTH = 100;

    private final Throwable failure;

    /**
     * Creates the presenter over the failure that stopped startup.
     *
     * @param failure {@link Throwable} the failure raised while starting
     */
    public StartupFailurePresenter(final Throwable failure) {
        this.failure = failure;
    }

    /**
     * The one line naming what happened.
     *
     * @return {@link String} the headline
     */
    public String headline() {
        return HEADLINE;
    }

    /**
     * What went wrong, taken from the deepest cause that actually says something. A configuration
     * problem states itself in words a person can act on, while the wrappers above it only say
     * which bean failed.
     *
     * <p>Depth alone is the wrong rule. The innermost failure is often a technical one carrying no
     * message at all, and taking it would throw away the sentence somebody wrote to be read.
     *
     * <p>Falls back to naming the failure's own type when nothing in the chain carries a message,
     * so the window is never blank. The walk is bounded, so a chain that loops back on itself
     * cannot hang the one window whose job is to explain why nothing else works.
     *
     * @return {@link String} the detail line
     */
    public String detail() {
        String deepestMessage = null;
        Throwable current = this.failure;
        Throwable deepest = this.failure;
        // Bounded rather than walked to the end. A cause chain that loops back on itself would
        // otherwise hang the one window whose whole job is to explain why nothing else works.
        int remaining = MAX_CAUSE_DEPTH;
        while (current != null && remaining-- > 0) {
            final String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepestMessage = message;
            }
            deepest = current;
            current = current.getCause();
        }
        if (deepestMessage == null) {
            return deepest.getClass().getSimpleName() + ". " + NO_DETAIL;
        }
        return deepestMessage;
    }
}
