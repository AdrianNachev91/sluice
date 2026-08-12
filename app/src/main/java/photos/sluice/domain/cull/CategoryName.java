package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.paths.ReservedDeviceNames;

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
        if (ReservedDeviceNames.isReserved(name)) {
            return "is a reserved device name on Windows and cannot become a folder there";
        }
        return null;
    }
}
