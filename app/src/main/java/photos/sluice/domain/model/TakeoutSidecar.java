package photos.sluice.domain.model;

import java.nio.file.Path;

/**
 * The Google Takeout JSON file paired to a media file, carrying metadata such as
 * {@code photoTakenTime}. It is a trusted date source. A sort deletes it once no media file left
 * in the Inbox owns it, whichever source actually dated those files.
 */
public record TakeoutSidecar(Path jsonPath) {
}
