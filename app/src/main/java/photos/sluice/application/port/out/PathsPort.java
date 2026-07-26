package photos.sluice.application.port.out;

import java.nio.file.Path;

public interface PathsPort {

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
