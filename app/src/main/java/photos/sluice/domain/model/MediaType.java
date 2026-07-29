package photos.sluice.domain.model;

/**
 * The two kinds of media the app handles. Sort and rescue logic route a file to the photo or
 * video branch of the destination folder based on this, and the low-resolution gate skips videos
 * entirely.
 */
public enum MediaType {
    PHOTO,
    VIDEO
}
