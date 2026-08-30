package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns the watch lifecycle for waiting cull jobs: which prep dirs currently have a
 * {@link CullWatcher} polling them, and the arm/disarm/auto-resume rules around that. A separate
 * class from {@link CullEngine}, since the watch lifecycle is an independently-testable concern of
 * its own, alongside prep/dispatch/apply orchestration. Not a Spring bean; {@link CullEngine} owns
 * the one instance it needs.
 */
final class CullWatchers {

    private static final Logger log = LoggerFactory.getLogger(CullWatchers.class);

    private final CullSettings cullSettings;
    private final Predicate<ProviderType> configuredProviderIs;
    private final ShardTallyCalculator shardTallyCalculator;
    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. Pipeline's own package-private
    // constructor overload is what lets a test override this.
    private final Duration watchPollInterval;
    private final Function<Path, JobHandle<CullJobOutcome>> resume;
    private final RunChanges runChanges;
    private final Map<Path, CullWatcher> activeWatches = new ConcurrentHashMap<>();
    // What each watched run's tally read as on the last poll, so a poll can tell a sheet arriving
    // from a folder that has not changed. Written and read on the watcher's own polling thread,
    // one watcher per prep dir. Cleared both when a watch is armed and when one is retired, so a
    // fresh watch never compares against a reading it did not take.
    private final Map<Path, ShardTally> lastSeenTally = new ConcurrentHashMap<>();

    /**
     * Creates the watch lifecycle owner.
     *
     * @param cullSettings {@link CullSettings} configured provider and watch-mode settings
     * @param configuredProviderIs a {@link Predicate} of {@link ProviderType} whether the configured
     *         provider works that way, asked afresh each time because a user can change it between
     *         runs
     * @param shardTallyCalculator {@link ShardTallyCalculator} the readiness check a watcher polls
     * @param watchPollInterval {@link Duration} how often a watcher re-checks its prep dir
     * @param resume a {@link Function} of {@link Path} to {@link JobHandle} of {@link CullJobOutcome}
     *         the route back to {@link CullEngine#resume} a ready watcher's auto-resume attempt uses
     * @param runChanges {@link RunChanges} told whenever a watch arms, disarms, or finishes a run
     */
    CullWatchers(final CullSettings cullSettings, final Predicate<ProviderType> configuredProviderIs,
                 final ShardTallyCalculator shardTallyCalculator,
                 final Duration watchPollInterval, final Function<Path, JobHandle<CullJobOutcome>> resume,
                 final RunChanges runChanges) {
        this.cullSettings = cullSettings;
        this.configuredProviderIs = configuredProviderIs;
        this.shardTallyCalculator = shardTallyCalculator;
        this.watchPollInterval = watchPollInterval;
        this.resume = resume;
        this.runChanges = runChanges;
    }

    /**
     * Whether a watcher is currently polling prepDir, without reaching into the private
     * activeWatches map. A test proves disarmWatch()'s own claim with it: that any
     * dispatchAndApply() call retires an existing watcher, not just the watcher's own auto-resume
     * trigger.
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
     * watch can be turned on by itself. disarmWatch() is its off position. A no-op when a watcher
     * is already active for that prep dir, rather than a competing second poller.
     *
     * <p>The provider check stays even here, where the user asked for this explicitly. Watch mode
     * exists to notice when the user's own separate culling agent, running outside this app, drops
     * a shard. An automated provider's shards never arrive that way, so its waiting run has nothing
     * to notice. A run that already looks ready would only trigger an unasked-for, API-spending
     * resume.
     *
     * @param prepDir {@link Path} the prep dir to watch
     */
    void armWatch(final Path prepDir) {
        if (!this.configuredProviderIs.test(ProviderType.MANUAL)) {
            return;
        }
        // Whether this call is the one that armed it, so a second call landing on a live watcher
        // does not announce a change that never happened.
        final var armed = new AtomicBoolean();
        this.activeWatches.compute(prepDir, (_, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            // Cleared here as well as on retire. The commonest way a watch ends does not go
            // through retire at all: a watcher stops itself once its auto-resume goes in, leaving
            // its last tally behind. Without this, a re-armed watch compares its first poll
            // against the previous watch's reading and announces a change nothing made.
            this.lastSeenTally.remove(prepDir);
            final var watcher = new CullWatcher(this.watchPollInterval,
                    () -> this.pollAndAnnounce(prepDir), () -> this.tryAutoResume(prepDir));
            watcher.start();
            armed.set(true);
            return watcher;
        });
        // Outside compute(), because a listener reads the runs again and a map this call still holds
        // locked is one it could block on.
        if (armed.get()) {
            this.runChanges.moved();
        }
    }

    /**
     * armWatch() for a run nobody has decided about yet, so the configured mode picks. Every
     * automatic arming site goes through here: the startup scan and dispatchAndApply()'s own
     * Waiting branch. A per-run toggle calls armWatch() directly instead, which is the whole
     * difference between the two.
     *
     * @param prepDir {@link Path} the waiting run's prep dir
     */
    void armWatchIfConfigured(final Path prepDir) {
        if (this.cullSettings.externalAgent().mode() != WatchMode.WATCH) {
            return;
        }
        this.armWatch(prepDir);
    }

    /**
     * Stops and removes the active watcher for a prep dir, if one exists. Giving up on a
     * still-waiting job must stop it from ever auto-resuming a prep dir that is about to be filed
     * into the graveyard. So this is package-private rather than private: dispatchAndApply()'s own
     * call site isn't the only place that needs to retire a watcher. It is also armWatch()'s
     * opposite. Retiring one leaves the run exactly as it is: still Waiting, still listed, still
     * blocking a re-cull of its scope.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     */
    void disarmWatch(final Path prepDir) {
        if (this.retire(prepDir)) {
            this.runChanges.moved();
        }
    }

    /**
     * Retires every active watcher. Two callers reach it. A save that moved the working root leaves
     * every watcher polling a prep dir outside the root now in force. An app that is closing leaves
     * them with no process to poll in at all.
     *
     * <p>Announces nothing, unlike retiring one watcher. Neither caller has moved a run, and the
     * one that is closing has just promised that nothing of this app's will touch the folder again.
     * A listener told here would answer by reading the whole runs folder. That read runs on a
     * thread the exit path does not wait for, while the working root is being handed on.
     *
     * <p>Safe to iterate while disarming, since {@link ConcurrentHashMap}'s own key view tolerates
     * removal during traversal. A watcher armed concurrently may or may not be seen. A copy taken
     * up front would simply never see it, so neither shape settles that race, and this one needs no
     * second collection.
     */
    void disarmAll() {
        this.activeWatches.keySet().forEach(this::retire);
    }

    /**
     * Stops one watcher and takes it off the map, saying whether there was one to stop.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     * @return boolean true where a watcher was actually retired
     */
    private boolean retire(final Path prepDir) {
        final CullWatcher watcher = this.activeWatches.remove(prepDir);
        if (watcher == null) {
            return false;
        }
        watcher.stop();
        // After the stop rather than before it. Stopping does not wait for a poll already running,
        // so this cannot close the window entirely, but it does stop a new poll opening one.
        this.lastSeenTally.remove(prepDir);
        return true;
    }

    /**
     * One poll: whether the run is worth resuming, and whether its sheets have moved since the last
     * one.
     *
     * <p>The tally is what a card shows about a run being answered a sheet at a time. Without an
     * announcement here, a reader watching their own agent work sees that line stand still until
     * the run finishes, then jump.
     *
     * <p>The first poll after arming announces nothing, having nothing to compare against.
     * {@link #armWatch} clears the last-seen tally to keep that true across a re-arm.
     *
     * @param prepDir {@link Path} the run being watched
     * @return boolean whether every sheet has an answer that could be read
     */
    private boolean pollAndAnnounce(final Path prepDir) {
        final ShardTallyCalculator.Reading reading = this.shardTallyCalculator.poll(prepDir);
        final ShardTally now = reading.tally();
        if (now == null) {
            return reading.readyToResume();
        }
        final ShardTally before = this.lastSeenTally.put(prepDir, now);
        if (before != null && !before.equals(now)) {
            this.runChanges.moved();
        }
        return reading.readyToResume();
    }

    /**
     * The heavier action a CullWatcher runs at most once it thinks isReadyToResume(). Returns
     * whether this watcher has anything left to do. False means keep polling. True means stop, and
     * three different things produce it.
     *
     * <p>A submitted resume is the ordinary one - the watcher's job is then done, win or lose (see
     * CullEngine's own dispatchAndApply() re-arm-on-Waiting note). The submitted job runs and
     * completes fully asynchronously; nothing here waits on it. A failure there would otherwise
     * vanish silently, so it's logged here instead. That matches the visibility a manual Resume gets
     * for free from whatever UI/CLI surfaces its own join()/onComplete() failure.
     *
     * <p>A refused resume is the other. Unusable folder roots are not a condition that clears by
     * waiting, so polling on would spend a tally read every interval to be refused again. The
     * watcher stops and the run stays exactly as it is, still Waiting and still listed. A manual
     * Resume then surfaces the same refusal on a screen, where it can be acted on.
     *
     * <p>An app that is closing is the third, and it stops for the same reason as a refusal: no
     * amount of polling reopens a shut runner. It logs nothing, because nothing failed. The window
     * is a poll already inside this method when the exit path shuts the runner behind it.
     *
     * <p>Each clause names its exact type, which is what tells them apart. All three are
     * IllegalStateException subtypes. A clause catching that supertype would read a refusal as
     * "runner busy, keep polling". It would then poll for the life of the process, over a condition
     * no poll resolves.
     *
     * <p>Anything else propagates to CullWatcher.poll's own broad catch, which logs it and polls on
     * every tick. No fourth type reaches here today, so nothing does that yet. A refusal added to
     * the resume path later needs a clause of its own here, or it becomes exactly the
     * poll-for-ever loop the naming above exists to prevent.
     *
     * @param prepDir {@link Path} the prep dir to attempt to resume
     * @return boolean false only when a busy job runner makes it worth retrying
     */
    private boolean tryAutoResume(final Path prepDir) {
        final JobHandle<CullJobOutcome> handle;
        try {
            handle = this.resume.apply(prepDir);
        } catch (final PathsMisconfiguredException refused) {
            log.warn("Watch-mode auto-resume for {} was refused and this watcher is stopping", prepDir, refused);
            return true;
        } catch (final ShuttingDownException closing) {
            return true;
        } catch (final JobInProgressException busy) {
            return false;
        }
        handle.onComplete().whenComplete((_, failure) -> {
            if (failure != null) {
                log.warn("Watch-mode auto-resume for {} failed", prepDir, failure);
            }
            // Told here rather than only where the watcher is retired. dispatchAndApply retires it
            // on the way in. An announcement from there reaches a screen before the apply that
            // follows has moved a single file. This one lands once the work is over.
            this.runChanges.moved();
        });
        return true;
    }
}
