package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;

/**
 * What a command that changes files does before it changes any: claim the working root, then run
 * the housekeeping that reaches inside it. A command that only reads calls none of this, so a
 * status check still answers while the desktop app is open.
 *
 * <p>The order carries the whole point. The sweep deletes outright, so it may not run before this
 * process owns the folder it deletes in.
 *
 * <p>Nothing gives the root back. A one-shot process holds its claim for its whole life, and the
 * kernel drops it when the process ends. That is the property the claim is built on rather than a
 * path left unwritten.
 *
 * <p>Watchers are never armed here, unlike on the desktop. A watcher polls, and there is nothing to
 * poll in for: this process starts a single job and exits when it is done.
 *
 * <p>This claims a root; it does not refuse a command. Nothing here reports whether the claim
 * happened, and nothing checks that a caller asked. What refuses unusable roots is the facade, on
 * every entry point that resolves a path. So a command that changes files reaches them through the
 * facade, and one that found another way to write would run neither claimed nor refused.
 */
@Component
@Profile("cli")
public class MutatingCommandStart {

    private final WorkingRootLock workingRootLock;
    private final PathsPort paths;
    private final Pipeline pipeline;
    private final PathValidationUseCase pathValidation;

    /**
     * Creates the sequence.
     *
     * @param workingRootLock {@link WorkingRootLock} claims the working root for this process
     * @param paths {@link PathsPort} resolves the configured working root
     * @param pipeline {@link Pipeline} the facade carrying the sweep
     * @param pathValidation {@link PathValidationUseCase} says whether there is a folder to claim
     */
    public MutatingCommandStart(final WorkingRootLock workingRootLock, final PathsPort paths,
                                final Pipeline pipeline, final PathValidationUseCase pathValidation) {
        this.workingRootLock = workingRootLock;
        this.paths = paths;
        this.pipeline = pipeline;
        this.pathValidation = pathValidation;
    }

    /**
     * Claims the working root, then sweeps the recovery artifacts whose 30 days are up.
     *
     * <p>Any folder root the app cannot work in stops both, and the command that follows is the one
     * that says so. Whatever it was asked to do is refused on those same violations a moment later,
     * so a claim taken here would be dropped again with no work in between.
     *
     * @throws WorkingRootBusyException if another process holds the working root
     */
    public void claimAndSweep() {
        if (!this.pathValidation.violationsInForce().isEmpty()) {
            return;
        }
        this.workingRootLock.acquire(this.paths.workingRoot());
        this.pipeline.sweepExpiredDisasterDrawers();
    }
}
