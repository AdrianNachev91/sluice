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
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

// Side-effect-free health check for a prep dir. Safe to call any time, before or instead of
// apply(). It can drive a run-card dashboard as well as a blocked run's troubleshoot screen.
// ApplyEngine's own validate()/checkMissingSources() are reused verbatim. allowPartial is always
// true here, so a still-culling dir reports on the shards it already has rather than flagging
// every uncalled montage as a finding. That reuse is why a proactive diagnosis and a failed
// apply's own ApplyException always describe the identical set of findings.
@Component
public class PrepDirDoctor {

    private static final String DECISIONS_FILE = "decisions.json";

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
     * @param prepDirPath {@link Path} the prep directory to diagnose
     * @return {@link PrepDirHealth} the prep dir's current state and open findings
     */
    public PrepDirHealth diagnose(Path prepDirPath) {
        PrepDir prepDir = cullPrepPort.readIndex(prepDirPath);
        if (mediaStore.exists(prepDirPath.resolve(DECISIONS_FILE))) {
            return new PrepDirHealth(State.COMPLETE, List.of());
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
