package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.CategoryName;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a culled file ends up. One decision maps to exactly one destination directory, and this is
 * the only class that decides which.
 *
 * <p>Carrying a decision out and reconciling one after the fact must agree on this exactly. An
 * offline reconcile searches the very directory a real apply would have moved the file into. Any
 * disagreement between the two would make an already-moved file look permanently lost. Neither
 * {@link ApplyEngine} nor {@link ReconcileEngine} is given a {@link PathsPort} of its own. So
 * neither can resolve the library, review, duplicates or unreviewable root except through this
 * class.
 */
@Component
public class CullDestinations {

    // The one category with a fixed destination: the library's flat Funny/ folder. It is kept, not
    // set aside for review, so it is hashed into the library index and gets no reason note.
    static final String FUNNY_CATEGORY = "funny";

    private static final String UNDATED = "0000-00";

    private final PathsPort pathsPort;

    /**
     * Creates a destination resolver over the configured paths.
     *
     * @param pathsPort {@link PathsPort} resolves the library, review, duplicates and unreviewable roots
     */
    public CullDestinations(final PathsPort pathsPort) {
        this.pathsPort = pathsPort;
    }

    /**
     * The destination directory a classification's file belongs in. A near-dup decision has no
     * single-file destination of its own to resolve this way - see {@link #duplicatesDir} and its
     * required anchor.
     *
     * @param decision {@link Classification} the classification to resolve a destination directory for
     * @return {@link Path} the destination directory
     */
    Path destinationDirFor(final Classification decision) {
        return decision.category().equals(FUNNY_CATEGORY)
                ? this.pathsPort.library().resolve("Funny")
                : under(this.pathsPort.review(), decision.category());
    }

    /**
     * A near-dup group's own folder under Duplicates/, keyed by the group's chosen keeper alone -
     * never by the file of the specific member being resolved. A group's members can sit in
     * different Sorted months, and every member, chosen or rejected, must land in the exact same
     * folder. Deriving each member's folder from its own file would split the group across two
     * Duplicates folders instead. Only one of the two would ever get the chosen-filename note.
     * {@link #nearDupAnchors} builds the group-to-keeper map this anchor comes from.
     *
     * @param anchorFile {@link Path} the group's chosen keeper file, used to derive year-month
     * @param group {@link String} the near-dup group id
     * @return {@link Path} the group's duplicates folder
     */
    Path duplicatesDir(final Path anchorFile, final String group) {
        return under(this.pathsPort.duplicates(), yearMonthOf(anchorFile) + "_" + group);
    }

    /**
     * The chosen keeper's file for every near-dup group present in decisions, keyed by group id.
     * The shard contract guarantees exactly one {@link NearDupChosen} per group, so every group with
     * a reject also has an anchor here.
     *
     * @param decisions a {@link List} of {@link Decision} the decisions to scan for near-dup keepers
     * @return a {@link Map} of {@link String} to {@link Path} each group's keeper file, by group id
     */
    static Map<String, Path> nearDupAnchors(final List<Decision> decisions) {
        final Map<String, Path> anchors = new HashMap<>();
        for (final Decision decision : decisions) {
            if (decision instanceof final NearDupChosen c) {
                anchors.put(c.group(), c.file());
            }
        }
        return anchors;
    }

    /**
     * Unlike every other category, which routes flatly to Review/&lt;category&gt;/, an unreviewable
     * file carries no category or reason to group by. So it keeps the &lt;yyyy&gt;/&lt;mm&gt;
     * structure its Sorted location already had.
     *
     * @param file {@link Path} the unreviewable file
     * @return {@link Path} its destination folder under the unreviewable root
     */
    Path unreviewableDir(final Path file) {
        final String[] yearMonth = yearMonthOf(file).split("-", 2);
        return under(this.pathsPort.unreviewable(), yearMonth[0], yearMonth[1]);
    }

    /**
     * The Nth collision candidate for baseName. Slot 1 is the name itself, then " (2)", " (3)", and
     * so on before the extension. Matches the media store's own collision-naming convention exactly.
     *
     * @param baseName {@link String} the original file name
     * @param slot int the 1-based candidate slot
     * @return {@link String} the candidate file name
     */
    static String candidateName(final String baseName, final int slot) {
        if (slot == 1) {
            return baseName;
        }
        final int dot = baseName.lastIndexOf('.');
        final String base = dot <= 0 ? baseName : baseName.substring(0, dot);
        final String extension = dot <= 0 ? "" : baseName.substring(dot);
        return base + " (" + slot + ")" + extension;
    }

    /**
     * A Sorted-relative file always sits under a .../&lt;yyyy&gt;/&lt;MM&gt;/ pair of directories.
     * The segments are read off the path directly rather than pattern-matched from its string form.
     * Matching a string is separator-sensitive across platforms, and unnecessary here, since this
     * app's Sorted layout already guarantees the segments. Falls back to a clearly-undated marker if
     * that guarantee somehow does not hold.
     *
     * @param file {@link Path} the file to derive year-month from
     * @return {@link String} the "yyyy-MM" string, or an undated marker
     */
    private static String yearMonthOf(final Path file) {
        final Path monthDir = file.getParent();
        final Path yearDir = monthDir == null ? null : monthDir.getParent();
        if (yearDir == null) {
            return UNDATED;
        }
        final String month = monthDir.getFileName().toString();
        final String year = yearDir.getFileName().toString();
        return year.matches("\\d{4}") && month.matches("\\d{2}") ? year + "-" + month : UNDATED;
    }

    /**
     * Resolves segments under a root and refuses anything not landing strictly inside it. This is
     * the last line before a move. It is also the only one that sees the resolved path rather than
     * the text it was built from.
     *
     * <p>Every segment reaching here is already constrained upstream. A category is checked by
     * {@link CategoryName} when the prep index is read. A near-dup group id is checked by
     * {@code ShardValidator}'s slug rule, and a year-month by the digit pattern
     * {@link #yearMonthOf} matches. The check here is still uniform across all three rather than
     * argued away per caller. Such an argument is only as durable as the upstream rule it rests
     * on, and this method cannot notice that rule loosening.
     *
     * <p>Two shapes escape a root. A segment carrying a parent reference walks out of it. One the
     * platform reads as absolute replaces it outright. Resolving first and comparing the result
     * catches both without naming either.
     *
     * <p>The root itself is refused too. A segment resolving to nothing at all would move media
     * into the shared root, loose among the category folders rather than in one of them.
     *
     * @param root {@link Path} the configured root the result must sit inside
     * @param segments the path segments to resolve under root, in order
     * @return {@link Path} the resolved directory, normalized
     * @throws IllegalStateException if the segments do not resolve strictly inside root, or if this
     * platform cannot make a path out of one of them
     */
    private static Path under(final Path root, final String... segments) {
        final Path normalRoot = root.normalize();
        Path resolved = normalRoot;
        try {
            for (final String segment : segments) {
                resolved = resolved.resolve(segment);
            }
        } catch (final InvalidPathException e) {
            // Path.resolve's own escape route out of every caller's handling. A name this platform
            // cannot make a path out of never becomes a destination on any platform.
            throw new IllegalStateException("Refusing a destination under " + normalRoot + ": "
                    + String.join(", ", segments) + " is not a usable path", e);
        }
        final Path normalized = resolved.normalize();
        if (normalized.equals(normalRoot)) {
            throw new IllegalStateException("Refusing a destination that is " + normalRoot + " itself: "
                    + String.join(", ", segments) + " names no folder under it");
        }
        if (!normalized.startsWith(normalRoot)) {
            throw new IllegalStateException("Refusing a destination outside " + normalRoot + ": "
                    + String.join(", ", segments) + " resolves to " + normalized);
        }
        return normalized;
    }
}
