package photos.sluice.domain.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// jsonPaths is every JSON sidecar found in the scanned tree, whether or not it paired to a media
// file. The post-run orphaned-sidecar sweep needs the unpaired ones too, which sidecars() alone
// doesn't expose.
public record ScanResult(List<MediaFile> media, Map<MediaFile, TakeoutSidecar> sidecars, boolean takeoutMode,
        List<Path> jsonPaths) {
    /**
     * Defensively copies the mutable collection components.
     *
     * @param media a {@link List} of {@link MediaFile} media files found in the scanned tree
     * @param sidecars a {@link Map} of {@link MediaFile} to {@link TakeoutSidecar} mapped to its media file
     * @param takeoutMode boolean whether the tree looks like a Takeout export
     * @param jsonPaths a {@link List} of {@link Path} every JSON sidecar path found, paired or not
     */
    public ScanResult {
        media = List.copyOf(media);
        sidecars = Map.copyOf(sidecars);
        jsonPaths = List.copyOf(jsonPaths);
    }
}
