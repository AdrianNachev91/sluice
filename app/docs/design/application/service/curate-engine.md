# Curate engine

How `application/service/CurateEngine` sorts a scope, then culls whatever that sort just populated, as one job
(`app/src/main/java/photos/sluice/application/service/CurateEngine.java`). `Pipeline`
builds the one `CurateEngine` instance it needs, wiring it to the same `CullEngine` it builds for
`cull()`/`resume()` itself - see `pipeline.md` for that facade and `cull-engine.md` for
`buildFreshAndDispatch()`, the cull-stage method this class reuses.

## `curate()`

Sort, then cull whatever that sort just populated - one job, sequential Java calls inside its
`JobWork`. Never two chained `submit()` calls (see `JobHandle`'s own doc on why nothing threads a job's outcome across
separate `submit()` calls).

The target `CullScope` mirrors `scope` directly wherever that's knowable up front. An explicit
`Year` maps straight across. `OldestN` carries the same `n` through to `CullScope.OldestN`. Sort itself is never
narrowed to fit cull's shape - it always runs its own normal, complete job.

An `OldestN` sort can still land files across more than one year. Cull's own `OldestN` ordering is by raw mtime, not
resolved date (see `CullScope`'s own doc) - exactly what a standalone `cull()`
call already does with that scope. Nothing new here.

Only `OldestYear` can't be mapped ahead of time - its year isn't decided until the sort itself resolves it.
`SortSummary.yearsSorted()` exists for exactly this: it's the only place an auto-resolved `OldestYear` scope's actual
year is ever reported.

A known target `CullScope` (`Year` or `OldestN`) gets the same synchronous, pre-submit
`checkNoWaitingJobFor()` `cull()` gets - failing before the sort even starts. `OldestYear` can't be checked that early.
Its only guard is the same check running again once its resolved year is known, after the sort has already moved real
files - narrowly, around just that one call, not the dispatch/apply that follows, so a genuine cull failure downstream
(a misconfigured provider, for example) is never mislabeled as this conflict.

A failure at that later point can't be a plain `IllegalStateException` like the pre-submit one is. The sort already
moved real files by then. A caller must not lose the `SortSummary` describing that just because the cull stage was
refused. `curate()` catches it and rethrows
`Pipeline.CurateConflictException` (an `IllegalStateException` subtype, kept on `Pipeline` itself since that's the
public facade type a caller catches) instead, carrying the `SortSummary` via its own `sortSummary()` accessor. This is
the only failure path that needs to carry a partial result - every other `checkNoWaitingJobFor()` failure happens before
anything runs.

The single stage-boundary check between sort finishing and cull starting still exists, but neither stage is coarse on
its own anymore. `SortEngine`'s own dating and routing passes each check the signal per file (see `sort-engine.md`), and
`CullEngine`'s own `buildFreshAndDispatch()`/
`dispatchAndApply()` boundaries (see `cull-engine.md`'s Cancellation section) close the cull side the same way. A large
sort or a long automated-provider dispatch both respond within about one item's worth of latency, not by waiting out the
whole remaining stage.

```mermaid
flowchart TD
    A["CurateEngine.curate(scope)"] --> K{"CullScope knowable<br/>before the sort runs?<br/>(Year or OldestN: yes;<br/>OldestYear: no)"}
    K -- " yes " --> KW{"a WaitingCullJob<br/>already exists for<br/>that CullScope?"}
    KW -- " yes " --> KZ(["IllegalStateException,<br/>thrown synchronously -<br/>sort never starts"])
    KW -- " no " --> B["JobRunner.submit"]
    K -- " no " --> B
    B --> S["sort: SortEngine.sort<br/>-> SortSummary"]
    S --> C{"cancellation<br/>requested?"}
    C -- " yes " --> CZ(["CurateOutcome(sortSummary, null)<br/>- cull stage skipped"])
    C -- " no " --> R{"CullScope known?<br/>(OldestYear: resolved from<br/>sortSummary.yearsSorted())"}
    R -- " no " --> RZ(["CurateOutcome(sortSummary, null)<br/>- nothing landed in Sorted"])
    R -- " yes, and known == null<br/>(OldestYear) " --> OC{"checkNoWaitingJobFor(cullScope)<br/>again, now that the year is known"}
    OC -- " conflict " --> OCZ(["CurateConflictException,<br/>carries sortSummary -<br/>files already moved"])
    OC -- " no conflict " --> BD["buildFreshAndDispatch(cullScope)"]
    R -- " yes, and known != null<br/>(already checked at K) " --> BD
    BD --> APP(["CurateOutcome(sortSummary, cullOutcome)"])
```

| CurateEngine method | What runs                                                 | Phase label(s)                                                                      |
|---------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------------|
| `curate(SortScope)` | sort -> (cancellation check) -> prep -> dispatch -> apply | `"Sorting..."`, `"Building montages..."`, `"Culling..."`, `"Applying decisions..."` |

### Scenarios

| Scenario                                                                       | Outcome                                                                                                                                   |
|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `scope` is `Year` or `OldestN`, already holding an unresolved `WaitingCullJob` | `IllegalStateException` thrown synchronously, before the sort ever starts                                                                 |
| `scope` is `OldestYear` and its sort routes nothing into Sorted                | `cullOutcome` is `null` - no year was resolved, so a cull-prep dir is never even written                                                  |
| `scope` is `Year` or `OldestN` and this run sorted nothing new under it        | The cull stage still runs over that same scope - files already sitting there from an earlier, uncommitted run get culled                  |
| `scope` is `OldestN` and the sort spans more than one year                     | Sort lands files in every year they resolve to, unrestricted; cull still runs `CullScope.OldestN` with the same `n`, by raw mtime         |
| Cancellation requested between the sort and cull stages                        | The sort still completes in full; `cullOutcome` is `null` - the cull stage never starts                                                   |
| `scope` is `OldestYear` and the sort resolves a year                           | That resolved year is culled - `SortSummary.yearsSorted()` is the only place it's reported                                                |
| `scope` is `OldestYear`, and its resolved year already has a `WaitingCullJob`  | `Pipeline.CurateConflictException` (not a plain `IllegalStateException`) - carries the `SortSummary` for the files the sort already moved |
| Cancellation requested mid-render during curate's cull stage                   | `cullOutcome` is `CullJobOutcome.Cancelled` - same as `cull()` hitting the same point via `buildFreshAndDispatch()`                       |

## Related

- `pipeline.md`: the `Pipeline` facade that builds this class, and the `CurateConflictException`
  type it defines.
- `cull-engine.md`: `buildFreshAndDispatch()`/`checkNoWaitingJobFor()`, both reused directly by this class's cull stage.
- `SortEngine`: `sort-engine.md` in this same design folder.
