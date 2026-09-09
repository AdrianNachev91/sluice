package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * The three folder roots a user configures, exactly as they typed them. Raw strings rather than
 * resolved paths. This is the form written back to the config file, and the form a picker hands
 * over before anything has checked it.
 *
 * <p>All three are nullable. Nothing is configured before a first run, and the app ships no default
 * paths of its own.
 *
 * <p>They travel together because they are edited together, checked against each other, and held
 * still together while a job runs.
 */
public record PathSettings(@Nullable String workingRoot, @Nullable String libraryRoot, @Nullable String inbox) {

    // A root is text before anything resolves it, so the ceiling is on the text rather than on what
    // the filesystem would make of it. Four thousand and ninety-six is the longest path Linux
    // accepts, and it is above what Windows takes even in its extended form. A root also has to
    // leave room for the years, months and filenames hanging beneath it. So anything near this
    // ceiling is already unusable, for reasons this bound is not trying to explain.
    private static final int MAX_ROOT = 4096;

    /**
     * Holds each root to a length, so no surface can store one without end.
     *
     * @param workingRoot the working root as typed, or null when unset
     * @param libraryRoot the library root as typed, or null when unset
     * @param inbox the inbox as typed, or null when unset
     */
    public PathSettings {
        refuseLongerThan(workingRoot, "working root");
        refuseLongerThan(libraryRoot, "library root");
        refuseLongerThan(inbox, "inbox");
    }

    /**
     * The longest a folder root may be.
     *
     * @return int the character ceiling
     */
    public static int maxRoot() {
        return MAX_ROOT;
    }

    /**
     * Refuses a root past the ceiling, naming which one it was.
     *
     * @param value the root as typed, or null
     * @param role {@link String} what to call it in the message
     */
    private static void refuseLongerThan(final @Nullable String value, final String role) {
        if (value != null && value.length() > MAX_ROOT) {
            throw new IllegalArgumentException("The " + role + " is longer than the " + MAX_ROOT
                    + " characters a folder path may take: " + value.length());
        }
    }
}
