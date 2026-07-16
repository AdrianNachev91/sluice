package photos.sluice.application.port.out;

import photos.sluice.domain.model.ScanResult;

import java.nio.file.Path;

public interface InboxScannerPort {

    ScanResult scan(Path inboxRoot);
}
