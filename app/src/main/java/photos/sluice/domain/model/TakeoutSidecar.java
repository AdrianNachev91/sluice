package photos.sluice.domain.model;

import java.nio.file.Path;

/**
 * The Google Takeout JSON file paired to a media file, carrying metadata such as
 * {@code photoTakenTime}. It is a trusted date source and is deleted once consumed by a sort run.
 */
public record TakeoutSidecar(Path jsonPath) {
}
