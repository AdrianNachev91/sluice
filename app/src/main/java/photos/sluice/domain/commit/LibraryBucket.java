package photos.sluice.domain.commit;

// Classifies a file by the first path segment of its Sorted-relative (equivalently
// library-relative, since both trees share the same top-level layout) location: Photos, Videos,
// or Funny. Used only to tally the commit summary's per-bucket counts. OTHER is a safe fallback
// for a first segment that isn't one of those three. Nothing in the pipeline produces one today,
// but ofFirstSegment must still return some value for every possible input.
public enum LibraryBucket {
    PHOTOS, VIDEOS, FUNNY, OTHER;

    public static LibraryBucket ofFirstSegment(String firstSegment) {
        return switch (firstSegment) {
            case "Photos" -> PHOTOS;
            case "Videos" -> VIDEOS;
            case "Funny" -> FUNNY;
            default -> OTHER;
        };
    }
}
