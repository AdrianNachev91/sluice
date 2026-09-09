package photos.sluice.domain.paths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How a path below a root is written once it leaves the filesystem.
 */
public final class RelativePaths {

    /**
     * Prevents instantiation of this static utility class.
     */
    private RelativePaths() {
    }

    /**
     * A relative path written the one way, whatever the platform separates with.
     *
     * <p>Such a name reaches a screen, and it is also what a rescue resolves back into a folder. A
     * resolve takes either separator, so the choice is about what a reader sees and about what a
     * pattern matching a path can rely on.
     *
     * @param relative {@link Path} the path below a root
     * @return {@link String} it written with forward slashes
     */
    public static String toForwardSlashes(final Path relative) {
        final List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }
}
