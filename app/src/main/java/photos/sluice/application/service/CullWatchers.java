package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Owns the watch lifecycle for waiting cull jobs: which prep dirs currently have a
 * {@link CullWatcher} polling them, and the arm/disarm/auto-resume rules around that. Pulled out of
 * {@link CullEngine} once that class grew a second job alongside prep/dispatch/apply orchestration -
 * see the phase doc for the chunk that made this split. Not a Spring bean; {@link CullEngine} owns
 * the one instance it needs.
 */
final class CullWatchers {

    private static final Logger log = LoggerFactory.getLogger(CullWatchers.class);

    private final CullSettings cullSettings;
    private final ShardTallyCalculator shardTallyCalculator;
    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. Pipeline's own package-private
    // constructor overload is what lets a test override this.
    private final Duration watchPollInterval;
    private final Function<Path, JobHandle<CullJobOutcome>> resume;
    private final Map<Path, CullWatcher> activeWatches = new ConcurrentHashMap<>();

    /**
     * Creates the watch lifecycle owner.
     *
     * @param cullSettings {@link CullSettings} configured provider and watch-mode settings
     * @param shardTallyCalculator {@link ShardTallyCalculator} the readiness check a watcher polls
     * @param watchPollInterval {@link Duration} how often a watcher re-checks its prep dir
     * @param resume a {@link Function} of {@link Path} to {@link JobHandle} of {@link CullJobOutcome}
     *         the route back to {@link CullEngine#resume} a ready watcher's auto-resume attempt uses
     */
    CullWatchers(final CullSettings cullSettings, final ShardTallyCalculator shardTallyCalculator,
                 final Duration watchPollInterval, final Function<Path, JobHandle<CullJobOutcome>> resume) {
        this.cullSettings = cullSettings;
        this.shardTallyCalculator = shardTallyCalculator;
        this.watchPollInterval = watchPollInterval;
        this.resume = resume;
    }

    /**
     * Test seam: whether a watcher is currently polling prepDir. Lets a test prove disarmWatch()'s
     * own claim - that any dispatchAndApply() call retires an existing watcher, not just the
     * watcher's own auto-resume trigger. No need to reach into the private activeWatches map.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean whether a watcher is currently active for it
     */
    boolean isWatchActive(final Path prepDir) {
        final CullWatcher watcher = this.activeWatches.get(prepDir);
        return watcher != null && watcher.isActive();
    }

    /**
     * Starts polling a prep dir for an auto-resume whatever the configured mode says, so one run's
     * watch can be turned on by itself. That is what the waiting card's own "auto-apply when shards
     * arrive" toggle switches, with disarmWatch() as its off position. A no-op when a watcher is
     * already active for that prep dir, rather than a competing second poller.
     *
     * <p>The provider check stays even here, where the user asked for this explicitly. Watch mode
     * exists to notice when the user's own separate culling agent, running outside this app, drops
     * a shard. An automated provider's shards never arrive that way, so its waiting run has nothing
     * to notice, and a run that already looks ready would only trigger an unasked-for, API-spending
     * resume. The toggle is absent from an automated provider's card for the same reason.
     *
     * @param prepDir {@link Path} the prep dir to watch
     */
    void armWatch(final Path prepDir) {
        if (!this.cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
            return;
        }
        this.activeWatches.compute(prepDir, (_, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            final var watcher = new CullWatcher(this.watchPollInterval,
                    () -> this.shardTallyCalculator.isReadyToResume(prepDir), () -> this.tryAutoResume(prepDir));
            watcher.start();
            return watcher;
        });
    }

    /**
     * armWatch() for a run nobody has decided about yet, so the configured mode picks. Every
     * automatic arming site goes through here: the startup scan and dispatchAndApply()'s own
     * Waiting branch. A per-run toggle calls armWatch() directly instead, which is the whole
     * difference between the two.
     *
     * @param job {@link WaitingCullJob} the waiting job to watch
     */
    void armWatchIfConfigured(final WaitingCullJob job) {
        if (this.cullSettings.externalAgent().mode() != WatchMode.WATCH) {
            return;
        }
        this.armWatch(job.prepDir());
    }

    /**
     * Stops and removes the active watcher for a prep dir, if one exists. Giving up on a
     * still-waiting job must stop it from ever auto-resuming a prep dir that's about to be filed
     * into the graveyard, so this is package-private rather than private: dispatchAndApply()'s own
     * call site isn't the only place that needs to retire a watcher. It is also armWatch()'s
     * opposite, and so the off position of the waiting card's own per-run watch toggle. Turning
     * that off leaves the run exactly as it is: still Waiting, still listed, still blocking a
     * re-cull of its scope.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     */
    void disarmWatch(final Path prepDir) {
        final CullWatcher watcher = this.activeWatches.remove(prepDir);
        if (watcher != null) {
            watcher.stop();
        }
    }

    /**
     * The heavier action a CullWatcher runs at most once it thinks isReadyToResume(). Returns whether
     * it actually got to run. True means resume's own jobRunner.submit() succeeded - the watcher's
     * job is then done, win or lose (see CullEngine's own dispatchAndApply() re-arm-on-Waiting note).
     * False means the job runner was busy with something else, so the watcher keeps polling and
     * retries. The submitted job runs and completes fully asynchronously; nothing here waits on it. A
     * failure there would otherwise vanish silently, so it's logged here instead. That matches the
     * visibility a manual Resume gets for free from whatever UI/CLI surfaces its own
     * join()/onComplete() failure.
     *
     * @param prepDir {@link Path} the prep dir to attempt to resume
     * @return boolean whether the resume attempt was actually submitted
     */
    private boolean tryAutoResume(final Path prepDir) {
        final JobHandle<CullJobOutcome> handle;
        try {
            handle = this.resume.apply(prepDir);
        } catch (final IllegalStateException busy) {
            return false;
        }
        handle.onComplete().whenComplete((_, failure) -> {
            if (failure != null) {
                log.warn("Watch-mode auto-resume for {} failed", prepDir, failure);
            }
        });
        return true;
    }
}
