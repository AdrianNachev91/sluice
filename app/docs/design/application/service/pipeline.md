# Pipeline

How `application/service/Pipeline` wraps `SortEngine`/`CommitEngine`/`RescueEngine` (and, once the
cull/curate flows join it, those too) so a driving caller gets a `JobHandle` back instead of
blocking, with progress reported through `ProgressPort`
(`app/src/main/java/photos/sluice/application/service/Pipeline.java`,
`app/src/main/java/photos/sluice/application/service/JobRunner.java`,
`app/src/main/java/photos/sluice/application/port/out/ProgressPort.java`).

## How one call works

```mermaid
flowchart TD
    A["Pipeline.sort/commit/rescue(...)"] --> B["JobRunner.submit(JobWork)"]
    B -- "a job is already running" --> Z(["IllegalStateException,<br/>thrown synchronously -<br/>nothing started"])
    B -- "slot free" --> C["work runs on a<br/>virtual thread;<br/>JobHandle returned<br/>immediately"]
    C --> D["progressPort.phaseStarted(phase)"]
    D --> E["the matching engine call"]
    E -- "each unit done" --> F["progress.tick(current, total)<br/>-> progressPort.tick(phase, current, total)"]
    F --> E
    E --> G["progressPort.phaseFinished(phase)<br/>- always, even on failure"]
    G --> H{"did the engine call throw?"}
    H -- "no" --> I(["JobHandle.join()<br/>returns the summary"])
    H -- "yes" --> J(["JobHandle.join()<br/>throws CompletionException"])
```

The busy check (`B`) is `Pipeline`'s own guaranteed enforcement of "one job at a time" - what it
means to a given caller depends on that caller's own shape. A caller with a persistent, disable-able
trigger (a desktop UI's own "start" action) can additionally disable it while a job runs, so the
check becomes a backstop there. A caller with no such affordance (a one-shot command-line
invocation) has nothing else standing between two concurrent calls, so the check is that caller's
actual enforcement, not just a backstop. Either way, catching the exception and showing a plain
message is the caller's own job, not `Pipeline`'s.

`phaseFinished` (`G`) fires whether or not the engine call throws. Without that, a job that dies
mid-call would leave a `ProgressPort` listener with a `phaseStarted` event and no matching
`phaseFinished` - the phase would look permanently "in progress" even though the `JobHandle`
itself already reports the failure.

| Pipeline method       | Engine call                             | Phase label       |
|-----------------------|-----------------------------------------|-------------------|
| `sort(SortScope)`     | `SortEngine.sort(scope, progress)`      | `"Sorting..."`    |
| `commit(CommitScope)` | `CommitEngine.commit(scope, progress)`  | `"Committing..."` |
| `rescue(String)`      | `RescueEngine.rescue(folder, progress)` | `"Rescuing..."`   |

`Pipeline` depends on these three engines' concrete classes, not their `SortUseCase`/
`CommitUseCase`/`RescueUseCase` interfaces - the progress-callback overloads only exist on the
concrete classes, not on those narrower interfaces.

## Scenarios

| Scenario                                                         | Outcome                                                                                                                                                                                                                   |
|------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A job is already running when `sort`/`commit`/`rescue` is called | `IllegalStateException` immediately; the running job is unaffected, no new job starts                                                                                                                                     |
| The engine call succeeds                                         | `phaseStarted` -> N ticks -> `phaseFinished`, `JobHandle.join()` returns the engine's summary                                                                                                                             |
| The engine call throws mid-run                                   | `phaseStarted` -> `phaseFinished` still fires -> `JobHandle.join()` throws `CompletionException` wrapping the real cause                                                                                                  |
| Cancellation requested via the returned `JobHandle`              | No effect yet - each of these three calls is a single, non-interruptible engine call with no boundary to check at; becomes meaningful once a multi-stage call (sort followed by a cull) has a boundary between its stages |

## `cull()` / `waitingJobs()` / `resume()`

Three stages chained into one job: prep (`MontageRenderer.build`) -> dispatch
(`CullDispatcher.cull`, which routes to whichever `VisionCuller` the configured provider selects)
-> apply (`ApplyEngine.apply`). The dispatch step is where the flow forks, because a
`CullException` from it means two different things depending on the provider - see
`VisionCuller.MANUAL_MODE_PROVIDER_ID`'s own doc comment for the full reasoning.

```mermaid
flowchart TD
    A["Pipeline.cull(scope)"] --> W{"a WaitingCullJob<br/>already exists for<br/>this scope?"}
    W -- "yes" --> WZ(["IllegalStateException,<br/>thrown synchronously -<br/>nothing rebuilt"])
    W -- "no" --> B["JobRunner.submit"]
    B --> P["prep: MontageRenderer.build<br/>-> PrepDir"]
    P --> D["dispatch: CullDispatcher.cull<br/>(allowPartial=false on a fresh cull,<br/>caller-supplied on resume)"]
    D -- "success" --> AP["apply: ApplyEngine.apply"]
    AP --> APP(["CullJobOutcome.Applied"])
    D -- "CullException" --> M{"configured provider ==<br/>MANUAL_MODE_PROVIDER_ID?"}
    M -- "yes" --> WT(["CullJobOutcome.Waiting<br/>- slot released, not a failure"])
    M -- "no" --> RT(["propagates -<br/>JobHandle.join() throws"])
```

`resume(prepDir, allowPartial)` re-enters at the dispatch step directly - `cullPrepPort.readIndex()`
re-reads the existing `PrepDir` from `index.json` instead of `MontageRenderer` regenerating it, so
no montage is ever rebuilt or re-rendered by a resume. `waitingJobs()` is a plain, un-jobbed read:
it scans `logs/cull-prep/*/` for a prep dir with `index.json` but no merged `decisions.json` yet
(see `WaitingCullJob`'s own doc for why this is derived live instead of a persisted list),
tolerating a transiently-unreadable `index.json` (a concurrent job's own prep dir mid-clear/
mid-write) by skipping that entry rather than failing the whole scan.

`Pipeline` computes each `WaitingCullJob`'s `ShardTally` (`present`/`valid`/`total`) itself, one
montage at a time via `ShardValidator`, rather than reusing `ApplyEngine`'s whole-batch
`validate()`. A cross-shard problem (a near-dup group id reused across two montages) therefore
doesn't show up in the tally - an accepted simplification for a progress number.
`ApplyEngine.apply()`'s own full-batch validation is still the actual gate before anything moves.

| Pipeline method                 | What runs                                                           | Phase label(s)                                                      |
|---------------------------------|---------------------------------------------------------------------|---------------------------------------------------------------------|
| `cull(CullScope)`               | prep -> dispatch -> (apply if complete)                             | `"Building montages..."`, `"Culling..."`, `"Applying decisions..."` |
| `waitingJobs()`                 | a plain disk scan, no `JobRunner` involved                          | none                                                                |
| `resume(Path prepDir, boolean)` | dispatch (re-reading the existing `PrepDir`) -> (apply if complete) | `"Culling..."`, `"Applying decisions..."`                           |

### Scenarios

| Scenario                                                                | Outcome                                                                                                                                              |
|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cull()` on a scope with no shards dropped yet (fresh manual-mode prep) | `CullJobOutcome.Waiting` with a `present=0/valid=0` tally; job slot released                                                                         |
| `cull()` called again while that scope's `WaitingCullJob` is unresolved | `IllegalStateException` thrown synchronously, before `JobRunner.submit()` - the existing prep dir (and any already-dropped shards) is left untouched |
| `resume()` once every shard is present and valid                        | `CullJobOutcome.Applied`, files moved                                                                                                                |
| `resume()` while a shard is still missing/invalid                       | `CullJobOutcome.Waiting` again, with a freshly recomputed tally                                                                                      |
| `resume(prepDir, allowPartial=true)` with a shard still missing         | Applies what it has; the missing montage's photos are left in place, untouched                                                                       |
| `CullException` from an automated (non-manual-mode) provider            | Propagates - `JobHandle.join()` throws, never resolves to `Waiting`                                                                                  |

## Watch mode

`cull.externalAgent.mode: watch` (`ExternalAgentSettings`, `WatchMode`) makes every `Waiting`
outcome from `dispatchAndApply()` arm a `CullWatcher` for that prep dir, on top of the plain
`Waiting` behavior above. `MANUAL` mode (the default) skips this section entirely - `armWatchIfConfigured`
returns immediately.

```mermaid
flowchart TD
    A["dispatchAndApply() lands<br/>on CullJobOutcome.Waiting"] --> B{"mode == WATCH?"}
    B -- "no" --> Z(["stay Waiting - unchanged"])
    B -- "yes" --> C["CullWatcher armed,<br/>polling every watchPollInterval<br/>(2s in production)"]
    C --> D{"tally fully valid?<br/>(cheap check, no submit)"}
    D -- "not yet" --> E{"watchTimeout elapsed?"}
    E -- "no" --> C
    E -- "yes" --> F(["watcher stops -<br/>drops back to plain manual<br/>Waiting, nothing touched"])
    D -- "yes" --> G["attempt resume()<br/>via JobRunner.submit()"]
    G -- "busy (another job running)" --> C
    G -- "submitted" --> H(["watcher stops -<br/>the submitted job's own outcome<br/>re-arms a fresh watcher if<br/>it lands back in Waiting"])
```

Deliberately pure polling, not `java.nio.file.WatchService`. A user's working folder can itself be
a cloud-synced or network folder (a Settings choice) - exactly the folder type known to miss
filesystem events. Depending on events at all would just relocate that gap. `watchPollInterval` is
an internal cadence, not a `CullSettings` field - only `mode` and `watchTimeout` are the documented
user-facing knobs.

`disarmWatch()` runs at the very start of every `dispatchAndApply()` call, regardless of who
triggered it (a fresh `cull()`, a manual `resume()` click, or a watcher's own auto-resume) - the
prep dir's watcher, if any, is always retired before a real attempt runs, so a manual click racing
an armed watcher can never leave two pollers on the same job. `armWatchesForExistingWaitingJobs()`
(`@PostConstruct`) re-arms every still-waiting job found on disk at startup, since there is no
persistent job store - restarting the app would otherwise silently stop watching every job armed
before the restart.

### Scenarios

| Scenario                                                         | Outcome                                                                                           |
|------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| Watch mode, a valid shard for every montage eventually appears   | The next poll tick's tally check passes, `resume()` is submitted automatically, `Applied` follows |
| Watch mode, `JobRunner` is busy with an unrelated job when ready | `attemptConsume` returns false; the watcher keeps polling and retries on the next tick            |
| Watch mode, `watchTimeout` elapses with no fully-valid tally     | Watcher stops on its own; every dropped shard is untouched; a manual `resume()` still works       |
| Watch mode, app restarts while a job is still waiting            | `armWatchesForExistingWaitingJobs()` re-arms a watcher for it purely from `waitingJobs()`         |
| Manual mode (default)                                            | No watcher ever arms; behavior is identical to the `cull()`/`resume()` section above              |

## `curate()`

Sort, then cull whatever that sort just populated - one job, sequential Java calls inside its
`JobWork`. Never two chained `submit()` calls (see `JobHandle`'s own doc on why nothing threads a
job's outcome across separate `submit()` calls).

The target `CullScope` mirrors `scope` directly wherever that's knowable up front. An explicit
`Year` maps straight across. `OldestN` carries the same `n` through to `CullScope.OldestN`. Sort
itself is never narrowed to fit cull's shape - it always runs its own normal, complete job.

An `OldestN` sort can still land files across more than one year. Cull's own `OldestN` ordering is
by raw mtime, not resolved date (see `CullScope`'s own doc) - exactly what a standalone `cull()`
call already does with that scope. Nothing new here.

Only `OldestYear` can't be mapped ahead of time - its year isn't decided until the sort itself
resolves it. `SortSummary.yearsSorted()` exists for exactly this: it's the only place an
auto-resolved `OldestYear` scope's actual year is ever reported.

A known target `CullScope` (`Year` or `OldestN`) gets the same synchronous, pre-submit
`checkNoWaitingJobFor()` `cull()` gets - failing before the sort even starts. `OldestYear` can't be
checked that early. Its only guard is the same check running again once its resolved year is known,
after the sort has already moved real files - narrowly, around just that one call, not the
dispatch/apply that follows, so a genuine cull failure downstream (a misconfigured provider, for
example) is never mislabeled as this conflict.

A failure at that later point can't be a plain `IllegalStateException` like the pre-submit one is.
The sort already moved real files by then. A caller must not lose the `SortSummary` describing
that just because the cull stage was refused. `curate()` catches it and rethrows
`Pipeline.CurateConflictException` (an `IllegalStateException` subtype) instead, carrying the
`SortSummary` via its own `sortSummary()` accessor. This is the only failure path that needs to
carry a partial result - every other `checkNoWaitingJobFor()` failure happens before anything runs.

Cancellation is checked once, at the one boundary this job actually has: between the sort stage
finishing and the cull stage starting. This matches the "coarse, between pipeline stages only"
cancellation model the other calls have no boundary to use.

That coarseness is a known, not-yet-closed gap. A cancellation request has to wait for whichever
stage is currently running to finish on its own, and a large sort can run long. `SortEngine`'s own
per-file loop is the natural place to add a finer-grained check later, the same shape as its
existing progress-callback overload.

Cull's own dispatch loop is a separate, lower-priority gap. It only matters for an automated vision
provider looping over montages - the external-agent provider's dispatch is already a fast, single
presence check.

```mermaid
flowchart TD
    A["Pipeline.curate(scope)"] --> K{"CullScope knowable<br/>before the sort runs?<br/>(Year or OldestN: yes;<br/>OldestYear: no)"}
    K -- "yes" --> KW{"a WaitingCullJob<br/>already exists for<br/>that CullScope?"}
    KW -- "yes" --> KZ(["IllegalStateException,<br/>thrown synchronously -<br/>sort never starts"])
    KW -- "no" --> B["JobRunner.submit"]
    K -- "no" --> B
    B --> S["sort: SortEngine.sort<br/>-> SortSummary"]
    S --> C{"cancellation<br/>requested?"}
    C -- "yes" --> CZ(["CurateOutcome(sortSummary, null)<br/>- cull stage skipped"])
    C -- "no" --> R{"CullScope known?<br/>(OldestYear: resolved from<br/>sortSummary.yearsSorted())"}
    R -- "no" --> RZ(["CurateOutcome(sortSummary, null)<br/>- nothing landed in Sorted"])
    R -- "yes, and known == null<br/>(OldestYear)" --> OC{"checkNoWaitingJobFor(cullScope)<br/>again, now that the year is known"}
    OC -- "conflict" --> OCZ(["CurateConflictException,<br/>carries sortSummary -<br/>files already moved"])
    OC -- "no conflict" --> BD["buildFreshAndDispatch(cullScope)"]
    R -- "yes, and known != null<br/>(already checked at K)" --> BD
    BD --> APP(["CurateOutcome(sortSummary, cullOutcome)"])
```

| Pipeline method     | What runs                                                 | Phase label(s)                                                                      |
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

## Related

- `JobRunner`/`JobHandle`/`JobWork` (the single-slot async executor `Pipeline` submits onto): no
  dedicated design doc yet - see the source files directly.
- `ProgressPort` (the out-port `Pipeline` reports through): see the source file directly; its own
  doc comment is the source of the "always bracket a phase" contract this page relies on.
- `SortEngine`: `sort-engine.md` in this same design folder.
- `RescueEngine`: `rescue-engine.md` in this same design folder.
- `CommitEngine` has no design doc of its own (one loop, one branch - judged too thin to diagram).
