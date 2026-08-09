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
}
