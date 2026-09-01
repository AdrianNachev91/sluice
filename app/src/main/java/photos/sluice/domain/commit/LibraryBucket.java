package photos.sluice.domain.commit;

import photos.sluice.domain.paths.SortFolderNames;

/**
 * Classifies a file by the first path segment of its Sorted-relative location: Photos, Videos,
 * Funny, or the undated folder a rescue fills. Sorted-relative and library-relative paths are
 * equivalent here, since both trees share the same top-level layout.
 *
 * <p>A tally rather than a decision: nothing branches on the answer. {@code OTHER} is a fallback
 * for a first segment that isn't one of those four. Nothing in the pipeline produces one today,
 * but {@code ofFirstSegment} must still return some value for every possible input.
 */
public enum LibraryBucket {
    PHOTOS, VIDEOS, FUNNY, UNDATED, OTHER;

    /**
     * Classifies a Sorted-relative path's first segment into a library bucket.
     *
     * @param firstSegment {@link String} the first path segment
     * @return {@link LibraryBucket} the matching library bucket, or OTHER if unrecognized
     */
    public static LibraryBucket ofFirstSegment(final String firstSegment) {
        // Whatever its case, matching CommitScopeSelector, which decides whether the same file is
        // in scope at all. A bucket disagreeing with that would tally a moved file as OTHER.
        if (SortFolderNames.UNDATED.equalsIgnoreCase(firstSegment)) {
            return UNDATED;
        }
        return switch (firstSegment) {
            case "Photos" -> PHOTOS;
            case "Videos" -> VIDEOS;
            case "Funny" -> FUNNY;
            default -> OTHER;
        };
    }
}
