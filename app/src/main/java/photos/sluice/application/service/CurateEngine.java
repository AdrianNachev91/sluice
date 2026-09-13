package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.domain.sift.SiftScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Sorts a scope, then sifts whatever that sort just populated, as one job.
 *
 * <p>No surface offers this, so nothing outside the tests reaches it. It is kept whole and tested
 * against the day one does, rather than deleted and written again from scratch.
 */
final class CurateEngine {

    // Letting each engine announce its own as it is reached would open the job's reporting twice,
    // and the second announcement replaces the first.
    private static final List<String> PHASES =
            Stream.concat(SortEngine.PHASES.stream(), SiftEngine.FRESH_PHASES.stream()).toList();

    private final SortEngine sortEngine;
    private final JobRunner jobRunner;
    private final SiftEngine siftEngine;
    private final PhaseRunner phaseRunner;

    /**
     * Creates the engine, wiring it to the given collaborators.
     *
     * @param sortEngine {@link SortEngine} performs the sort phase
     * @param jobRunner {@link JobRunner} submits the combined sort+sift job
     * @param siftEngine {@link SiftEngine} performs the sift phase
     * @param phaseRunner {@link PhaseRunner} announces what the combined job will report
     */
    CurateEngine(final SortEngine sortEngine, final JobRunner jobRunner,
                 final SiftEngine siftEngine, final PhaseRunner phaseRunner) {
        this.sortEngine = sortEngine;
        this.jobRunner = jobRunner;
        this.siftEngine = siftEngine;
        this.phaseRunner = phaseRunner;
    }

    /**
     * Sort scope, then sift whatever that sort just populated, as one job. Sequential Java calls
     * inside this one JobWork, never two chained submit() calls.
     *
     * <p>The target SiftScope mirrors scope directly wherever that's knowable up front: an explicit
     * Year maps straight across, and OldestN carries the same n through to SiftScope.OldestN. Sort
     * itself is never narrowed to fit sift's shape - it always runs its own normal, complete job.
     *
     * <p>An OldestN sort can still land files across more than one year. Sift's own OldestN ordering
     * is by raw mtime, not resolved date, exactly what a standalone sift() call already does with
     * that scope. Nothing new here.
     *
     * <p>Only OldestYear can't be mapped ahead of time, its year not being decided until the sort
     * itself resolves it. knownSiftScope() returns null for it. The real mapping happens after the
     * sort runs, from SortSummary.yearsSorted().
     *
     * <p>A known target SiftScope gets the same synchronous, pre-submit refuseIfScopeOccupied()
     * sift() gets - failing before the sort even starts. OldestYear can't be checked that early.
     * Its only guard is the same check running again once its year is resolved, after the sort has
     * already moved real files. That failure can't be a plain ScopeOccupiedException like the
     * pre-submit one is - the caller would lose the SortSummary describing what already moved. It
     * raises CurateConflictException, which carries that summary forward.
     *
     * @param scope {@link SortScope} the sort scope to sort and then sift
     * @return a {@link JobHandle} of {@link CurateOutcome} handle for the combined sort+sift job
     */
    JobHandle<CurateOutcome> curate(final SortScope scope) {
        // Before the sort, not only before the sift stage. A curate whose provider cannot
        // authenticate ends in a refusal whatever the sort does. By then it has moved real files
        // for a run that was never going to finish.
        this.siftEngine.refuseIfProviderHasNoCredential();
        final SiftScope known = knownSiftScope(scope);
        if (known != null) {
            this.siftEngine.refuseIfScopeOccupied(known);
        }
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(PHASES);
            final SortSummary sortSummary =
                    this.sortEngine.sort(scope, handle.stopSignal());
            if (handle.isCancellationRequested()) {
                return new CurateOutcome(sortSummary, null);
            }
            final SiftScope siftScope = known != null ? known : oldestYearSiftScope(sortSummary);
            if (siftScope == null) {
                return new CurateOutcome(sortSummary, null);
            }
            // Every occupancy refusal from here on shares one problem. The sort has already moved
            // real files, so a plain ScopeOccupiedException would leave the caller blind to what
            // happened. CurateConflictException carries the SortSummary forward. It keeps the
            // occupying run too, being a subtype of what is caught here, so this path refuses no
            // less usefully than the pre-submit one.
            //
            // Two calls can raise it. An OldestYear scope reaches refuseIfScopeOccupied() below
            // without having been checked before the sort, since its year is not known that early.
            // And buildFreshAndDispatch()'s own claimScope() re-asks for every scope shape, on the
            // job thread. A scope free when curate() was called can be taken while a long sort
            // runs. Only ScopeOccupiedException is caught, so a genuine sift failure downstream
            // (a misconfigured provider, for example) is never mislabeled as a conflict. The type
            // is sealed, permitting only CurateConflictException, which is what makes it safe to
            // wrap the dispatch call rather than the guard alone. No unrelated failure can wear it.
            try {
                if (known == null) {
                    this.siftEngine.refuseIfScopeOccupied(siftScope);
                }
                return new CurateOutcome(sortSummary,
                        this.siftEngine.buildFreshAndDispatch(siftScope, handle.stopSignal()));
            } catch (final Pipeline.ScopeOccupiedException conflict) {
                throw new Pipeline.CurateConflictException(conflict.occupant(), sortSummary);
            }
        });
    }

    /**
     * The SiftScope scope maps to before the sort ever runs. Null only for OldestYear, whose year
     * isn't decided until the sort itself resolves it.
     *
     * @param scope {@link SortScope} the sort scope to map
     * @return {@link SiftScope} the mapped sift scope, or null for OldestYear
     */
    private static @Nullable SiftScope knownSiftScope(final SortScope scope) {
        return switch (scope) {
            case SortScope.Year(final int year, final MonthRange months) ->
                    new SiftScope.Year(year, monthsFromRange(months));
            case SortScope.OldestN(final int n) -> new SiftScope.OldestN(n);
            case SortScope.OldestYear() -> null;
        };
    }

    /**
     * sortSummary.yearsSorted() is the only place an OldestYear scope's resolved year is ever
     * reported. Guaranteed to hold at most one element (see its own doc), so any element found is
     * "the" year. Empty means nothing reached Sorted this run, so there is nothing left to sift.
     *
     * @param sortSummary {@link SortSummary} summary produced by the just-run sort
     * @return {@link SiftScope} the resolved sift scope, or null if nothing to sift
     */
    private static @Nullable SiftScope oldestYearSiftScope(final SortSummary sortSummary) {
        return sortSummary.yearsSorted().stream().findAny()
                .<SiftScope>map(year -> new SiftScope.Year(year, null))
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
