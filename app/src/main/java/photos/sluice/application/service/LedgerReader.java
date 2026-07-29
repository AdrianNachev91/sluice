package photos.sluice.application.service;

import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.service.MoveLedger.Ledger;

import java.nio.file.Path;

/**
 * Read-only access to a prep dir's move ledger. {@link MoveLedger} is the sole implementation and
 * adds every append verb on top. A diagnose-only collaborator holds this instead. {@link
 * MediaReader} narrows {@link MediaStore} the same way, on the filesystem side.
 */
public interface LedgerReader {

    /**
     * Parses the whole ledger into one snapshot. See {@link Ledger}'s own Javadoc for why that
     * snapshot is only valid for the run that took it.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger to read
     * @return {@link Ledger} the parsed ledger, empty in every part if no log exists yet
     */
    Ledger read(Path prepDirPath);
}
