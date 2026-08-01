package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Health checks for a prep dir, plus the purge that clears completed runs. diagnose() is
 * side-effect-free and safe to call any time, before or instead of an apply. It can drive a
 * run-card dashboard as well as a blocked run's troubleshoot screen.
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
        this.shardTallyCalculator = new ShardTallyCalculator(cullPrepPort, cullSettings);
    }

    /**
     * Diagnoses prepDir's current state.
     *
     * <p>COMPLETE once decisions.json exists. WAITING while any montage still lacks a shard.
     * Otherwise READY (nothing blocks apply()) or BLOCKED (findings exist), decided by the merged
     * shard-contract and missing-source checks.
     *
     * <p>Missing-source checking only runs once the shard contract itself is clean. That mirrors
     * apply() itself, which throws on a shard-contract problem before ever reaching its own
     * classify() pass. Running the check regardless would risk a second, misleading finding
     * against a decision already flagged for an unrelated reason. For example, a FileOutOfScope
     * decision is still carried into decisions() unhealed, so its bogus path would also read as a
     * MissingSource once checked against disk.
     *
     * <p>Findings are ordered by repair dependency: AUTO-remedied ones first, then CHOICE, then
     * the informational NONE ones. That lets a troubleshooter walk the list top to bottom. It also
     * means a CHOICE finding never coexists with an AUTO or NONE one in the same report. It only
     * ever surfaces once the shard contract is already clean.
     *
     * <p>The completion check runs before index.json is ever read, and reads no further than
     * whether decisions.json exists - a COMPLETE run needs nothing else. A corrupt or missing
     * index.json past that point reports BLOCKED with a single {@link Finding.CorruptIndex}. With
     * no readable montage list, nothing else here can be computed at all - not the tally, not the
     * shard contract, not missing sources. A read that merely failed - the file exists and is well
     * formed, but couldn't be opened - is not diagnosed at all; it propagates, since a false
     * corruption diagnosis would offer an AUTO rebuild that discards an index that was never broken.
     *
     * @param prepDirPath {@link Path} the prep directory to diagnose
     * @return {@link PrepDirHealth} the prep dir's current state and open findings
     */
    public PrepDirHealth diagnose(final Path prepDirPath) {
        if (this.mediaStore.exists(prepDirPath.resolve(DECISIONS_FILE))) {
            return new PrepDirHealth(State.COMPLETE, List.of());
        }

        final PrepDir prepDir;
        try {
            prepDir = this.cullPrepPort.readIndex(prepDirPath);
        } catch (final MalformedPrepJsonException e) {
            return new PrepDirHealth(State.BLOCKED, List.of(new Finding.CorruptIndex(prepDirPath.resolve(INDEX_FILE))));
        }

        // One snapshot for this whole diagnosis, taken before either planner call below.
        final Ledger ledger = this.ledgerReader.read(prepDirPath);
        final ValidationReport validation = this.applyPlanner.validate(prepDirPath, prepDir, new ApplyOptions(true),
                ledger);
        final ShardTally tally = this.shardTallyCalculator.tally(prepDir);
        // Missing-source checking is skipped here too, for the same reason it's skipped below: the
        // shard contract is still incomplete. A montage still missing its shard tells nothing about
        // whether an already-submitted decision's file is missing.
        if (tally.present() < tally.total()) {
            return new PrepDirHealth(State.WAITING, ordered(validation.findings()));
        }

        if (!validation.valid()) {
            return new PrepDirHealth(State.BLOCKED, ordered(validation.findings()));
        }
        final List<Finding> findings = this.applyPlanner.checkMissingSources(prepDir, validation.decisions(), ledger);
        return findings.isEmpty()
                ? new PrepDirHealth(State.READY, List.of())
                : new PrepDirHealth(State.BLOCKED, ordered(findings));
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
        if (!this.mediaStore.exists(cullPrepRoot)) {
            return new PurgeReport(List.of(), Map.of());
        }
        final List<Path> prepDirs = this.mediaStore.listFiles(cullPrepRoot).stream()
                .filter(file -> file.getFileName().toString().equals(INDEX_FILE))
                .map(Path::getParent)
                .distinct()
                .toList();

        final var purged = new ArrayList<String>();
        final var skipped = new LinkedHashMap<String, State>();
        for (final Path prepDir : prepDirs) {
            final String scope = prepDir.getFileName().toString();
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
