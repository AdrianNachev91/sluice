# Move ledger

How `application/service/MoveLedger` owns `move-records.log`: the file name, the line format,
every disposition marker, and how the whole file parses back
(`app/src/main/java/photos/sluice/application/service/MoveLedger.java`).

This is a format reference, not a flow. For how each disposition actually gets used - who reads
it, who appends to it, and why - see the cross-links in Related below.

## File and delimiter

One `move-records.log` lives inside each prep directory, at the path the private `logFor()`
returns. Every field within a line is split on `\u001F`, the ASCII unit-separator control
character. It is guaranteed absent from any path on every mainstream filesystem. Fields split
apart with zero escaping, even when a path itself contains spaces or commas.

## The four dispositions

`read()` parses the whole file in one pass into a `Ledger` snapshot: the file's own path, plus
four accumulators - `moves`, `skipped`, `overlaps`, and `corruptSidecars`. A witnessed move and a
reconstructed move share the same `moves` map. Provenance is not kept as a separate field once
parsed, so both are trusted identically by a reading caller.

Markers are checked before falling back to the plain move shapes below. A move record's own
`dest` field can never equal one of these marker strings, so checking the markers first is
unambiguous. An unrecognized line shape is silently ignored.

| Disposition              | Fields | Appended by              | Format                                                                                         |
|--------------------------|--------|--------------------------|------------------------------------------------------------------------------------------------|
| Witnessed move           | 3      | `recordMove()`           | `source` DELIM `dest` DELIM `hash`                                                             |
| Reconstructed move       | 4      | `recordReconstructed()`  | `source` DELIM `dest` DELIM `hash` DELIM `RECONSTRUCTED`                                       |
| Skipped                  | 4      | `recordSkip()`           | `source` DELIM `SKIPPED_BY_USER` DELIM `timestamp` DELIM `reason`                              |
| Overlap resolved         | 5      | `recordOverlap()`        | `file` DELIM `OVERLAP_RESOLVED` DELIM `resolution` DELIM `timestamp` DELIM `reason`            |
| Corrupt sidecar resolved | 5      | `recordCorruptSidecar()` | `montage` DELIM `CORRUPT_SIDECAR_RESOLVED` DELIM `resolution` DELIM `timestamp` DELIM `reason` |

("DELIM" stands in for the literal `\u001F` character above, for readability.)

Each disposition has exactly one writer:

- A **witnessed** move is recorded by `ApplyEngine.recordThenMove()`, right before the move it
  guards runs. See `apply-engine.md`.
- A **reconstructed** move is recorded by `ReconcileEngine`'s own rebuild sweep, once a group of
  pending moves resolves unambiguously. See `reconcile-engine.md`.
- A **skip**, an **overlap** resolution, and a **corrupt-sidecar** resolution are each recorded by
  `PrepDirRemedies`, one CHOICE remedy per disposition. See `prep-dir-remedies.md`.

## Reading it back

`ApplyPlanner.classify()` and `classifyFile()` consult `moves` and `skipped` to decide whether a
decision or an unreviewable file is pending, already done, given up on, or unresolvable. See
`apply-planner.md`. `ApplyPlanner.resolveOverlaps()` and `resolvedUnreviewable()` consult
`overlaps`. `ApplyPlanner.collectMontage()` consults `corruptSidecars`; see `apply-planner.md`
and `prep-dir-remedies.md`.

## One snapshot per run, threaded through

`read()` is called once per public entry point - `apply()`, `diagnose()`, `reconcile()`. The
resulting `Ledger` is a plain parameter, passed to every collaborator that reads from it. Nothing
caches it. A `Ledger`'s four maps are copied on return, so sharing one snapshot across several
read-only consumers cannot let one of them mutate what another sees.

`reconcile()` takes its snapshot before filing `move-records.log` into the disaster drawer.
Everything it resolves against the ledger must see the same state that filing is about to
replace. See `reconcile-engine.md`.

`ApplyPlanner` itself holds no ledger reference at all - see `apply-planner.md`. `PrepDirDoctor`
holds only the read-only `LedgerReader` view, never the append-capable `MoveLedger` - see
`prep-dir-doctor.md`.

## Related

- The classification that reads `moves` and `skipped`: `apply-planner.md`.
- The overlap-resolution consultation that reads `overlaps`: `apply-planner.md`.
- The CHOICE remedies that append a skip, overlap, or corrupt-sidecar resolution:
  `prep-dir-remedies.md`.
- The witnessed move written before every real move: `apply-engine.md`.
- The reconstructed move written by an offline rebuild: `reconcile-engine.md`.
