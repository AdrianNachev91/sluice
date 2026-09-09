package photos.sluice.domain.paths;

import photos.sluice.domain.model.Numerals;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * The folder names a sort or a rescue writes.
 *
 * <p>Under Sorted, a file goes to its year and then its month, both zero-padded.
 *
 * <p>Under Review, two more. A dated {@code yyyy-mm} folder holds a photo that has a date and whose
 * file size or resolution is under what a sift looks at. {@code UNDATED} holds one no date chain
 * could place. Every other folder under that root is a category.
 */
public final class SortFolderNames {

    /**
     * Where anything nothing could date goes, photo or video alike.
     *
     * <p>The same name under two roots. A sort writes it under Review, and a rescue writes it
     * under Sorted. Sorted is the one place a photo with no date can wait and still be reached by
     * a move to the library.
     */
    public static final String UNDATED = "Unsorted";

    private static final Pattern DATED = Pattern.compile("\\d{4}-\\d{2}");

    /**
     * Prevents instantiation of this static utility class.
     */
    private SortFolderNames() {
    }

    /**
     * The year folder a file taken then belongs in.
     *
     * @param when {@link LocalDateTime} the date the file carries
     * @return {@link String} the four-digit year folder name
     */
    public static String yearFolder(final LocalDateTime when) {
        return Numerals.padded(when.getYear(), 4);
    }

    /**
     * The month folder a file taken then belongs in, under its year.
     *
     * @param when {@link LocalDateTime} the date the file carries
     * @return {@link String} the two-digit month folder name
     */
    public static String monthFolder(final LocalDateTime when) {
        return Numerals.padded(when.getMonthValue(), 2);
    }

    /**
     * Whether a sort would write this name for itself.
     *
     * <p>{@code Unsorted} is matched whatever its case, because Windows and a stock Mac would give
     * a lower-case category the very folder the sort writes.
     *
     * @param name {@link String} a folder name below the Review root
     * @return boolean true where a sort writes that name
     */
    public static boolean writtenByASort(final String name) {
        return DATED.matcher(name).matches() || UNDATED.equalsIgnoreCase(name);
    }
}
