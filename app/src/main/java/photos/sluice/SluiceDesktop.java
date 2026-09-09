package photos.sluice;

/**
 * The entry point an installed icon starts. It opens the window and nothing else.
 *
 * <p>A double-click carries no arguments, and no arguments asks for the help. So an icon wired to
 * the ordinary entry point would print help to a stream nobody reads, and appear to do nothing at
 * all. This one says which surface it is, and the installer names it for every way in that has no
 * terminal behind it.
 */
public final class SluiceDesktop {

    /**
     * The arguments this entry point runs with, whatever it was started with.
     */
    static final String[] WINDOW_ARGUMENTS = {SluiceApplication.APP};

    /**
     * Prevents instantiation of this static utility class.
     */
    private SluiceDesktop() {
    }

    /**
     * Opens the window.
     *
     * <p>{@code public} is required rather than redundant. The installer's launcher resolves this
     * method reflectively instead of going through the JDK's own, and reflection still wants the
     * modifier.
     *
     * @param args {@link String}[] command-line arguments, dropped rather than forwarded. The
     *     window takes nothing from a command line. Forwarding would also let an argument the
     *     operating system adds turn the request into one the parser has to refuse.
     */
    @SuppressWarnings({"UnnecessaryModifier", "unused"})
    public static void main(final String[] args) {
        SluiceApplication.main(WINDOW_ARGUMENTS);
    }
}
