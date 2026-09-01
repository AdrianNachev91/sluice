package photos.sluice.domain.paths;

import java.util.regex.Pattern;

/**
 * The folder names a sort writes for itself under the Review root.
 *
 * <p>Two of them. A dated {@code yyyy-mm} folder holds a photo that has a date and whose file size
 * or resolution is under what a sift looks at. {@code UNDATED} holds one no date chain could
 * place. Every other folder under that root is a category.
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
