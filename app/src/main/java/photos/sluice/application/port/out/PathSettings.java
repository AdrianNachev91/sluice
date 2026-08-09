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
public record PathSettings(@Nullable String repoRoot, @Nullable String libraryRoot, @Nullable String inbox) {
}
