package photos.sluice.domain.model;

import java.nio.file.Path;

public record IndexEntry(String sha256, Path path) {
}
