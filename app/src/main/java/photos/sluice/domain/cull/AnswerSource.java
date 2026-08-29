package photos.sluice.domain.cull;

/**
 * Which surface a user gave an answer through, recorded beside the answer itself.
 *
 * <p>It says where the answer was typed, never who or what typed it. A person can run the command
 * line by hand, so {@code CLI} does not mean a machine answered.
 */
public enum AnswerSource {

    /** The desktop app's own troubleshoot screen. */
    DESKTOP,

    /** The command line. */
    CLI
}
