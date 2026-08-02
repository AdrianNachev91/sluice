package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Health checks for a prep dir, plus the purge that clears completed runs. diagnose() is
 * side-effect-free and safe to call any time. It never throws, whatever state the dir is in, so one
 * unreadable run cannot take down a caller reading every other one. runs() diagnoses every prep dir
 * under the cull-prep root. Startup arming reads it today. The run-card dashboard and the
 * unresolved-run banner are the consumers it was shaped for.
 *
 * <p>Totality is not the same as cheapness. One diagnosis reads every sidecar twice, every shard
 * twice, and the move ledger twice. The validate pass and the tally pass each do their own reads,
 * and a ledger read opens two files. A partly-applied run additionally hashes each recorded
 * destination whose source has already gone. A caller refreshing on a timer picks its interval with
 * that in mind.
 *
 * <p>{@link ApplyPlanner}'s own validate()/checkMissingSources() are reused verbatim. That reuse is
 * why a proactive diagnosis and a failed apply's own ApplyException always describe the identical
 * set of findings. The allowPartial flag is always true here, so a still-culling dir reports on the
 * shards it already has rather than flagging every uncalled montage as a finding.
 *
 * <p>Nothing on the diagnosis path can move a file: it reads through the planner, which is the
 * read-only half of applying. It also only ever holds a {@link LedgerReader}, never the
 * write-capable {@link MoveLedger} - diagnosing can take a ledger snapshot, never append one.
 * purgeCompleted() is the one method here that deletes, and it only ever touches a run diagnose()
 * has certified COMPLETE. It never touches media.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/prep-dir-doctor.md}.
 */
@Component
public class PrepDirDoctor {

    private static final Logger log = LoggerFactory.getLogger(PrepDirDoctor.class);

    private static final String DECISIONS_FILE = "decisions.json";
    private static final String INDEX_FILE = "index.json";

    private final CullPrepPort cullPrepPort;
    private final MediaStore mediaStore;
    private final ApplyPlanner applyPlanner;
    private final LedgerReader ledgerReader;
    private final ShardTallyCalculator shardTallyCalculator;

    /**
     * Creates a doctor wired to the same collaborators the apply side already uses.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param mediaStore {@link MediaStore} filesystem access for prep dirs
     * @param cullSettings {@link CullSettings} configured cull categories, for the shard tally
     * @param applyPlanner {@link ApplyPlanner} the merged shard-contract and missing-source checks
     * @param ledgerReader {@link LedgerReader} takes a read-only move-ledger snapshot per diagnosis
     */
    public PrepDirDoctor(final CullPrepPort cullPrepPort, final MediaStore mediaStore, final CullSettings cullSettings,
                         final ApplyPlanner applyPlanner, final LedgerReader ledgerReader) {
        this.cullPrepPort = cullPrepPort;
        this.mediaStore = mediaStore;
        this.applyPlanner = applyPlanner;
        this.ledgerReader = ledgerReader;
        this.shardTallyCalculator = new ShardTallyCalculator(cullPrepPort, cullSettings, applyPlanner, ledgerReader);
    }

    /**
     * Diagnoses prepDir's current state.
     *
     * <p>COMPLETE once decisions.json exists. WAITING while any montage still lacks a shard.
     * Otherwise READY (nothing blocks apply()) or BLOCKED (findings exist), decided by the merged
     * shard-contract and missing-source checks.
     *
     * <p>Missing-source checking only runs once the shard contract itself is clean, mirroring
     * apply(), which throws on a contract problem before reaching its own classify() pass. Running it
     * regardless would add a second, misleading finding against a decision already flagged for an
     * unrelated reason. A FileOutOfScope decision is carried into decisions() unhealed, so its bogus
     * path would also read as a MissingSource once checked against disk.
     *
     * <p>The completion check runs before index.json is ever read, and reads no further than whether
     * decisions.json exists. A corrupt or missing index.json past that point reports BLOCKED with a
     * single {@link Finding.CorruptIndex}. With no readable montage list, nothing else here can be
     * computed at all.
     *
     * <p>An index read that merely failed reports DAMAGED with a single {@link
     * Finding.UnreadablePrepDir} instead. The files exist and are well formed, and opening them is
     * what did not work. Calling that corrupt would matter. {@link Finding.CorruptIndex} carries an
     * AUTO remedy, so it would offer to rebuild an index that was never broken, discarding it on the
     * way. The DAMAGED finding's remedy is NONE, the honest answer when the content is the one thing
     * nobody could look at.
     *
     * <p>Past the index the mapping is narrower than DAMAGED alone. A shard that fails to parse
     * reports {@link Finding.CorruptShard}, and a sidecar that cannot be read reports {@link
     * Finding.CorruptSidecar}. DAMAGED is where anything that would otherwise escape lands, not where
     * every later failure lands. Nothing does escape: {@link #examine}'s catch-all is what holds that
     * contract. An {@link Error} is the one deliberate exception, since a dying JVM is not a
     * diagnosis a prep dir can carry.
     *
     * <p>One transient failure is misreported as damaged content, a known gap rather than a design
     * choice. A sidecar that could not be opened reports {@link Finding.CorruptSidecar}, because the
     * sidecar read catches every {@code UncheckedIOException} rather than narrowing to malformed
     * content the way the shard read does. That remedy is CHOICE and both answers are permanent, so
     * a locked sidecar can cost a user an irreversible decision.
     *
     * @param prepDirPath {@link Path} the prep directory to diagnose
     * @return {@link PrepDirHealth} the prep dir's current state and open findings
     */
    public PrepDirHealth diagnose(final Path prepDirPath) {
        return this.examine(prepDirPath).health();
    }

    /**
     * diagnose()'s whole body, keeping the shard tally it computes on the way rather than throwing
     * it away. {@link #summaryOf} needs both, and recomputing the tally means re-reading every
     * sidecar and every shard in the dir.
     *
     * <p>The tally is absent whenever {@link #read} did not get far enough to compute one. See
     * {@link #summaryOf} for which states those are. Worth stating once here: an absent tally does
     * not mean the montage list was unreadable. A failure anywhere past the index unwinds to the
     * catch below, with the list already read fine.
     *
     * <p>Being a separate method from {@link #read} is what keeps the guard total. This body is
     * nothing but the try, so no statement can sit outside it. Merging the two would put the reading
     * logic and its guard in one body, where a line added before the try, or after the catch, leaves
     * the guard silently.
     *
     * @param prepDirPath {@link Path} the prep directory to examine
     * @return {@link Diagnosis} the health verdict, and the tally if one could be computed
     */
    private Diagnosis examine(final Path prepDirPath) {
        try {
            return this.read(prepDirPath);
        } catch (final RuntimeException e) {
            // Catch-all on purpose. A garbled path field in the move ledger throws
            // InvalidPathException, and an unrecognised disposition token throws
            // IllegalArgumentException. Neither is an I/O type. An enumerated list needs
            // re-enumerating every time a reader downstream grows a new escape, and a miss costs
            // the app's own startup.
            //
            // Logged rather than swallowed, so a genuine bug reaching here stays visible instead of
            // reading as an ordinary damaged dir.
            log.warn("Could not diagnose {}, reporting it as damaged", prepDirPath, e);
            return new Diagnosis(new PrepDirHealth(State.DAMAGED,
                    List.of(new Finding.UnreadablePrepDir(prepDirPath))), null);
        }
    }

    /**
     * The reading half of a diagnosis, free to let any failed read propagate to {@link #examine}.
     *
     * @param prepDirPath {@link Path} the prep directory to read
     * @return {@link Diagnosis} the health verdict, and the tally if one could be computed
     */
    private Diagnosis read(final Path prepDirPath) {
        if (this.mediaStore.exists(prepDirPath.resolve(DECISIONS_FILE))) {
            return new Diagnosis(new PrepDirHealth(State.COMPLETE, List.of()), null);
        }

        final PrepDir prepDir;
        try {
            prepDir = this.cullPrepPort.readIndex(prepDirPath);
        } catch (final MalformedPrepJsonException e) {
            return new Diagnosis(new PrepDirHealth(State.BLOCKED,
                    List.of(new Finding.CorruptIndex(prepDirPath.resolve(INDEX_FILE)))), null);
        }

        // One snapshot for both planner calls below. The tally takes its own, so a concurrent write
        // between the two can leave the tally reading a different ledger state than the findings.
        // Display-only: nothing acts on the tally, and the findings are what apply is gated on.
        final Ledger ledger = this.ledgerReader.read(prepDirPath);
        final ValidationReport validation = this.applyPlanner.validate(prepDirPath, prepDir, new ApplyOptions(true),
                ledger);
        final ShardTally tally = this.shardTallyCalculator.tally(prepDir);
        // Missing-source checking is skipped here too, for the same reason it's skipped below: the
        // shard contract is still incomplete. A montage still missing its shard tells nothing about
        // whether an already-submitted decision's file is missing.
        if (tally.present() < tally.total()) {
            return new Diagnosis(new PrepDirHealth(State.WAITING, ordered(validation.findings())), tally);
        }

        if (!validation.valid()) {
            return new Diagnosis(new PrepDirHealth(State.BLOCKED, ordered(validation.findings())), tally);
        }
        final List<Finding> findings = this.applyPlanner.checkMissingSources(prepDir, validation.decisions(), ledger);
        return findings.isEmpty()
                ? new Diagnosis(new PrepDirHealth(State.READY, List.of()), tally)
                : new Diagnosis(new PrepDirHealth(State.BLOCKED, ordered(findings)), tally);
    }

    /**
     * One examination's two results.
     *
     * @param health {@link PrepDirHealth} the state and open findings
     * @param shards {@link ShardTally} the shard counts, or null when the read never reached one
     */
    private record Diagnosis(PrepDirHealth health, @Nullable ShardTally shards) {
    }

    /**
     * Every cull run currently sitting under cullPrepRoot, diagnosed.
     *
     * <p>Enumerating the root is the point, rather than deriving the list from anything a prep dir
     * says about itself. A run whose index cannot be read is the one most in need of attention. A
     * derivation starting from that index would be blind to exactly that run.
     *
     * <p>Ordered by scope so a dashboard's rows hold still between refreshes.
     *
     * @param cullPrepRoot {@link Path} the cull-prep root directory to enumerate
     * @return a {@link List} of {@link CullRunSummary} every run found, diagnosed, ordered by scope
     */
    public List<CullRunSummary> runs(final Path cullPrepRoot) {
        return this.prepDirsUnder(cullPrepRoot).stream().map(this::summaryOf).toList();
    }

    /**
     * One prep dir's diagnosis, with its shard tally and age, as a run card renders it.
     *
     * <p>The tally is null whenever the diagnosis did not get far enough to compute one. Three
     * states reach that: DAMAGED, the {@link Finding.CorruptIndex} form of BLOCKED, and COMPLETE.
     * Check for null rather than deriving it from the state. DAMAGED covers a dir whose montage list
     * was never read as well as one where the list read fine and a later read gave out.
     *
     * <p>Never throws, the same contract {@link #diagnose} carries and held the same way. The mtime
     * read is guarded too, since a prep dir can be purged or discarded between being enumerated and
     * being summarised.
     *
     * @param prepDirPath {@link Path} the prep directory to summarise
     * @return {@link CullRunSummary} that run's scope, diagnosis, tally and age
     */
    public CullRunSummary summaryOf(final Path prepDirPath) {
        final Diagnosis diagnosis = this.examine(prepDirPath);
        return new CullRunSummary(scopeOf(prepDirPath), prepDirPath, diagnosis.health(),
                diagnosis.shards(), this.lastModifiedOrEpoch(prepDirPath));
    }

    /**
     * prepDirPath's own folder name, which is the scope tag it was culled under.
     *
     * <p>Falls back to the whole path for a root directory, which has no name component at all. A
     * root is never a prep dir, but {@link #summaryOf} is public and its never-throws contract has
     * to hold for whatever it is handed.
     *
     * @param prepDirPath {@link Path} the prep directory to name
     * @return {@link String} the folder name, or the whole path when it has none
     */
    private static String scopeOf(final Path prepDirPath) {
        final Path name = prepDirPath.getFileName();
        return name == null ? prepDirPath.toString() : name.toString();
    }

    /**
     * prepDirPath's mtime, or the epoch if it cannot be read.
     *
     * <p>A run card sorts and ages by this, so a wrong value costs a misplaced row. Throwing costs
     * the whole dashboard, which is the worse trade. The epoch reads as "as old as anything", which
     * puts a dir nobody can even stat at the top of a list ordered by neglect. Guarded by a
     * catch-all, for the reason {@link #examine} is.
     *
     * @param prepDirPath {@link Path} the prep directory to check
     * @return {@link Instant} the last-modified instant, or {@link Instant#EPOCH} if unreadable
     */
    private Instant lastModifiedOrEpoch(final Path prepDirPath) {
        try {
            return this.mediaStore.lastModifiedTime(prepDirPath);
        } catch (final RuntimeException e) {
            log.warn("Could not read the mtime of {}, ageing it as the epoch", prepDirPath, e);
            return Instant.EPOCH;
        }
    }

    /**
     * Every prep dir holding at least one file, as an immediate child of cullPrepRoot.
     *
     * <p>Presence of a file is the occupancy test, not presence of index.json. A dir holding shards
     * an agent already produced, but whose index has since been lost, is exactly the run worth
     * refusing to overwrite. Filtering on index.json would make it invisible.
     *
     * <p>The first path segment under the root is the prep dir, never the file's own parent. A prep
     * dir has subdirectories of its own, such as the disaster drawer holding filed-away artifacts.
     * A file inside one of those belongs to the run, not to a run of its own. A loose file lying
     * directly in the root belongs to no run at all and is skipped.
     *
     * <p>A listing that fails outright yields no runs rather than throwing. runs() must not be able
     * to take anything down, and purgeCompleted() sweeping nothing is safer than purgeCompleted()
     * throwing before it starts. A permission denial on one prep dir's own subdirectory is enough to
     * fail the walk, as is a network mount dropping under the root.
     *
     * <p>The guard is a catch-all, for the reason {@link #examine} is, and it wraps the whole body
     * rather than the listing call alone. The existence check reaches the same port, and the mapping
     * below can raise on a path it cannot relativize. Guarding only the call that looks like the
     * risky one is how a total method quietly stops being total.
     *
     * <p>Logged rather than swallowed silently. An empty dashboard and an unreadable one look
     * identical to a user, so the log is the only place the difference exists. purgeCompleted()
     * inherits that blind spot. A sweep that could not list the root reports nothing purged and
     * nothing skipped, which is exactly what an empty root reports.
     *
     * @param cullPrepRoot {@link Path} the cull-prep root directory to enumerate
     * @return a {@link List} of {@link Path} every prep dir found, ordered by name
     */
    private List<Path> prepDirsUnder(final Path cullPrepRoot) {
        try {
            if (!this.mediaStore.exists(cullPrepRoot)) {
                return List.of();
            }
            return this.mediaStore.listFiles(cullPrepRoot).stream()
                    .map(cullPrepRoot::relativize)
                    .filter(relative -> relative.getNameCount() > 1)
                    .map(relative -> cullPrepRoot.resolve(relative.getName(0)))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (final RuntimeException e) {
            log.warn("Could not list {}, reporting no cull runs this pass", cullPrepRoot, e);
            return List.of();
        }
    }

    /**
     * Manual, one-button housekeeping: hard-deletes every prep dir under cullPrepRoot this sweep
     * diagnoses COMPLETE, and reports every other one it looked at alongside the state that kept
     * it. No age-based auto-purge, and no graveyard detour. A completed run holds no image weight
     * worth salvaging - apply()'s own cleanup already dropped the montage/tile images. Letting go
     * of its shards, index.json, move-record log, and any disaster drawer is a decision only the
     * user makes, never a timer.
     *
     * @param cullPrepRoot {@link Path} the cull-prep root directory to sweep
     * @return {@link PurgeReport} every scope purged this sweep, and every scope skipped with its state
     */
    public PurgeReport purgeCompleted(final Path cullPrepRoot) {
        final var purged = new ArrayList<String>();
        final var skipped = new LinkedHashMap<String, State>();
        for (final Path prepDir : this.prepDirsUnder(cullPrepRoot)) {
            final String scope = scopeOf(prepDir);
            final State state = this.diagnose(prepDir).state();
            if (state == State.COMPLETE) {
                this.purgeDir(prepDir);
                purged.add(scope);
            } else {
                skipped.put(scope, state);
            }
        }
        return new PurgeReport(purged, skipped);
    }

    /**
     * Hard-deletes every file under a completed prepDir, then removes the now-empty directory tree.
     *
     * @param prepDir {@link Path} the completed prep directory to delete
     */
    private void purgeDir(final Path prepDir) {
        this.mediaStore.listFiles(prepDir).forEach(this.mediaStore::delete);
        this.mediaStore.removeIfEmptyOfFiles(prepDir);
    }

    /**
     * Orders findings by remedy tier (AUTO, then CHOICE, then NONE), stable within a tier.
     * AUTO-remedied problems are fixed first, since their repair can change what a later finding
     * even means. A stray shard renamed into place, for instance, can turn what looked like a
     * missing montage into a validated one.
     *
     * @param findings a {@link List} of {@link Finding} the findings to order
     * @return a {@link List} of {@link Finding} the same findings, ordered by remedy tier
     */
    private static List<Finding> ordered(final List<Finding> findings) {
        return findings.stream()
                .sorted(Comparator.comparingInt(finding -> finding.remedy().ordinal()))
                .toList();
    }
}
