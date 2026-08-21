package photos.sluice.application.port.out;

import java.nio.file.Path;

/**
 * The effect boundary application services and adapters use to read the app's configured folder
 * roots, so neither imports the config record that supplies them.
 */
public interface PathsPort {

    /**
     * The working root: the folder Sluice stages everything under, and the one a process claims
     * before it mutates anything inside it.
     *
     * @return {@link Path} the working root directory
     */
    Path repoRoot();

    /**
     * The Inbox root path.
     *
     * @return {@link Path} the Inbox directory
     */
    Path inbox();

    /**
     * The Sorted staging root path.
     *
     * @return {@link Path} the Sorted directory
     */
    Path sorted();

    /**
     * The Review root path.
     *
     * @return {@link Path} the Review directory
     */
    Path review();

    /**
     * The Duplicates root path.
     *
     * @return {@link Path} the Duplicates directory
     */
    Path duplicates();

    /**
     * The unreviewable-files root path.
     *
     * @return {@link Path} the unreviewable directory
     */
    Path unreviewable();

    /**
     * The library root path.
     *
     * @return {@link Path} the library directory
     */
    Path library();

    /**
     * The logs root path.
     *
     * @return {@link Path} the logs directory
     */
    Path logs();

    /**
     * Where cull runs are prepared, one directory per run.
     *
     * @return {@link Path} the sift-prep directory
     */
    Path cullPrep();

    /**
     * Where artifacts nobody could salvage are filed instead of deleted.
     *
     * <p>What is swept from here and what stays is a property of each filed name rather than of the
     * folder. The retention sweep parses a trailing timestamp, so a directory named for a discarded
     * run expires and anything it cannot parse is kept. A caller filing something here decides which
     * it wants by how it names it.
     *
     * @return {@link Path} the graveyard directory
     */
    Path graveyard();
}
