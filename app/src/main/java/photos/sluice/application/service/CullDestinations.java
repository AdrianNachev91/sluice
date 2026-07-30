package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;

import java.nio.file.Path;

/**
 * Where a culled file ends up. One decision maps to exactly one destination directory, and this is
 * the only class that decides which.
 *
 * <p>Carrying a decision out and reconciling one after the fact must agree on this exactly. An
 * offline reconcile searches the very directory a real apply would have moved the file into. Any
 * disagreement between the two would make an already-moved file look permanently lost. Keeping the
 * mapping in one class is what guarantees they cannot drift.
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
     * The destination directory a decision's file belongs in. Never called for
     * {@link NearDupChosen}: that decision copies rather than moves, so a missing source has no
     * destination worth searching in the first place.
     *
     * @param decision {@link Decision} the decision to resolve a destination directory for
     * @return {@link Path} the destination directory
     */
    Path destinationDirFor(final Decision decision) {
        return switch (decision) {
            case final Classification c -> c.category().equals(FUNNY_CATEGORY)
                    ? this.pathsPort.library().resolve("Funny")
                    : this.pathsPort.review().resolve(c.category());
            case final NearDupReject r -> this.duplicatesDir(r.file(), r.group());
            case NearDupChosen _ -> throw new IllegalStateException(
                    "NearDupChosen has no move destination - a chosen keeper is copied, never moved");
        };
    }

    /**
     * A near-dup group's own folder under Duplicates/.
     *
     * @param file {@link Path} a file in the group, used to derive year-month
     * @param group {@link String} the near-dup group id
     * @return {@link Path} the group's duplicates folder
     */
    Path duplicatesDir(final Path file, final String group) {
        return this.pathsPort.duplicates().resolve(yearMonthOf(file) + "_" + group);
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
        return this.pathsPort.unreviewable().resolve(yearMonth[0]).resolve(yearMonth[1]);
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
}
