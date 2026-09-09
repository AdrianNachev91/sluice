package photos.sluice.application.port.in;

/**
 * Thrown when something is refused because a job is already running.
 *
 * <p>A settings save that moves a folder root is refused. A sort that starts against one library
 * and finishes against another has filed half its photos in the wrong place.
 * Nothing about the run would say so. A second job is refused outright, since one job at a time is
 * what keeps two engines off the same tree.
 *
 * <p>A screen is expected to hold those fields still, and to disable its own start control, while a
 * job runs. So a user rarely meets this. It is still refused here, because a screen forgetting to is
 * exactly the kind of miss that only shows up as damage.
 *
 * <p>The sentence comes from the caller, since what a user should do about it differs. One says
 * finish the run before moving folders, the other says wait and start again.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class JobInProgressException extends IllegalStateException {

    /**
     * Creates the exception.
     *
     * @param message {@link String} what was refused, and what to do about it
     */
    public JobInProgressException(final String message) {
        super(message);
    }
}
