package photos.sluice.domain.model;

import java.util.List;
import java.util.Map;

public record ScanResult(List<MediaFile> media, Map<MediaFile, TakeoutSidecar> sidecars, boolean takeoutMode) {
    public ScanResult {
        media = List.copyOf(media);
        sidecars = Map.copyOf(sidecars);
    }
}
