# Sort engine

How `application/service/SortEngine` turns a scan of the Inbox into a `SortSummary`, moving each
file to `Sorted/` or `Review/` along the way
(`app/src/main/java/photos/sluice/application/service/SortEngine.java`).

## 1. The one-pass pipeline

```mermaid
flowchart TD
    A["scan the Inbox"] --> B["resolve a date for every<br/>scanned file (whole Inbox,<br/>not just what's in scope)"]
    B --> C["narrow to the requested<br/>SortScope"]
    C --> D["consume sidecars<br/>(see below)"]
    C --> E["which library hashes are<br/>still real? (see below)"]
    D --> F["hash every in-scope file"]
    E --> F
    F --> G["partition: redundant vs<br/>library / within-batch<br/>duplicate / keeper"]
    G --> H["delete redundant<br/>and duplicate files"]
    G --> I["route each keeper<br/>(see below)"]
    H --> J["build SortSummary"]
    I --> J
```

Every scanned file is dated before the scope narrows anything. `OldestYear`/`OldestN` need to
compare dates across the *whole* Inbox to pick the right subset, so dating only the
already-narrowed files would pick the wrong ones. Sidecar consumption and the library-hash check
are independent of each other, and of the dedup partition that follows. Nothing here depends on
which of the three dedup buckets a file eventually lands in.

## 2. Consuming a sidecar

```mermaid
flowchart TD
    A["for each in-scope file"] --> B{"has a paired sidecar?"}
    B -- no --> Z(["not consumed"])
    B -- yes --> C{"did that sidecar actually<br/>produce this file's date?"}
    C -- no --> Z
    C -- yes --> D{"already deleted this<br/>sidecar path this run?"}
    D -- yes --> Z
    D -- no --> E["delete it, count it"]
```

A sidecar that exists but lost the date-resolution race - invalid content, or a coincidentally
name-matched but unrelated JSON file - is left alone. Only a sidecar that was actually read as
real metadata gets consumed. The "already deleted?" check exists because an edited copy of a photo
shares its original's sidecar. Both files can be in scope at once, and both resolve their date
from the same JSON path. It must be deleted, and counted, exactly once - not once per file that
points at it.

## 3. Which library hashes still count as "real"

```mermaid
flowchart TD
    A["for each hash the<br/>library index remembers"] --> B{"does at least one of its<br/>recorded paths still<br/>exist on disk?"}
    B -- yes --> C(["counts as already-in-library"])
    B -- no --> D(["stale - ignored"])
```

The library lives outside this process's control - it can be resynced, moved, or pruned
independently. A stale index entry pointing at a since-vanished file must never cause an
otherwise-unique Inbox file to be deleted as a false duplicate. Only a hash with at least one
surviving copy can safely delete its Inbox twin.

## 4. Routing a keeper

```mermaid
flowchart TD
    A["a keeper (survived dedup)"] --> B{"date confidence<br/>UNSORTABLE?"}
    B -- yes --> C(["Review/Unsorted/,<br/>reason noted"])
    B -- no --> D{"video?"}
    D -- yes --> G
    D -- no --> E{"low-res gate<br/>(size or pixel dimensions;<br/>svg exempt)?"}
    E -- yes --> F(["Review/&lt;yyyy-MM&gt;/,<br/>reason noted"])
    E -- no --> G["Sorted/Photos|Videos/<br/>&lt;yyyy&gt;/&lt;MM&gt;/"]
    G --> H{"date confidence LOW<br/>(mtime-sourced)?"}
    H -- yes --> I(["also flagged in the<br/>low-confidence list"])
```

Checked in this order and only this order. An undatable file is routed away before anything else
looks at it - "which folder does this date belong in" is meaningless without a usable date. The
low-res gate only ever runs on a file that already cleared that check. A "reason noted" move also
appends a line (`"<filename> - <reason>"`) to a `_reasons.txt` file in the destination folder.

## 5. Post-run sweep

Runs once, after every keeper has been routed:

```mermaid
flowchart TD
    A["remaining media = step 1's<br/>scan media, minus every<br/>in-scope file's path"] --> C["SidecarSweep.findOrphaned<br/>on the derived media + JSON lists"]
    B["remaining JSON = step 1's<br/>scan JSON paths, minus every<br/>path section 2 consumed"] --> C
    C --> D["delete every orphaned<br/>sidecar returned"]
    D --> E["MediaStore.removeEmptyDirectories<br/>(Inbox root)"]
```

This is independent of section 2's per-file consumption - that mechanism only spends a sidecar
whose date actually won for its file. The sweep instead catches every other spent sidecar too.
That includes unmatched ones, and ones whose media left via a different branch (sorted, or deleted
as a duplicate) than the one that would have consumed their sidecar inline. This is what keeps
sidecars from piling up across incremental year-by-year runs. `SortSummary.sidecarsDeleted` is not
incremented here - only section 2's inline consumption counts toward it.

"Remaining" is derived from step 1's original scan, not observed directly. `dedup.plan`'s three
buckets (section 1) are a total partition of every in-scope file, and each bucket is either moved
or deleted before the sweep runs. So every in-scope file is guaranteed to have left the Inbox
already, and step 1's original scan lists minus what this run itself removed already describe
what's left.

## Scenarios

| Scenario | Outcome |
|---|---|
| Photo with a Takeout sidecar that produced its date | Sidecar deleted, counted in `sidecarsDeleted` |
| Photo `+` its `-edited` copy, sharing one sidecar, both in scope | Sidecar deleted exactly once, `sidecarsDeleted` = 1, not 2 |
| Sidecar present but invalid/unrelated, and its would-be media leaves the directory this run | Not deleted inline (not counted in `sidecarsDeleted`); swept afterward since nothing in the directory owns it anymore |
| Sidecar present but invalid/unrelated, and a same-named media file remains in the directory (e.g. awaiting a future run) | Left on disk by both mechanisms |
| Inbox file's hash matches a library-index hash whose recorded path still exists | Deleted, counted in `reimportsDeleted`, never moved |
| Inbox file's hash matches a library-index hash whose recorded path is gone | Not treated as redundant - sorts (or routes) normally |
| Two inbox files share bytes, neither is in the library | First one sorts; the rest are deleted, counted in `byteDupsDeleted` |
| Undatable file (implausible mtime, no better source) | `Review/Unsorted/`, `unsorted` count, filename in `unsortedFiles` |
| Small or low-dimension photo | `Review/<yyyy-MM>/`, `lowRes` count |
| Video or `.svg`, however small | Exempt from the low-res gate - sorts normally |
| Sorted photo whose date came only from file-modification time | Sorts normally, but also listed in `lowConfidenceFiles` |
| A Takeout album directory left with no files at all after this run | Directory removed (cascades up through empty parent directories too) |

## Related

- The filesystem effects this pipeline uses (collision-safe move/copy, existence/size checks,
  appending a reason line): `media-store.md` in the `adapter/fs` design folder.
- Scope selection itself (`Year`/`OldestN`/`OldestYear`) is delegated to
  `domain/dating/ScopeSelector` and not diagrammed here - see that class directly.
- The sweep's orphan decision itself: `sidecar-sweep.md` in the `domain/scan` design folder.
