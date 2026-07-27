package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

/**
 * {@code Troubleshooter.troubleshoot()}'s outcome for one prep dir. before is the diagnosis taken
 * first. reconcile is the move-log rebuild it triggered, or null if no finding suggested one was
 * needed. after is the re-diagnosis taken once reconcile ran, equal to before when it didn't. text
 * is the same technical, path-and-hash-level report the disaster drawer files away. It is also what
 * a support hand-off text field would show verbatim - never layman-friendly copy, which stays a
 * UI-layer concern built on top of this structured data.
 */
public record TroubleshootReport(PrepDirHealth before, @Nullable ReconcileReport reconcile, PrepDirHealth after, String text) {
}
