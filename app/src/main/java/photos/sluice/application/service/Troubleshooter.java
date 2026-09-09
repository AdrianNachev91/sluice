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
 * unprompted, re-diagnose. Then hand back what was found, what was fixed, and what remains.
 *
 * <p>Three AUTO repairs run today, in the locked dependency order: index before move log before
 * stray shards. Until the index is readable, nothing else can even be diagnosed. Until the log is
 * rebuilt, an already-moved file can still look like a stray shard's own missing match.
 *
 * <p>Every disposition-ledger CHOICE remedy needs a real user choice, so none of them run here.
 * They surface unchanged in {@code after} for a caller to offer.
 *
 * <p>The move records and the answers are separate files so a repair triggered by a lost move
 * record cannot cost the reader an unrelated answer they already gave.
 *
 * <p>No confirmation is asked before any AUTO repair: none of the three ever moves or deletes
 * anything the library or Sorted tree holds.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/troubleshooter.md}.
 */
@Component
public class Troubleshooter {

    private static final String REPORT_LABEL = "troubleshoot-report";

    private final PrepDirDoctor prepDirDoctor;
    private final ReconcileEngine reconcileEngine;
    private final PrepDirRemedies prepDirRemedies;
    private final DisasterDrawer disasterDrawer;

    /**
     * Creates a troubleshooter wired to its collaborators.
     *
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses a prep dir before and after repair
     * @param reconcileEngine {@link ReconcileEngine} runs the offline move-log reconcile
     * @param prepDirRemedies {@link PrepDirRemedies} runs the index rebuild and stray-shard repair
     * @param disasterDrawer {@link DisasterDrawer} files the rendered report for support hand-off
     */
    public Troubleshooter(final PrepDirDoctor prepDirDoctor, final ReconcileEngine reconcileEngine,
                          final PrepDirRemedies prepDirRemedies, final DisasterDrawer disasterDrawer) {
        this.prepDirDoctor = prepDirDoctor;
        this.reconcileEngine = reconcileEngine;
        this.prepDirRemedies = prepDirRemedies;
        this.disasterDrawer = disasterDrawer;
    }

    /**
     * Diagnoses prepDir, attempts an AUTO index rebuild when a CorruptIndex finding is present, and
     * reconciles its move log when a MissingSource finding suggests it is untrustworthy. Then it
     * attempts an AUTO repair for every StrayShard finding left, re-diagnoses, and files and returns
     * the rendered report.
     *
     * @param prepDir {@link Path} the prep directory to troubleshoot
     * @return {@link TroubleshootReport} what was found, what was fixed, and what remains
     * @throws ApplyException if the shard contract itself does not validate cleanly. Not reachable
     *                        from a MissingSource-triggered reconcile today, since PrepDirDoctor
     *                        only reports MissingSource once that contract has validated. If it
     *                        does throw, no report is filed: it propagates before render() runs
     */
    public TroubleshootReport troubleshoot(final Path prepDir) throws ApplyException {
        final PrepDirHealth before = this.prepDirDoctor.diagnose(prepDir);
        final boolean indexRebuilt = before.findings().stream().anyMatch(Finding.CorruptIndex.class::isInstance)
                && this.prepDirRemedies.rebuildIndex(prepDir).isPresent();
        final PrepDirHealth afterIndexRebuild = indexRebuilt ? this.prepDirDoctor.diagnose(prepDir) : before;

        final boolean needsReconcile = afterIndexRebuild.state() == State.BLOCKED
                && afterIndexRebuild.findings().stream().anyMatch(Finding.MissingSource.class::isInstance);
        final ReconcileReport reconcile = needsReconcile ? this.reconcileEngine.reconcile(prepDir) : null;
        final PrepDirHealth afterReconcile = needsReconcile ? this.prepDirDoctor.diagnose(prepDir) : afterIndexRebuild;

        final List<String> strayShardsRepaired = this.repairStrayShards(prepDir, afterReconcile);
        final PrepDirHealth after = strayShardsRepaired.isEmpty() ? afterReconcile :
                this.prepDirDoctor.diagnose(prepDir);

        final String text = render(prepDir, before, indexRebuilt, reconcile, strayShardsRepaired, after);
        this.disasterDrawer.write(prepDir, REPORT_LABEL, text);
        return new TroubleshootReport(before, indexRebuilt, reconcile, strayShardsRepaired, after, text);
    }

    /**
     * Attempts autoRepairStrayShard() for every StrayShard finding diagnosis currently reports. A
     * StrayShard finding can surface either while WAITING (other montages still being culled) or
     * BLOCKED (culling finished, something else needs a remedy). Unlike a MissingSource finding,
     * PrepDirDoctor never gates it on the shard contract being otherwise complete - so this repair
     * isn't gated on overall state either. Each attempt re-reads current disk state. So an earlier
     * repair in this same pass can make a later one possible, or moot. autoRepairStrayShard()
     * decides that per its own unambiguity rule, not this loop.
     *
     * @param prepDir {@link Path} the prep directory being troubleshot
     * @param diagnosis {@link PrepDirHealth} the diagnosis to read StrayShard findings from
     * @return a {@link List} of {@link String} every repair actually made ("shardFile -> montage")
     */
    private List<String> repairStrayShards(final Path prepDir, final PrepDirHealth diagnosis) {
        final var repaired = new ArrayList<String>();
        diagnosis.findings().stream()
                .filter(Finding.StrayShard.class::isInstance)
                .map(Finding.StrayShard.class::cast)
                .forEach(stray -> this.prepDirRemedies.autoRepairStrayShard(prepDir, stray)
                        .ifPresent(montage -> repaired.add(stray.shardFile() + " -> " + montage)));
        return repaired;
    }

    /**
     * Renders the technical, support-hand-off report text - the same level of detail
     * {@link Finding#describe()} already gives an aggregated {@code ApplyException}, never
     * layman-friendly wording.
     *
     * @param prepDir {@link Path} the prep directory troubleshot
     * @param before {@link PrepDirHealth} the diagnosis taken before any repair
     * @param indexRebuilt boolean whether a CorruptIndex finding was AUTO-repaired
     * @param reconcile {@link ReconcileReport} the reconcile outcome, or null if none ran
     * @param strayShardsRepaired a {@link List} of {@link String} every stray shard AUTO-renamed into place
     * @param after {@link PrepDirHealth} the diagnosis taken after any repair
     * @return {@link String} the rendered report text
     */
    private static String render(final Path prepDir, final PrepDirHealth before, final boolean indexRebuilt,
                                 final @Nullable ReconcileReport reconcile, final List<String> strayShardsRepaired,
                                 final PrepDirHealth after) {
        final List<String> lines = new ArrayList<>();
        lines.add("Troubleshoot report for " + prepDir);
        lines.add("Before: " + before.state() + " - " + before.findings().size() + " finding(s)");
        describeAll(before.findings(), lines);
        lines.add(indexRebuilt
                ? "Index: rebuilt from surviving sidecars"
                : "Index: not rebuilt - no CorruptIndex finding, or the rebuild guard refused");
        if (reconcile == null) {
            lines.add("Reconcile: not run - no finding suggested the move log needed rebuilding");
        } else {
            lines.add("Reconcile: " + reconcile.reconstructed() + " reconstructed, " + reconcile.stillPending()
                    + " still pending, " + reconcile.skipped() + " already answered as skipped, "
                    + reconcile.missingSource().size() + " still missing");
            if (reconcile.choicesLost()) {
                lines.add("  - choices.log could not be decoded, and was filed into this drawer. Every answer it "
                        + "held is lost. The findings those answers settled will be raised again.");
            }
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
    private static void describeAll(final List<Finding> findings, final List<String> lines) {
        findings.forEach(finding -> lines.add("  - " + finding.describe()));
    }
}
