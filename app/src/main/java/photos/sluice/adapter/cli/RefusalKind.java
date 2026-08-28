package photos.sluice.adapter.cli;

/**
 * Which refusal a command met. Every one of them exits {@link CommandStatus#REFUSED}, so this is
 * what tells them apart.
 */
public enum RefusalKind {

    /**
     * The configured folder roots cannot be worked in. The detail lists every violation.
     */
    FOLDERS_UNUSABLE,

    /**
     * Another Sluice process holds the working root.
     */
    WORKING_ROOT_BUSY,

    /**
     * A job is already running, so this one was not started.
     */
    JOB_IN_PROGRESS,

    /**
     * The configured provider needs a credential and no tier holds one.
     */
    CREDENTIAL_MISSING,

    /**
     * A credential store could not say what it holds, so no read could be trusted.
     */
    CREDENTIAL_STORE_FAILED,

    /**
     * The config file could not be parsed, so nothing in it was read.
     */
    CONFIG_FILE_UNPARSABLE,

    /**
     * A configured setting holds a value the app refused.
     */
    SETTING_REJECTED,

    /**
     * The sift-prep root could not be read, so what sits under it is unknown.
     */
    RUNS_UNREADABLE,

    /**
     * Nothing in the arguments named which photos to work on.
     */
    SCOPE_MISSING,

    /**
     * The arguments named the photos two ways at once, and the two do not agree.
     */
    SCOPE_CONFLICTING,

    /**
     * A scope argument holds a value that is not the thing it names.
     */
    SCOPE_VALUE_REFUSED,

    /**
     * A verb that narrows a year by a span of months was given a set with a gap in it.
     */
    MONTHS_NOT_A_SPAN,

    /**
     * The address given names no sift on disk.
     */
    RUN_NOT_FOUND
}
