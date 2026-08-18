package photos.sluice.adapter.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.ThemeChoice;

/**
 * Which look every window wears, and the one settings value the UI is told rather than asks for.
 *
 * <p>Settings live behind a volatile reference that a save replaces wholesale. That is the right
 * shape for everything reading on demand, and the wrong shape for a window, which cannot poll. So
 * this one value gets a property.
 *
 * <p>It answers with a {@link Theme} rather than a {@link ThemeChoice}, which is what lets a window
 * bind to it at all. {@code adapter/ui/view} may not name anything in {@code application}, and
 * resolving a choice against the desktop is a decision besides, so it happens here.
 *
 * <p>Static, like {@link UiBootstrap} beside it, because the windows reading it are built by static
 * factories and the toolkit constructs the application class itself. There is nothing to inject
 * through.
 *
 * <p>It holds {@link ThemeChoice#SYSTEM} until something sets it. What decides whether a startup
 * failure screen honours a saved choice is therefore whether a context existed to read one from,
 * which varies by failure. A busy working root is raised by the startup sequence, after the context
 * is built, so the saved look is available and worn. A failure that killed the context leaves
 * nothing to read, and the desktop's scheme is all there is.
 */
public final class ThemeSelection {

    private static final ObjectProperty<ThemeChoice> CHOICE = new SimpleObjectProperty<>(ThemeChoice.SYSTEM);

    // Built on first use rather than at class load, because it reads the desktop's preferences and
    // those need a started toolkit. One binding serves the process: every scene attaches a weak
    // listener to this, and this stays alive for as long as the app does.
    private static @Nullable ObservableValue<Theme> effective;

    /**
     * Prevents instantiation of this static holder.
     */
    private ThemeSelection() {
    }

    /**
     * The look to wear right now, watchable so a window restyles when either source changes.
     *
     * <p>Those sources are the saved choice and the desktop's own colour scheme. A choice naming a
     * look wins outright; {@link ThemeChoice#SYSTEM} names none, so the desktop answers.
     *
     * @return an {@link ObservableValue} of {@link Theme} the look in force
     */
    public static synchronized ObservableValue<Theme> effectiveTheme() {
        if (effective == null) {
            final var scheme = Platform.getPreferences().colorSchemeProperty();
            effective = Bindings.createObjectBinding(() -> look(CHOICE.get(), scheme.get()), CHOICE, scheme);
        }
        return effective;
    }

    /**
     * Puts a choice in force, restyling every window already watching.
     *
     * @param choice {@link ThemeChoice} the look the user asked for
     */
    public static void set(final ThemeChoice choice) {
        CHOICE.set(choice);
    }

    /**
     * Puts it back to following the desktop. Here so a harness sharing one process can leave this as
     * it found it, since what is set outlives the window it was set for.
     */
    public static void clear() {
        CHOICE.set(ThemeChoice.SYSTEM);
    }

    /**
     * The look one choice produces against one desktop scheme.
     *
     * <p>Only two of {@link ThemeChoice}'s constants name a look at all, so a third one added later
     * has no answer here until someone decides what it should be. Exhaustiveness makes that a
     * compile error.
     *
     * @param choice {@link ThemeChoice} the look the user asked for
     * @param scheme {@link ColorScheme} the scheme the desktop reports
     * @return {@link Theme} the look to wear
     */
    private static Theme look(final ThemeChoice choice, final ColorScheme scheme) {
        return switch (choice) {
            case SYSTEM -> Theme.matching(scheme);
            case LIGHT -> Theme.LIGHT;
            case DARK -> Theme.DARK;
        };
    }
}
