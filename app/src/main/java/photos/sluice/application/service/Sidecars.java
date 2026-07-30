package photos.sluice.application.service;

import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reading a montage sidecar's own in-scope files, translated to the empty case rather than throwing.
 *
 * <p>Two callers need exactly this. Validation reads every sidecar to learn which files a montage
 * actually showed. An index rebuild reads them to recover the montage list a lost index.json used to
 * hold. Both treat an unreadable sidecar as a reportable condition rather than a crash, so the
 * translation lives in one place instead of being repeated on each side.
 */
final class Sidecars {

    /**
     * Prevents instantiation of this utility class.
     */
    private Sidecars() {
    }

    /**
     * Reads one montage's sidecar and returns the source files it lists.
     *
     * @param cullPrepPort {@link CullPrepPort} reads the sidecar
     * @param prepDirPath {@link Path} the prep directory holding the sidecar
     * @param montage {@link String} the montage whose sidecar to read
     * @return an {@link Optional} {@link List} of {@link Path}, the sidecar's own src files, or empty if unreadable
     */
    static Optional<List<Path>> srcsOf(final CullPrepPort cullPrepPort, final Path prepDirPath, final String montage) {
        try {
            return Optional.of(cullPrepPort.readSidecar(prepDirPath, montage).stream()
                    .map(SidecarPhotoEntry::src)
                    .toList());
        } catch (final UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
