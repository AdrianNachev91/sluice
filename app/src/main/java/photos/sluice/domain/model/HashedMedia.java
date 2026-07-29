package photos.sluice.domain.model;

import photos.sluice.domain.dedup.ByteIdenticalDedup;

/**
 * A media file paired with its SHA-256 content hash. A sort run computes this only for the files
 * already narrowed to its in-scope selection. The hash is what {@link ByteIdenticalDedup} compares
 * to find byte-identical duplicates within the batch.
 */
public record HashedMedia(MediaFile file, String sha256) {
}
