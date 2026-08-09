package photos.sluice.application.port.in;

/**
 * Thrown when a change is refused because a job is still running.
 *
 * <p>Moving a folder root mid-run is the case that matters. A sort that starts against one library
 * and finishes against another has filed half its photos in the wrong place. Nothing about the run
 * would say so.
 *
 * <p>A screen is expected to hold those fields still while a job runs, so a user never meets this.
 * It is still refused here, because a screen forgetting to is exactly the kind of miss that only
 * shows up as damage.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class JobInProgressException extends IllegalStateException {

    /**
     * Creates the exception.
     */
    public JobInProgressException() {
        super("Sluice is running a job. Finish the current run before changing where its folders are.");
    }
}
