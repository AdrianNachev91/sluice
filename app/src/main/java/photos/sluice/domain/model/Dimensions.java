package photos.sluice.domain.model;

/**
 * The pixel width and height of an image or video frame. Sort logic checks these against a
 * minimum-resolution bar to decide whether a media file is routed to the low-resolution bucket.
 */
public record Dimensions(int width, int height) {
}
