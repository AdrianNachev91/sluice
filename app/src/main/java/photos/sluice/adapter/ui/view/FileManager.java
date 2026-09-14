package photos.sluice.adapter.ui.view;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * How this app hands a folder to the user's own file manager.
 *
 * <p>Held here rather than passed down. Only the desktop launch has anything that can open a folder,
 * and the screen offering it sits several layers below.
 *
 * <p>A screen offering the button can be built on its own, outside a launched app, and nothing built
 * that way has a file manager to hand. So an unset opener is a state to tolerate rather than refuse.
 */
final class FileManager {

    private static final Logger log = LoggerFactory.getLogger(FileManager.class);

    private static @Nullable Consumer<Path> opener;

    private FileManager() {}

    /**
     * Says what opens a folder from now on.
     *
     * @param handler a {@link Consumer} of {@link Path} hands the folder to a file manager
     */
    static void openWith(final Consumer<Path> handler) {
        opener = handler;
    }

    /**
     * Forgets whatever was opening folders, so a test cannot decide what the next one does.
     */
    static void clear() {
        opener = null;
    }

    /**
     * Opens one folder, or does nothing when nothing is set to open it.
     *
     * @param folder {@link Path} the folder to open
     */
    static void open(final Path folder) {
        final Consumer<Path> handler = opener;
        if (handler != null) {
            handler.accept(folder);
        }
    }

    /**
     * Hands one folder to whatever the operating system browses files with.
     *
     * <p>{@link Desktop} rather than the host services' {@code showDocument}, which takes an
     * address and hands a {@code file:} URI to whatever is registered for one. On Windows that is
     * the web browser. This one names no command per platform and opens whatever the reader
     * actually browses files with.
     *
     * <p>A machine with no display reports no desktop, and the press is then spent in silence.
     *
     * <p>Blocking, so a caller runs it off the thread that paints.
     *
     * <p>Every failure it can meet is one the reader can do nothing about, and a folder that has
     * gone since its card was drawn is the likeliest. So it reports nothing and the press is spent.
     *
     * @param folder {@link Path} the folder to open
     */
    static void openInSystemFileManager(final Path folder) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            log.info("This machine has no desktop to open {} with", folder);
            return;
        }
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (final IOException | IllegalArgumentException | UnsupportedOperationException e) {
            log.info("Could not open {}", folder, e);
        }
    }
}
