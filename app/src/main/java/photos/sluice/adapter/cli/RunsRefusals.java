package photos.sluice.adapter.cli;

import java.nio.file.Path;

/**
 * The refusal for a sift-prep root that could not be read, shared by everything on this surface
 * that reads one.
 */
final class RunsRefusals {

    /**
     * Told to somebody whose sift-prep root could not be read at all.
     *
     * <p>Ruling Sluice out belongs to this surface alone. A reader here is in a terminal with a
     * window open behind it, so an unnamed holder points them at their own app. Both shapes are
     * named because the command line is Sluice too.
     *
     * <p>The claim is about holding rather than causing. Nothing Sluice does holds this folder,
     * and a working root another Sluice does hold refuses with its own message.
     *
     * <p>A folder that is simply gone answers as a root with no runs, so it never reaches here.
     */
    private static final String UNREADABLE = "Sluice doesn't know what sifts are in %s because it "
            + "cannot be read. No Sluice process is holding it, not the desktop app and not "
            + "another Sluice command. Look for another program, or a drive that isn't reachable.";

    /**
     * Prevents instantiation of this static utility class.
     */
    private RunsRefusals() {}

    /**
     * The refusal for a sift-prep root nobody could read.
     *
     * @param root {@link Path} the folder that could not be listed
     * @return {@link Refusal} the refusal
     */
    static Refusal unreadable(final Path root) {
        return new Refusal(RefusalKind.RUNS_UNREADABLE, UNREADABLE.formatted(root),
                Fields.of("root", root.toString()));
    }
}
