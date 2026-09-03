package photos.sluice.adapter.ui.view;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * How this app hands a folder to the user's own file manager.
 *
 * <p>Held here rather than passed down. Only the desktop launch has anything that can open a folder,
 * and the screen offering it sits several layers below.
 *
 * <p>A screen offering the button is not required to be running inside a launched app. A gallery
 * render and a test both build one, and neither has a file manager to hand. So an unset opener is a
 * state to tolerate rather than refuse.
 */
final class FileManager {

    private static final Logger log = LoggerFactory.getLogger(FileManager.class);

    private static @Nullable Consumer<Path> opener;

    private static @Nullable BiConsumer<Path, Runnable> fileOpener;

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
     * Says what opens a file from now on, and how it reports back where nothing can.
     *
     * @param handler a {@link BiConsumer} of {@link Path} and {@link Runnable} hands the file to
     *     whatever is registered for its type. It runs the second argument where nothing is
     */
    static void openFilesWith(final BiConsumer<Path, Runnable> handler) {
        fileOpener = handler;
    }

    /**
     * Forgets whatever was opening folders and files, so a test cannot decide what the next one
     * does.
     */
    static void clear() {
        opener = null;
        fileOpener = null;
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
     * Opens one file, or does nothing when nothing is set to open one.
     *
     * @param file {@link Path} the file to open
     * @param whenNothingCan {@link Runnable} what to run where the machine has nothing registered
     *     for this kind of file
     */
    static void openFile(final Path file, final Runnable whenNothingCan) {
        final BiConsumer<Path, Runnable> handler = fileOpener;
        if (handler != null) {
            handler.accept(file, whenNothingCan);
        }
    }

    /**
     * Hands one file to whatever the machine has registered for its type, and says whether anything
     * took it.
     *
     * <p>Reported rather than swallowed, unlike a folder. A folder that will not open leaves the
     * reader nothing to do about it. A note file that will not open leaves them the folder it sits
     * in, which the same card already offers.
     *
     * <p>Blocking, so a caller runs it off the thread that paints.
     *
     * @param file {@link Path} the file to open
     * @return boolean true where something opened it
     */
    static boolean inTheRegisteredApplication(final Path file) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            log.info("This machine has no desktop to open the file {} with", file);
            return false;
        }
        try {
            Desktop.getDesktop().open(file.toFile());
            return true;
        } catch (final IOException | IllegalArgumentException | UnsupportedOperationException e) {
            log.info("Could not open {}", file, e);
            return false;
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
    static void inTheSystemFileManager(final Path folder) {
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
