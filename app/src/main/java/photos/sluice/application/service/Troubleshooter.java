package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.ReconcileReport;
import photos.sluice.domain.cull.TroubleshootReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The single-button recovery: diagnose a prep dir, run every repair this app can perform
 * unprompted today, re-diagnose, then hand back what was found, what was fixed, and what remains.
 * Two AUTO repairs run today, in the locked dependency order (move log before stray shards - until
 * the log is rebuilt, an already-moved file can still look like a stray shard's own missing match).
 * A {@link Finding.MissingSource} finding is currently the only signal available that the
 * move-record log itself might be lost or unreadable. {@link PrepDirDoctor} only ever reports one
 * once the shard contract is already clean. {@link ApplyEngine#reconcile} is exactly the offline
 * repair for that situation. Reconcile never runs unprompted otherwise: it files any existing log
 * away wholesale, which would needlessly demote an already-trustworthy log's witnessed provenance
 * to reconstructed for no benefit. A {@link Finding.StrayShard} finding gets
 * {@link ApplyEngine#autoRepairStrayShard} attempted for it, unprompted, since that repair is
 * provably safe when it runs at all - it either renames the one unambiguous match or does nothing.
 * Every disposition-ledger CHOICE remedy (missing-source skip, overlap resolution, stray-shard
 * set-aside) needs a real user choice, so none of them run here - they surface unchanged in
 * {@code after} for the UI to offer. No confirmation is asked before any AUTO repair: neither one
 * ever moves or deletes anything the library or Sorted tree holds.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/troubleshooter.md}.
 */
@Component
public class Troubleshooter {

    private static final String REPORT_WHAT = "troubleshoot-report";

    private final PrepDirDoctor prepDirDoctor;
    private final ApplyEngine applyEngine;
    private final DisasterDrawer disasterDrawer;

    /**
     * Creates a troubleshooter wired to its collaborators.
     *
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses a prep dir before and after repair
     * @param applyEngine {@link ApplyEngine} runs the offline move-log reconcile and stray-shard repair
     * @param disasterDrawer {@link DisasterDrawer} files the rendered report for support hand-off
     */
    public Troubleshooter(PrepDirDoctor prepDirDoctor, ApplyEngine applyEngine, DisasterDrawer disasterDrawer) {
        this.prepDirDoctor = prepDirDoctor;
        this.applyEngine = applyEngine;
        this.disasterDrawer = disasterDrawer;
    }

    /**
     * Diagnoses prepDir, reconciles its move log when a MissingSource finding suggests it is
     * untrustworthy, attempts an AUTO repair for every StrayShard finding left, re-diagnoses, then
     * files and returns the rendered report.
     *
     * @param prepDir {@link Path} the prep directory to troubleshoot
     * @return {@link TroubleshootReport} what was found, what was fixed, and what remains
     * @throws ApplyException if the shard contract itself does not validate cleanly. Not reachable
     *         from a MissingSource-triggered reconcile call today. PrepDirDoctor only ever reports
     *         MissingSource once the shard contract has already validated clean, and reconcile()
     *         re-runs that identical check. If a later change breaks that invariant and this does
     *         throw, no report gets filed - the exception propagates before render() runs.
     */
    public TroubleshootReport troubleshoot(Path prepDir) throws ApplyException {
        final PrepDirHealth before = prepDirDoctor.diagnose(prepDir);
        final boolean needsReconcile = before.state() == State.BLOCKED
                && before.findings().stream().anyMatch(Finding.MissingSource.class::isInstance);
        final ReconcileReport reconcile = needsReconcile ? applyEngine.reconcile(prepDir) : null;
        final PrepDirHealth afterReconcile = needsReconcile ? prepDirDoctor.diagnose(prepDir) : before;

        final List<String> strayShardsRepaired = repairStrayShards(prepDir, afterReconcile);
        final PrepDirHealth after = strayShardsRepaired.isEmpty() ? afterReconcile : prepDirDoctor.diagnose(prepDir);

        final String text = render(prepDir, before, reconcile, strayShardsRepaired, after);
        disasterDrawer.write(prepDir, REPORT_WHAT, text);
        return new TroubleshootReport(before, reconcile, strayShardsRepaired, after, text);
    }

    /**
     * Attempts autoRepairStrayShard() for every StrayShard finding diagnosis currently reports. A
     * StrayShard finding can surface either while WAITING (other montages still being culled) or
     * BLOCKED (culling finished, something else needs a remedy). Unlike a MissingSource finding,
     * PrepDirDoctor never gates it on the shard contract being otherwise complete - so this repair
     * isn't gated on overall state either. Each attempt re-reads current disk state, so an earlier
     * repair in this same pass can make a later one possible (one candidate montage claimed) or moot
     * (nothing left unclaimed). autoRepairStrayShard() itself decides that per its own unambiguity
     * rule, not this loop.
     *
     * @param prepDir {@link Path} the prep directory being troubleshot
     * @param diagnosis {@link PrepDirHealth} the diagnosis to read StrayShard findings from
     * @return a {@link List} of {@link String} every repair actually made ("shardFile -> montage")
     */
    private List<String> repairStrayShards(Path prepDir, PrepDirHealth diagnosis) {
        var repaired = new ArrayList<String>();
        diagnosis.findings().stream()
                .filter(Finding.StrayShard.class::isInstance)
                .map(Finding.StrayShard.class::cast)
                .forEach(stray -> applyEngine.autoRepairStrayShard(prepDir, stray)
                        .ifPresent(montage -> repaired.add(stray.shardFile() + " -> " + montage)));
        return repaired;
    }

    /**
     * Renders the technical, support-hand-off report text - the same level of detail
     * {@link Finding#describe()} already gives an aggregated {@code ApplyException}, never
     * layman-friendly copy. A UI maps that friendlier language on top of this structured data.
     *
     * @param prepDir {@link Path} the prep directory troubleshot
     * @param before {@link PrepDirHealth} the diagnosis taken before any repair
     * @param reconcile {@link ReconcileReport} the reconcile outcome, or null if none ran
     * @param strayShardsRepaired a {@link List} of {@link String} every stray shard AUTO-renamed into place
     * @param after {@link PrepDirHealth} the diagnosis taken after any repair
     * @return {@link String} the rendered report text
     */
    private static String render(Path prepDir, PrepDirHealth before, @Nullable ReconcileReport reconcile,
            List<String> strayShardsRepaired, PrepDirHealth after) {
        final List<String> lines = new ArrayList<>();
        lines.add("Troubleshoot report for " + prepDir);
        lines.add("Before: " + before.state() + " - " + before.findings().size() + " finding(s)");
        describeAll(before.findings(), lines);
        if (reconcile == null) {
            lines.add("Reconcile: not run - no finding suggested the move log needed rebuilding");
        } else {
            lines.add("Reconcile: " + reconcile.reconstructed() + " reconstructed, " + reconcile.stillPending()
                    + " still pending, " + reconcile.missingSource().size() + " still missing");
        }
        if (strayShardsRepaired.isEmpty()) {
            lines.add("Stray shards: none auto-repaired");
        } else {
            lines.add("Stray shards auto-repaired: " + strayShardsRepaired.size());
            strayShardsRepaired.forEach(repair -> lines.add("  - " + repair));
        }
        lines.add("After: " + after.state() + " - " + after.findings().size() + " finding(s)");
        describeAll(after.findings(), lines);
        return String.join("\n", lines);
    }

    /**
     * Appends one rendered line per finding to lines.
     *
     * @param findings a {@link List} of {@link Finding} the findings to render
     * @param lines a {@link List} of {@link String} the report lines accumulated so far
     */
    private static void describeAll(List<Finding> findings, List<String> lines) {
        findings.forEach(finding -> lines.add("  - " + finding.describe()));
    }
}
