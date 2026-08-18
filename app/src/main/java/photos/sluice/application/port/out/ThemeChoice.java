package photos.sluice.application.port.out;

/**
 * Which look the user asked for, which is not the same question as which look a window wears.
 *
 * <p>{@link #SYSTEM} names no look at all. It says to follow whatever the desktop reports, so the
 * look it produces changes when the desktop's colour scheme does. The other two override the
 * desktop and hold.
 *
 * <p>Kept apart from the rendering enum in {@code adapter/ui} deliberately. That one has exactly the
 * looks a stylesheet exists for, and a third constant there would be a look with no sheet behind it.
 */
public enum ThemeChoice {

    /** Follow the desktop's own colour scheme. What an install runs on until something names another. */
    SYSTEM,

    /** Wear the light look whatever the desktop reports. */
    LIGHT,

    /** Wear the dark look whatever the desktop reports. */
    DARK
}
