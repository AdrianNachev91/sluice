package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.CategoryName;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.paths.Containment;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a culled file ends up. One decision maps to exactly one destination directory, and this is
 * the only class that decides which.
 *
 * <p>Carrying a decision out and reconciling one after the fact must agree on this exactly. An
 * offline reconcile searches the very directory a real apply would have moved the file into. Any
 * disagreement between the two would make an already-moved file look permanently lost. Neither
 * {@link ApplyEngine} nor {@link ReconcileEngine} is given a {@link PathsPort} of its own. So
 * neither can resolve the Sorted, library, review, duplicates or unreviewable root except through
 * this class.
 *
 * <p>Sorted is the exception to the opening sentence, being the one root read here rather than
 * written to. {@link #requireUnderSorted} bounds where a run may take a file from, and it lives
 * beside the destination refusals because this is the class that holds the roots.
 */
@Component
public class CullDestinations {

    // The one category with a fixed destination: the library's flat Funny/ folder. It is kept, not
    // set aside for review, so it is hashed into the library index and gets no reason note. The
    // name itself is domain's, because a settings screen has to refuse renaming that card and
    // cannot name anything in this package.
    static final String FUNNY_CATEGORY = CategoryName.LIBRARY_CATEGORY;

    private static final String UNDATED = "0000-00";

    private final PathsPort pathsPort;

    /**
     * Creates a destination resolver over the configured paths.
     *
     * @param pathsPort {@link PathsPort} resolves the Sorted, library, review, duplicates and unreviewable roots
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
     * The month a file was filed under in Sorted, for the note written where it lands next.
     *
     * <p>A month rather than a day, because the path is all there is to read here. What resolved
     * the day is long gone by the time a cull runs.
     *
     * @param file {@link Path} the file about to be moved out of Sorted
     * @return an {@link Optional} {@link YearMonth}, empty where the path names no real month
     */
    Optional<YearMonth> monthFiledUnder(final Path file) {
        try {
            return Optional.of(YearMonth.parse(yearMonthOf(file)));
        } catch (final DateTimeParseException _) {
            return Optional.empty();
        }
    }

    /**
     * Refuses a source file sitting anywhere but inside the Sorted root. This is the last line
     * before a cull touches a file, and the mirror of {@link #under}'s refusal on the destination
     * side. Together they bound what a run relocates at both ends: it moves and copies only out of
     * Sorted, and only into a configured root. What it merely reads is wider, prep-dir state and
     * the library among it.
     *
     * <p>{@code ApplyPlanner} already reports a file outside Sorted as a finding, which aborts the
     * whole run before this can be reached. The check stays because that argument is only as
     * durable as the caller that makes it, and this method cannot notice a future one skipping
     * validation.
     *
     * @param source {@link Path} the file about to be moved or copied
     * @throws IllegalStateException if source does not sit strictly inside the Sorted root
     */
    void requireUnderSorted(final Path source) {
        final Path sorted = this.pathsPort.sorted();
        if (!Containment.strictlyUnder(sorted, source)) {
            throw new IllegalStateException("Refusing to act on a file outside " + sorted + ": " + source);
        }
    }

    /**
     * Refuses a path that does not sit inside the library root, immediately before it is recorded
     * as library content in the hash index.
     *
     * <p>A row in that index means the file is in the library, which is the folder the user's cloud
     * storage syncs and backs up. A row naming anywhere else says a file is safe somewhere nothing
     * is protecting. A later sort then treats a matching Inbox file as redundant and deletes it.
     * The bytes do survive that moment, since the sort only counts a hash whose recorded path still
     * exists. What does not survive is the guarantee: the remaining copy sits outside everything
     * that would preserve it.
     *
     * <p>{@link ApplyEngine} appends to the index at two places. One resolves its destination here,
     * as a fixed folder name under the library root that nothing can steer. The other backfills a
     * row for a move an earlier run made, taking the path from the move-record log on disk. That one
     * is what this guards, and nothing else stands between it and the index.
     *
     * @param dest {@link Path} the path about to be recorded as library content
     * @throws IllegalStateException if dest does not sit strictly inside the library root
     */
    void requireUnderLibrary(final Path dest) {
        final Path library = this.pathsPort.library();
        if (!Containment.strictlyUnder(library, dest)) {
            throw new IllegalStateException("Refusing to record a file outside " + library
                    + " as library content: " + dest);
        }
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
        if (!Containment.strictlyUnder(normalRoot, normalized)) {
            throw new IllegalStateException("Refusing a destination outside " + normalRoot + ": "
                    + String.join(", ", segments) + " resolves to " + normalized);
        }
        return normalized;
    }
}
