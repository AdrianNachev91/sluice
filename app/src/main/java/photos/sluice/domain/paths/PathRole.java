package photos.sluice.domain.paths;

/**
 * Which of the three folder roots a value belongs to. Carried by every {@link PathViolation}, so a
 * surface knows which field to mark without reading a message.
 *
 * <p>Identity only. What a root is called in a config file, on a screen, or on a command line is
 * each surface's own word for it, not the rule's.
 */
public enum PathRole {

    /** The working root: the folder Sluice stages everything under. */
    WORKING_ROOT,

    /** The library root: where committed keepers live for good. */
    LIBRARY_ROOT,

    /** The Inbox: where new media arrives to be sorted. */
    INBOX
}
