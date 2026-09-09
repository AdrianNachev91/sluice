package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.paths.ReservedDeviceNames;
import photos.sluice.domain.paths.SortFolderNames;

import java.util.regex.Pattern;

/**
 * What a classification category may be called. A name is two things at once: the action id a
 * decision carries, and the folder name a culled file is moved into under the Review root. The
 * second is what constrains it, so the rule lives here rather than on any one caller.
 *
 * <p>Lower-case letters and digits in hyphen-joined runs, and nothing else. That is the shape
 * {@link ShardValidator}'s near-dup group ids take, for the same reason. It leaves no separator, no
 * parent reference, and no character any of the three platforms forbids.
 *
 * <p>Ruling out upper case settles two further things. On a case-insensitive filesystem
 * {@code Junk} and {@code junk} would be two distinct categories over one folder. And a vision
 * model that normalises case would emit a name the very prompt it was given would then reject.
 */
public final class CategoryName {

    /**
     * The one category name with a destination of its own: the library's flat Funny/ folder, rather
     * than a folder under the Review root like every other card.
     *
     * <p>One definition, because renaming it routes library keepers into Review instead, silently.
     * Nothing else in the app decides a card's destination.
     */
    public static final String LIBRARY_CATEGORY = "funny";

    private static final Pattern SHAPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    // The same ceiling ShardValidator puts on a near-dup group id, for the same reason. Both become
    // a folder name. One long enough to eat the path budget fails at the filesystem instead, and
    // that message names a path length, never the category behind it.
    private static final int MAX_LENGTH = 24;

    /**
     * Prevents instantiation of this static utility class.
     */
    private CategoryName() {
    }

    /**
     * The longest a name may be. For a surface stating the rule to a user, so its sentence cannot
     * promise a length this class would then refuse.
     *
     * @return int the character ceiling
     */
    public static int maxLength() {
        return MAX_LENGTH;
    }

    /**
     * Why a name cannot be a category, or null when it can. The caller phrases its own exception
     * around the returned clause, which reads as the tail of "category 'x' ...". A clause rather
     * than a boolean, so a refused device name does not report the wrong reason.
     *
     * @param name {@link String} the candidate category name
     * @return {@link String} the reason it is refused, or null when the name is usable
     */
    public static @Nullable String problemWith(final String name) {
        if (!SHAPE.matcher(name).matches()) {
            return "may hold only lower-case letters and digits, in hyphen-joined runs";
        }
        if (name.length() > MAX_LENGTH) {
            return "is longer than the " + MAX_LENGTH + " characters a folder name may take here";
        }
        // The clause names no platform on purpose. Only Windows reserves these names, and a Mac
        // reader gets the same refusal.
        if (ReservedDeviceNames.isReserved(name)) {
            return "is reserved by the operating system and cannot become a folder";
        }
        if (VerdictAction.isVerdictWord(name)) {
            return "is already one of the app's own photo decisions";
        }
        // A sort files into the Review root as well. A category taking one of its two names would
        // be handed a folder already holding photos nothing classified.
        if (SortFolderNames.isSortFolderName(name)) {
            return "names a folder a sort fills by itself";
        }
        return null;
    }
}
