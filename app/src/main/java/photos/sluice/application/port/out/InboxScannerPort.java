package photos.sluice.application.port.out;

import photos.sluice.domain.model.ScanResult;

import java.nio.file.Path;

public interface InboxScannerPort {

    /**
     * Scans the Inbox tree and reports what it found.
     *
     * @param inboxRoot {@link Path} the Inbox root directory to scan
     * @return {@link ScanResult} the scan result
     */
    ScanResult scan(Path inboxRoot);
}
