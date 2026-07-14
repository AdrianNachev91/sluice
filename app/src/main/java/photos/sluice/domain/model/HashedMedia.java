package photos.sluice.domain.model;

public record HashedMedia(MediaFile file, String sha256) {
}
