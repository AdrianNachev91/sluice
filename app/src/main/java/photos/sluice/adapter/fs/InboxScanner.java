package photos.sluice.adapter.fs;

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

// Recursively enumerates every regular file under an inbox tree and pairs Takeout JSON sidecars
// against recognized media files. No port/out exists for scanning yet, so this is a plain class
// rather than a Spring-managed adapter.
// Flowcharts + scenario table: app/docs/design/adapter/fs/inbox-scanning.md.
public final class InboxScanner {

    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();
    private final TakeoutSidecarPairer sidecarPairer = new TakeoutSidecarPairer();

    public ScanResult scan(Path inboxRoot) {
        List<Path> mediaPaths = new ArrayList<>();
        List<Path> jsonPaths = new ArrayList<>();
        // Files.walk holds an open directory-traversal resource until the returned stream is
        // closed, so it must stay inside try-with-resources rather than being consumed inline.
        try (Stream<Path> walk = Files.walk(inboxRoot)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                // A file that is neither a JSON sidecar nor a recognized media extension falls
                // through untouched (no else branch) - it never reaches the pairer below and
                // never appears in the scan result.
                if (isJson(path)) {
                    jsonPaths.add(path);
                } else if (mediaTypeDetector.classify(path).isPresent()) {
                    mediaPaths.add(path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan inbox " + inboxRoot, e);
        } catch (UncheckedIOException e) {
            // Files.walk fails synchronously (plain IOException, caught above) only when the root
            // itself can't be opened; a failure partway through traversal (e.g. a subdirectory
            // that becomes unreadable mid-walk) surfaces as this unchecked form instead. Both are
            // rewrapped the same way so a caller sees one consistent, contextual failure either way.
            throw new UncheckedIOException("Failed to scan inbox " + inboxRoot, e.getCause());
        }

        PairingResult pairing = sidecarPairer.pair(mediaPaths, jsonPaths);

        List<MediaFile> media = new ArrayList<>();
        for (Path path : mediaPaths) {
            media.add(new MediaFile(path));
        }
        Map<MediaFile, TakeoutSidecar> sidecars = new LinkedHashMap<>();
        // Pairing runs on raw Paths (TakeoutSidecarPairer's existing contract), so its result is
        // translated into the domain-model MediaFile/TakeoutSidecar wrappers only at the end.
        pairing.sidecarsByMedia().forEach((mediaPath, jsonPath) ->
                sidecars.put(new MediaFile(mediaPath), new TakeoutSidecar(jsonPath)));

        return new ScanResult(media, sidecars, pairing.takeoutMode());
    }

    private static boolean isJson(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }
}
