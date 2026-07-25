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

## Related

- `JobRunner`/`JobHandle`/`JobWork` (the single-slot async executor `Pipeline` submits onto): no
  dedicated design doc yet - see the source files directly.
- `ProgressPort` (the out-port `Pipeline` reports through): see the source file directly; its own
  doc comment is the source of the "always bracket a phase" contract this page relies on.
- `SortEngine`: `sort-engine.md` in this same design folder.
- `RescueEngine`: `rescue-engine.md` in this same design folder.
- `CommitEngine` has no design doc of its own (one loop, one branch - judged too thin to diagram).
