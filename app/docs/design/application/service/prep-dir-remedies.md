# Prep dir remedies

How `application/service/PrepDirRemedies` repairs a damaged prep directory: the disposition
ledger's CHOICE remedies, a corrupt or missing `index.json`/sidecar, and the last-resort discard
(`app/src/main/java/photos/sluice/application/service/PrepDirRemedies.java`).

Two kinds of remedy live here. An AUTO remedy is non-destructive and provably safe, so a
troubleshooter runs it unprompted. Examples are renaming an unambiguous stray shard into place, or
rebuilding a lost `index.json` from surviving sidecars. A CHOICE remedy costs the user something
(work, money, or an audit trail), so it only ever runs on an explicit decision.

Every CHOICE remedy records itself as a disposition-ledger entry. Neither a shard nor `index.json`
is ever edited. Mutating a culler's own output would destroy the record of what it actually said,
which is exactly what these repairs exist to reason about.

## 1. Disposition ledger and CHOICE remedies

The disposition ledger covers every decision and unreviewable file the shard/`index.json` alone
can't resolve. A witnessed or reconstructed move record is one disposition, held in
`move-records.log` (see [`apply-planner.md`](apply-planner.md) and
[`reconcile-engine.md`](reconcile-engine.md)). `skipMissingSource()` and `resolveOverlap()` append
two more, each a **CHOICE** remedy a user picks between, never guessed at automatically. Those land
in `choices.log`, the ledger's other half. Neither one ever edits a shard or `index.json`. Both work
by appending a ledger entry that `ApplyPlanner.validate()`/`classify()` consult on every later read.

The write side of each CHOICE lives here: `skipMissingSource`, `resolveOverlap`,
`resolveCorruptSidecar`. The read side that consults it - suppressing findings and filtering the
decisions and unreviewable files `apply()` acts on - lives in `ApplyPlanner`. See
[`apply-planner.md`](apply-planner.md). `MoveLedger.read()` parses both files into their four
dispositions in one pass; see [`move-ledger.md`](move-ledger.md) for the file formats and why the
ledger is split in two.

An answer, once given, is permanent. It survives a reconcile, a troubleshoot and a restart, and
nothing re-asks it. There is no un-answer affordance, deliberately. An answer only ever changes
routing within its own run, and no CHOICE remedy here destroys media. A skipped or set-aside photo
stays in `Sorted` for a future cull. `resolveCorruptSidecar` has the widest reach of the three,
since it is keyed by montage rather than by file, so a `SET_ASIDE` settles a whole montage at once.

Two things end an answer, neither of them an undo. A `choices.log` whose bytes do not decode loses
what it held; [`reconcile-engine.md`](reconcile-engine.md) covers that. `discard()` below gives up
on the whole run, ledger included, and is the one sanctioned give-up path.

```mermaid
flowchart TD
    A["Finding.MissingSource"] --> B{"user's choice"}
    B -- "restored it" --> C(["no engine call -<br/>just re-diagnose"])
    B -- "skip this file" --> D["skipMissingSource() -<br/>appends SKIPPED_BY_USER<br/>+ timestamp + reason"]
    D --> E(["classify() reports Skipped -<br/>apply() moves/writes<br/>nothing for it, ever"])

    F["Finding.VerdictUnreviewableOverlap"] --> G{"user's choice"}
    G -- "trust the decision" --> H["resolveOverlap(TRUST_DECISION)"]
    G -- "treat as unreviewable" --> I["resolveOverlap(TREAT_AS_UNREVIEWABLE)"]
    H --> J["validate(): finding suppressed,<br/>file dropped from<br/>resolvedUnreviewable()"]
    I --> K["validate(): finding suppressed,<br/>the verdict dropped, and the<br/>file moved to Unreviewable"]
```

`VerdictUnreviewableOverlap` is `ShardValidator`'s own finding for the narrow
one-verdict-plus-one-unreviewable shape. A `Verdict.Keep` counts as that one verdict, not only a
`Decision`. Either is a shard claiming it judged a photo the app reported nobody could judge.
Every other multi-reference shape stays the general `DuplicateFileReference`, which has no CHOICE
remedy. `ApplyPlanner.resolveOverlaps()` resolves it, a post-processing pass `validate()` runs over
`ShardValidator`'s own report. A resolved finding is suppressed. The losing side never reaches
`apply()`: the verdict for `TREAT_AS_UNREVIEWABLE`, or the unreviewable listing for
`TRUST_DECISION`.
`ApplyPlanner.resolvedUnreviewable()` is the single place `prepDir.unreviewable()` gets filtered
against a `TRUST_DECISION` resolution. Every caller that needs prepDir's unreviewable list -
`apply()`, `checkMissingSources()`, `reconcile()` - reads through it instead of
`prepDir.unreviewable()` directly.

`Finding.StrayShard` gets a third remedy, this one not ledger-based: `autoRepairStrayShard()`. It
is **AUTO** when it's provably unambiguous. Exactly one montage in the prep dir currently has no
shard. Every file the stray shard's own decisions name is also a member of that one candidate
montage's sidecar. It renames the stray file into place (`decisions-NNN.json` for the candidate
montage) with no ledger entry needed, the rename itself is the fix. Anything else is left
untouched: more than one montage unclaimed, or a decision naming a file the candidate's sidecar
never showed. `setAsideStrayShard()` is the CHOICE fallback for that case. It files the stray
file into the disaster drawer (never a true delete), so the culler can redo that montage from a
clean slate. `Troubleshooter` attempts this AUTO repair for every `StrayShard` finding it sees,
regardless of overall prep-dir state. See [`troubleshooter.md`](troubleshooter.md).

## 2. Corrupt or missing index.json / sidecar

This app's own prior output can itself be damaged in two places, but the two read paths tell
"damaged" apart differently. `index.json` (the prep dir's own summary) reaches its AUTO remedy only
when it's missing entirely or its content is genuinely malformed (`MalformedPrepJsonException`). A
read that merely failed - a lock, a permission denial - is not diagnosed here at all. It propagates
uncaught, so a transient failure never triggers an AUTO rebuild against an index that was never
actually broken. A montage's own sidecar (`montage-NNN.json`, its scope evidence) is read more
broadly below. Any read failure routes it to the CHOICE remedy, since `Sidecars.srcsOf` doesn't yet
make the same distinction. Neither missing nor malformed nor unreadable is a culling mistake. Every
case index.json's AUTO remedy actually reaches, and every sidecar case, gets a remedy here instead
of a bare crash.

```mermaid
flowchart TD
    A["diagnose() / apply()<br/>reads index.json"] --> Z{"missing, or content<br/>genuinely malformed?"}
    Z -- yes --> B(["Finding.CorruptIndex<br/>(AUTO)"])
    Z -- "no - the read<br/>itself just failed" --> P(["propagates uncaught -<br/>not diagnosed here"])
    B --> C["rebuildIndex():<br/>scan for montage-NNN.json<br/>sidecar files"]
    C --> D{"contiguous 1..N,<br/>every one parseable?"}
    D -- no --> E(["guard refuses -<br/>Optional.empty(),<br/>nothing written"])
    D -- yes --> F["file the corrupt<br/>original into the<br/>disaster drawer, if present"]
    F --> G(["write a fresh index.json:<br/>entries + photos derived<br/>from sidecars, basePath =<br/>deepest common parent,<br/>unreviewable = empty,<br/>categories = live config"])
```

`rebuildIndex()`'s guard only trusts a *contiguous* montage sequence where *every* sidecar in it
also parses cleanly. A gap or an unparseable sidecar means the sidecars themselves are also
damaged. A silently-smaller rebuilt index would make perfectly healthy shards look stray.

The unreviewable list is genuinely unrecoverable (no sidecar or shard ever names it), so a
rebuilt index always reports it empty. That's a report-line loss, not a safety one. A file dropped
from the rebuilt list is simply not acted on. `basePath` is reconstructed as the deepest common
parent of every surviving sidecar's own `src` files. That's exact for a `Year` scope, an
approximation for `OldestN` narrowed to one year. The field is display-only, though, and no engine
logic ever consults it.

The category set is unrecoverable the same way, and it does not degrade as quietly. A sidecar
carries only `src`, `name`, `time` and `received`, so nothing left on disk remembers what this run
was culled under. The currently configured set is substituted. A run repaired after a category edit
is therefore judged against today's rules, which is how every run behaved before the set was
recorded at all. So the repair path is no worse than what it replaces, while the happy path stops
drifting. This is the only place in the app that makes that substitution.

A montage's own sidecar failing to read, with `index.json` itself intact, is a narrower problem:
`Finding.CorruptSidecar` (CHOICE). This per-montage check is `ApplyPlanner.collectMontage()`, run as
part of `validate()`'s own sidecar/shard collection loop. See [`apply-planner.md`](apply-planner.md)
for that method; the resolution below is what this class contributes.

```mermaid
flowchart TD
    A["one montage"] --> B{"sidecar readable?"}
    B -- yes --> C(["contributes its srcs<br/>to the in-scope pool,<br/>and its shard too,<br/>if it has one"])
    B -- no --> D{"montage has<br/>a shard yet?"}
    D -- no --> E(["silently skipped -<br/>not yet actionable,<br/>same as a still-culling<br/>montage generally"])
    D -- yes --> F{"ledger resolution<br/>for this montage?"}
    F -- none yet --> G(["Finding.CorruptSidecar<br/>(CHOICE)"])
    F -- SET_ASIDE --> H(["montage dropped<br/>entirely - no shard,<br/>no srcs, its photos<br/>stay in Sorted"])
    F -- APPLY_ANYWAY --> I(["shard's own decision<br/>files seeded into the<br/>in-scope pool - trusted<br/>at face value"])
```

`resolveCorruptSidecar()` records the user's choice as one more disposition-ledger entry (the
mechanism from section 1 above, keyed by montage id rather than a file path). It also files the
sidecar itself into the disaster drawer if it's still present, its scope evidence is spent either
way once a choice is made. `SET_ASIDE` means a future cull of the same scope sees those photos
fresh. `APPLY_ANYWAY` means every other safety net still applies: files must exist, categories must
be ones index.json recorded, the cross-shard duplicate check runs, and nothing is overwritten. Only
the membership cross-check is skipped.

### Why one validator, and why it is the apply phase's

Every remedy on this page is worth exactly as much as the run's ability to reach the gate that
reads it. That gate is `ApplyPlanner.validate()`. It is the only place the disposition ledger is
consulted. Any second validator ahead of it works from raw disk state alone. It would therefore
re-derive verdicts the user has already answered, and refuse the run before their answer could
count.

The cull phase is the one step sitting ahead of that gate, so the vision cullers hold no opinion
about shard content. The external-agent provider asks only which montages have no shard file at
all. The Anthropic provider validates the model's own response, because that response is its own
output and the corrective retry needs the problem list. It skips a montage whose sidecar it cannot
read rather than failing the run. Neither provider consults `index.json`'s unreviewable list. A
resume whose montages all have shards enters no culler at all and goes straight to the gate.

Both `resolveCorruptSidecar` resolutions depend on that. So does `resolveOverlap` with
`TRUST_DECISION`. Each records an answer that only this gate can read, on a prep dir whose raw disk
state still shows the original problem.

A watcher's automatic resume reaches those answers the ordinary way. Its readiness check
(`ShardTallyCalculator.isReadyToResume`) only asks whether every shard has arrived and parses, never
whether the batch is any good. So a run whose remaining problem the user has already answered simply
resumes, and the gate above honours the answer. Nothing between the two holds a second opinion. See
[`cull-engine.md`](cull-engine.md) for why readiness is deliberately that narrow.

## 3. Last-resort discard

`discard()` is the remedy for a prep dir mangled beyond every repair above. It gives up on the run
entirely rather than resolving it. Every non-image file (shards, sidecars, `index.json`, both
ledger files, and any disaster drawer, preserving its own relative layout) is moved wholesale
into a global graveyard, `logs/disasters/<scope>-<timestamp>/`. The scope is read straight off the
prep dir's own folder name, never `index.json`. The whole point of this remedy is that
`index.json` (or anything else) might be unreadable. Only the montage/tile contact-sheet images
are truly deleted, cents to re-render on a fresh cull of the same scope. Library media is never
touched. It returns a `DiscardReport` naming the graveyard directory and how many montage decision
shards (`decisions-NNN.json`) were among the files filed there. That lets a caller tell the user
how many already-paid vision-model calls this discard gives up on.

This is the raw, ungated mechanism only, and its two callers gate it in opposite directions.

`Pipeline.discard()` is the give-up path, so it gates on `PrepDirDoctor` reporting anything but
`COMPLETE` - `purgeCompleted()` is that state's own verb. It also retires any watcher polling the
prep dir first (an auto-resume must never fire against a run mid-discard), and wraps it as a
`JobRunner` job. Both of this remedy's entry points - this last-resort CHOICE, and giving up on a
still-waiting job - call that one `Pipeline.discard()` method.

The cull engine's own scope claim requires exactly the opposite: `COMPLETE`, and nothing else. A
fresh run over a finished one archives that record here rather than letting prep overwrite it (see
[`cull-engine.md`](cull-engine.md)). Same file moves, opposite preconditions, because the only
question either caller asks is whether the run being filed away is finished.

## Scenarios

| Scenario                                                                             | Outcome                                                                            |
|--------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| A file listed both as a verdict and in index.json's unreviewable list                | Reported as `VerdictUnreviewableOverlap` (CHOICE), not `DuplicateFileReference`    |
| A missing file the user resolved via `skipMissingSource()`                           | Skipped - `apply()` moves/writes nothing for it, ever again                        |
| An overlap resolved `TRUST_DECISION`                                                 | The shard's verdict applies; the file is no longer treated as unreviewable         |
| An overlap resolved `TREAT_AS_UNREVIEWABLE`                                          | The file moves to `Unreviewable/<yyyy>/<mm>/`; the verdict is dropped              |
| A stray shard, exactly one montage unclaimed, decisions match that montage's sidecar | `autoRepairStrayShard()` (AUTO) renames it into place with no user input           |
| A stray shard that can't be assigned unambiguously                                   | Left as a `StrayShard` finding; `setAsideStrayShard()` is the CHOICE fallback      |
| index.json is corrupt or missing, every sidecar contiguous and parseable             | `rebuildIndex()` (AUTO) rebuilds it from the sidecars; original filed if present   |
| index.json is corrupt or missing, a sidecar is also missing or unparseable           | Rebuild guard refuses - `CorruptIndex` finding stays open, no engine remedy left   |
| index.json read fails but is not missing or malformed (a lock, a permission denial)  | Propagates uncaught - not diagnosed here, no AUTO remedy attempted                 |
| A montage's sidecar is missing or corrupt, the montage has no shard yet              | Silently skipped - not yet actionable, same as any still-culling montage           |
| A montage's sidecar is missing or corrupt, the montage already has a shard           | `CorruptSidecar` finding (CHOICE)                                                  |
| A `CorruptSidecar` finding resolved `SET_ASIDE`                                      | Montage dropped entirely; its photos stay in `Sorted` for a future cull            |
| A `CorruptSidecar` finding resolved `APPLY_ANYWAY`                                   | Montage's own decisions trusted at face value; membership cross-check skipped      |
| A prep dir mangled beyond every repair above                                         | `discard()` (last-resort CHOICE) graveyards its text artifacts, deletes its images |

## Related

- The shard contract itself, and the auto-heal rule: `ShardValidator`'s own doc comment
  (`app/src/main/java/photos/sluice/domain/cull/ShardValidator.java`).
- The validation and classification that surface every finding resolved here, and that consult
  the ledger entries these remedies write: [`apply-planner.md`](apply-planner.md).
- The move-record log's own file format, markers, and parsing rules:
  [`move-ledger.md`](move-ledger.md).
- The pipeline that carries decisions out once validation and classification are clean, and its
  own cancellation handling: [`apply-engine.md`](apply-engine.md).
- The offline rebuild this class's `rebuildIndex()` complements, for when the move ledger itself
  (rather than the index) can't be trusted: [`reconcile-engine.md`](reconcile-engine.md).
- `Pipeline.discard()`'s `PrepDirDoctor`/`JobRunner`/watcher wiring around the raw `discard()`
  mechanism here: [`pipeline.md`](pipeline.md).
- The single-button recovery that runs `rebuildIndex()`, `autoRepairStrayShard()`, and (via a
  separate reconcile call) resolves a lost move ledger: [`troubleshooter.md`](troubleshooter.md).
- Where a repaired decision's file ends up once applied: `CullDestinations`
  (`app/src/main/java/photos/sluice/application/service/CullDestinations.java`), described in
  [`apply-engine.md`](apply-engine.md).
