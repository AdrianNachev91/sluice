package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.InboxScannerPort;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.ScanResult;
import photos.sluice.domain.model.TakeoutSidecar;
import photos.sluice.domain.scan.MediaTypeDetector;
import photos.sluice.domain.scan.TakeoutSidecarPairer;
import photos.sluice.domain.scan.TakeoutSidecarPairer.PairingResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Recursively enumerates every regular file under an inbox tree and pairs Takeout JSON sidecars
 * against recognized media files.
 *
 * <p>Flowcharts and scenario table: {@code app/docs/design/adapter/fs/inbox-scanning.md}.
 */
@Component
public final class InboxScanner implements InboxScannerPort {

    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();
    private final TakeoutSidecarPairer sidecarPairer = new TakeoutSidecarPairer();

    /**
     * Recursively scans an inbox tree, classifying media files and pairing Takeout JSON sidecars.
     *
     * @param inboxRoot {@link Path} root directory to scan
     * @return {@link ScanResult} the scanned media, paired sidecars, Takeout mode, and JSON paths
     */
    @Override
    public ScanResult scan(final Path inboxRoot) {
        final List<Path> mediaPaths = new ArrayList<>();
        final List<Path> jsonPaths = new ArrayList<>();
        // Files.walk holds an open directory-traversal resource until the returned stream is
        // closed, so it must stay inside try-with-resources rather than being consumed inline.
        try (final Stream<Path> walk = Files.walk(inboxRoot)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                // No else branch. A file that is neither a JSON sidecar nor recognized media falls
                // through untouched, never reaching the pairer and never appearing in the result.
                if (isJson(path)) {
                    jsonPaths.add(path);
                } else if (this.mediaTypeDetector.classify(path).isPresent()) {
                    mediaPaths.add(path);
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to scan inbox " + inboxRoot, e);
        } catch (final UncheckedIOException e) {
            // Files.walk fails synchronously (plain IOException, caught above) only when the root
            // itself can't be opened; a failure partway through traversal (e.g. a subdirectory
            // that becomes unreadable mid-walk) surfaces as this unchecked form instead. Both are
            // rewrapped the same way so a caller sees one consistent, contextual failure either way.
            throw new UncheckedIOException("Failed to scan inbox " + inboxRoot, e.getCause());
        }

        final PairingResult pairing = this.sidecarPairer.pair(mediaPaths, jsonPaths);

        final List<MediaFile> media = mediaPaths.stream().map(MediaFile::new).toList();
        final Map<MediaFile, TakeoutSidecar> sidecars = new LinkedHashMap<>();
        // TakeoutSidecarPairer works in raw Paths, so its result is wrapped into the domain types
        // only here at the end.
        pairing.sidecarsByMedia().forEach((mediaPath, jsonPath) ->
                sidecars.put(new MediaFile(mediaPath), new TakeoutSidecar(jsonPath)));

        return new ScanResult(media, sidecars, pairing.takeoutMode(), jsonPaths);
    }

    /**
     * Checks whether a path is a JSON sidecar file, by extension.
     *
     * @param path {@link Path} file to check
     * @return boolean true if the file name ends with .json
     */
    private static boolean isJson(final Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }
}
