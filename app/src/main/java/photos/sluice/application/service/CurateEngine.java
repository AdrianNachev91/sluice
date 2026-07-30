package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Sorts a scope, then culls whatever that sort just populated, as one job. Not a Spring bean.
 * {@link Pipeline} builds the one instance it needs, wiring it to the same {@link CullEngine} it
 * builds for its own {@code cull}/{@code resume}.
 */
final class CurateEngine {

    private static final String SORTING = "Sorting...";

    private final SortEngine sortEngine;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final CullEngine cullEngine;

    /**
     * Creates the engine, wiring it to the given collaborators.
     *
     * @param sortEngine {@link SortEngine} performs the sort phase
     * @param jobRunner {@link JobRunner} submits the combined sort+cull job
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param cullEngine {@link CullEngine} performs the cull phase
     */
    CurateEngine(final SortEngine sortEngine, final JobRunner jobRunner, final ProgressPort progressPort, final CullEngine cullEngine) {
        this.sortEngine = sortEngine;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.cullEngine = cullEngine;
    }

    /**
     * Sort scope, then cull whatever that sort just populated, as one job. Sequential Java calls
     * inside this one JobWork - never two chained submit() calls (JobHandle's own doc explains why
     * no job depends on another's future).
     *
     * <p>The target CullScope mirrors scope directly wherever that's knowable up front: an explicit
     * Year maps straight across, and OldestN carries the same n through to CullScope.OldestN. Sort
     * itself is never narrowed to fit cull's shape - it always runs its own normal, complete job.
     *
     * <p>An OldestN sort can still land files across more than one year. Cull's own OldestN ordering
     * is by raw mtime, not resolved date (see CullScope's own doc) - exactly what a standalone
     * cull() call already does with that scope. Nothing new here.
     *
     * <p>Only OldestYear can't be mapped ahead of time - its year isn't decided until the sort itself
     * resolves it. knownCullScope() returns null for it; the real mapping happens after the sort
     * runs, from SortSummary.yearsSorted().
     *
     * <p>A known target CullScope gets the same synchronous, pre-submit checkNoWaitingJobFor()
     * cull() gets - failing before the sort even starts. OldestYear can't be checked that early.
     * Its only guard is the same check running again once its year is resolved, after the sort has
     * already moved real files. That failure can't be a plain IllegalStateException like the
     * pre-submit one is - the caller would lose the SortSummary describing what already moved. See
     * Pipeline.CurateConflictException's own doc for how that's carried forward instead.
     *
     * <p>isCancellationRequested() is checked here at the sort/cull boundary. It's also checked
     * inside SortEngine's own dating and routing passes via its CancellationSignal overload.
     * The cull stage's own render/dispatch/apply passes check it too, via buildFreshAndDispatch()'s
     * and dispatchAndApply()'s own checks. A large sort or cull responds promptly throughout, not
     * only at this one stage boundary.
     *
     * @param scope {@link SortScope} the sort scope to sort and then cull
     * @return a {@link JobHandle} of {@link CurateOutcome} handle for the combined sort+cull job
     */
    JobHandle<CurateOutcome> curate(final SortScope scope) {
        final CullScope known = knownCullScope(scope);
        if (known != null) {
            this.cullEngine.checkNoWaitingJobFor(known);
        }
        return this.jobRunner.submit(handle -> {
            final SortSummary sortSummary = this.phaseRunner.run(SORTING,
                    progress -> this.sortEngine.sort(scope, progress, handle::isCancellationRequested));
            if (handle.isCancellationRequested()) {
                return new CurateOutcome(sortSummary, null);
            }
            final CullScope cullScope = known != null ? known : oldestYearCullScope(sortSummary);
            if (cullScope == null) {
                return new CurateOutcome(sortSummary, null);
            }
            if (known == null) {
                // Only OldestYear reaches here without having already passed this same check
                // synchronously before the sort ran - the one case that can't be checked that
                // early. Wrapped narrowly around just this call, not the dispatch/apply that
                // follows. That way a genuine cull failure downstream (a misconfigured provider,
                // for example) is never mislabeled as this conflict.
                try {
                    this.cullEngine.checkNoWaitingJobFor(cullScope);
                } catch (final IllegalStateException conflict) {
                    // The sort has already moved real files by this point. CurateConflictException
                    // carries the SortSummary forward so the caller isn't left blind about what
                    // already happened.
                    throw new Pipeline.CurateConflictException(conflict.getMessage(), sortSummary);
                }
            }
            return new CurateOutcome(sortSummary,
                    this.cullEngine.buildFreshAndDispatch(cullScope, handle::isCancellationRequested));
        });
    }

    /**
     * The CullScope scope maps to before the sort ever runs. Null only for OldestYear, whose year
     * isn't decided until the sort itself resolves it.
     *
     * @param scope {@link SortScope} the sort scope to map
     * @return {@link CullScope} the mapped cull scope, or null for OldestYear
     */
    private static @Nullable CullScope knownCullScope(final SortScope scope) {
        return switch (scope) {
            case SortScope.Year(final int year, final MonthRange months) -> new CullScope.Year(year, monthsFromRange(months));
            case SortScope.OldestN(final int n) -> new CullScope.OldestN(n);
            case SortScope.OldestYear() -> null;
        };
    }

    /**
     * sortSummary.yearsSorted() is the only place an OldestYear scope's resolved year is ever
     * reported. Guaranteed to hold at most one element (see its own doc), so any element found is
     * "the" year. Empty means nothing reached Sorted this run, so there is nothing left to cull.
     *
     * @param sortSummary {@link SortSummary} summary produced by the just-run sort
     * @return {@link CullScope} the resolved cull scope, or null if nothing to cull
     */
    private static @Nullable CullScope oldestYearCullScope(final SortSummary sortSummary) {
        return sortSummary.yearsSorted().stream().findAny()
                .<CullScope>map(year -> new CullScope.Year(year, null))
                .orElse(null);
    }

    /**
     * Expands a month range into an explicit list of month numbers.
     *
     * @param months {@link MonthRange} the range to expand, or null for no restriction
     * @return a {@link List} of {@link Integer} the list of months in range, or null if months is null
     */
    private static @Nullable List<Integer> monthsFromRange(final @Nullable MonthRange months) {
        return months == null ? null : IntStream.rangeClosed(months.from(), months.to()).boxed().toList();
    }
}
