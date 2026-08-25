package photos.sluice.domain.imports;

/**
 * Whether an import leaves the files where it found them.
 */
public enum ImportKind {

    /** Leaves the originals where they are. */
    COPY,

    /** Deletes each original once its bytes are confirmed in the Inbox. */
    MOVE
}
