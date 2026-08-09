package photos.sluice.domain.paths;

import java.nio.file.Path;

/**
 * One reason the configured folder roots cannot be worked in. Typed rather than worded, so each
 * surface says it its own way. A screen marks the field it belongs to. A command line prints a
 * property name a script can match on.
 *
 * <p>Sealed, so a surface that handles every case says so by compiling.
 */
public sealed interface PathViolation {

    /**
     * Nothing is set for this root. The ordinary state of a fresh install, where no folder has been
     * chosen yet.
     *
     * @param role {@link PathRole} the root with no value
     */
    record NotConfigured(PathRole role) implements PathViolation {
    }

    /**
     * The configured text does not name a path this system could ever have. A user typing a folder
     * by hand can produce one, where a picker cannot.
     *
     * @param role {@link PathRole} the root the value belongs to
     * @param value {@link String} the configured text, exactly as it was set
     */
    record NotAPath(PathRole role, String value) implements PathViolation {
    }

    /**
     * The path is well formed but no directory sits there. Covers a folder that was moved or
     * deleted, a typo, and a path naming a regular file.
     *
     * @param role {@link PathRole} the root the value belongs to
     * @param path {@link Path} the configured value, made absolute
     */
    record NotADirectory(PathRole role, Path path) implements PathViolation {
    }

    /**
     * Two roots sit inside each other, or are the same folder. This is the violation that matters
     * most. An overlap is what could let a file be deleted as a redundant duplicate while being the
     * only copy left.
     *
     * <p>The pair is enough to say which rule was broken, so no direction is carried. Two pairs are
     * possible. Library and inbox must not contain each other, either way round. And the inbox must
     * not be, or contain, the working root, though the working root containing the inbox is the
     * documented layout and stays legal. The roles come in declaration order, so one layout always
     * reports the same pair.
     *
     * @param first {@link PathRole} the earlier-declared of the two roots
     * @param second {@link PathRole} the later-declared of the two roots
     */
    record Overlap(PathRole first, PathRole second) implements PathViolation {
    }
}
