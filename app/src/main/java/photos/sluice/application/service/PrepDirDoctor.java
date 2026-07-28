package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.ShardTally;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Side-effect-free health check for a prep dir. Safe to call any time, before or instead of
// apply(). It can drive a run-card dashboard as well as a blocked run's troubleshoot screen.
// ApplyEngine's own validate()/checkMissingSources() are reused verbatim. allowPartial is always
// true here, so a still-culling dir reports on the shards it already has rather than flagging
// every uncalled montage as a finding. That reuse is why a proactive diagnosis and a failed
// apply's own ApplyException always describe the identical set of findings.
@Component
public class PrepDirDoctor {

    private static final String DECISIONS_FILE = "decisions.json";
    private static final String INDEX_FILE = "index.json";

    private final CullPrepPort cullPrepPort;
    private final MediaStore mediaStore;
    private final ApplyEngine applyEngine;
    private final ShardTallyCalculator shardTallyCalculator;

    /**
     * Creates a doctor wired to the same collaborators ApplyEngine and CullEngine already use.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param mediaStore {@link MediaStore} filesystem access for prep dirs
     * @param cullSettings {@link CullSettings} configured cull categories, for the shard tally
     * @param applyEngine {@link ApplyEngine} the merged shard-contract and missing-source checks
     */
    public PrepDirDoctor(CullPrepPort cullPrepPort, MediaStore mediaStore, CullSettings cullSettings,
            ApplyEngine applyEngine) {
        this.cullPrepPort = cullPrepPort;
        this.mediaStore = mediaStore;
        this.applyEngine = applyEngine;
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
     * means a CHOICE finding never coexists with an AUTO or NONE one in the same report - it only
     * ever surfaces once the shard contract is already clean.
     *
     * <p>The completion check runs before index.json is ever read, and reads no further than
     * whether decisions.json exists - a COMPLETE run needs nothing else. A corrupt or missing
     * index.json past that point reports BLOCKED with a single {@link Finding.CorruptIndex}: with
     * no readable montage list, nothing else here (the tally, the shard contract, missing sources)
     * can be computed at all.
     *
     * @param prepDirPath {@link Path} the prep directory to diagnose
     * @return {@link PrepDirHealth} the prep dir's current state and open findings
     */
    public PrepDirHealth diagnose(Path prepDirPath) {
        if (mediaStore.exists(prepDirPath.resolve(DECISIONS_FILE))) {
            return new PrepDirHealth(State.COMPLETE, List.of());
        }

        PrepDir prepDir;
        try {
            prepDir = cullPrepPort.readIndex(prepDirPath);
        } catch (UncheckedIOException e) {
            return new PrepDirHealth(State.BLOCKED, List.of(new Finding.CorruptIndex(prepDirPath.resolve(INDEX_FILE))));
        }

        ValidationReport validation = applyEngine.validate(prepDirPath, prepDir, new ApplyOptions(true));
        ShardTally tally = shardTallyCalculator.tally(prepDir);
        // Missing-source checking is skipped here too, for the same reason it's skipped below: the
        // shard contract is still incomplete. A montage still missing its shard tells nothing about
        // whether an already-submitted decision's file is missing.
        if (tally.present() < tally.total()) {
            return new PrepDirHealth(State.WAITING, ordered(validation.findings()));
        }

        if (!validation.valid()) {
            return new PrepDirHealth(State.BLOCKED, ordered(validation.findings()));
        }
        List<Finding> findings = applyEngine.checkMissingSources(prepDirPath, prepDir, validation.decisions());
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
    public PurgeReport purgeCompleted(Path cullPrepRoot) {
        if (!mediaStore.exists(cullPrepRoot)) {
            return new PurgeReport(List.of(), Map.of());
        }
        List<Path> prepDirs = mediaStore.listFiles(cullPrepRoot).stream()
                .filter(file -> file.getFileName().toString().equals(INDEX_FILE))
                .map(Path::getParent)
                .distinct()
                .toList();

        var purged = new ArrayList<String>();
        var skipped = new LinkedHashMap<String, State>();
        for (Path prepDir : prepDirs) {
            String scope = prepDir.getFileName().toString();
            State state = diagnose(prepDir).state();
            if (state == State.COMPLETE) {
                purgeDir(prepDir);
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
    private void purgeDir(Path prepDir) {
        mediaStore.listFiles(prepDir).forEach(mediaStore::delete);
        mediaStore.removeIfEmptyOfFiles(prepDir);
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
    private static List<Finding> ordered(List<Finding> findings) {
        return findings.stream()
                .sorted(Comparator.comparingInt(finding -> finding.remedy().ordinal()))
                .toList();
    }
}
