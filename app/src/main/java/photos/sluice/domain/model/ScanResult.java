package photos.sluice.domain.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// jsonPaths is every JSON sidecar found in the scanned tree, whether or not it paired to a media
// file. The post-run orphaned-sidecar sweep needs the unpaired ones too, which sidecars() alone
// doesn't expose.
public record ScanResult(List<MediaFile> media, Map<MediaFile, TakeoutSidecar> sidecars, boolean takeoutMode,
        List<Path> jsonPaths) {
    public ScanResult {
        media = List.copyOf(media);
        sidecars = Map.copyOf(sidecars);
        jsonPaths = List.copyOf(jsonPaths);
    }
}
