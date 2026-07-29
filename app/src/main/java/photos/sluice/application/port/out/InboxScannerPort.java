package photos.sluice.application.port.out;

import photos.sluice.domain.model.ScanResult;

import java.nio.file.Path;

/**
 * The effect boundary application services use to walk the Inbox tree and report what media it
 * holds, feeding the sort engine's planning without performing any moves itself.
 */
public interface InboxScannerPort {

    /**
     * Scans the Inbox tree and reports what it found.
     *
     * @param inboxRoot {@link Path} the Inbox root directory to scan
     * @return {@link ScanResult} the scan result
     */
    ScanResult scan(Path inboxRoot);
}
