# Move ledger

How `application/service/MoveLedger` owns a prep dir's two ledger files: their names, the line
format, every disposition marker, and how each file parses back
(`app/src/main/java/photos/sluice/application/service/MoveLedger.java`).

This is a format reference, not a flow. For how each disposition actually gets used - who reads
it, who appends to it, and why - see the cross-links in Related below.

## Two files, and why

A prep directory holds `move-records.log` and `choices.log`, at the paths `moveRecordLogFor()`
and `choicesLogFor()` return. The split is by what a lost line costs.

A move record states that a named file's bytes went to a named destination. Disk state either
agrees or it does not. So a lost move record is re-derivable by looking at disk again, which is
exactly what `ReconcileEngine` does. A choice record is user testimony instead. Nothing on disk
records that somebody chose to give up on a missing file, so a lost choice can only be re-asked.

Keeping the two apart gives each kind of evidence its own file to be reasoned about by name. A
rule about re-deriving from disk can then say which file it means, rather than which subset of one
file's lines. That is what the split buys: a reconcile files the whole move-record file away and
rebuilds it, and `choices.log` is simply not in its scope. The answers survive by construction.

## When a file's bytes do not decode

Either file may be present and undecodable. A torn write or a damaged sector leaves that behind. So
does a user opening the file and saving it back in another encoding. A UTF-16 save even starts with
the same bytes a corrupt one does, and neither is readable as what this app writes. That is a
different failure from the malformed line below, and it is not fatal to reading the other file. `read()` gives back no entries for the damaged side and carries on. A damaged
ledger is the exact state the recovery flow exists to diagnose, so it has to reach a diagnosis
rather than abort it.

**Only a decode failure degrades this way.** Every other I/O failure propagates. A file locked by
a backup or antivirus scanner, or one a permission check refuses, still holds every entry it ever
did. Reading that as an empty log would file intact answers away and report them lost, so it fails
the run loudly instead. `MediaReader.readLines`'s own contract is what makes the two
distinguishable: a decode failure must arrive as an `UncheckedIOException` caused by a
`CharacterCodingException`.

Only the choices side is reported, as the snapshot's `choicesUndecodable`. An undecodable
move-record file needs no flag. Every move it held reports as unproven, which is already how a
missing record reads, and a reconcile rebuilds them all from disk. Testimony has no second source,
so a caller has to be able to say the answers are gone. `ReconcileEngine` is that caller: it files
the damaged file into the drawer and discloses the loss in its report.

Appending a fresh answer files an undecodable choices file away first. Otherwise the new answer
would land in a file nothing can parse, and the finding it settles would raise again on the next
read. The original is kept for forensics rather than deleted. Its own answers are gone either way.

## Delimiter

Every field within a line of either file is split on `\u001F`, the ASCII unit-separator control
character. A filesystem may technically permit that character in a name, so its absence from a
path is a practical convention rather than an OS-enforced guarantee. It is vanishingly unlikely to
appear in a real path. Fields split apart with zero escaping, even when a path itself contains
spaces or commas. A path that did carry one would change its line's field count, and an
unrecognized line shape is dropped.

## The four dispositions

`read()` parses both files in one pass into a single `Ledger` snapshot. It carries the move-record
file's own path, four accumulators - `moves`, `skipped`, `overlaps`, and `corruptSidecars` - and
the `choicesUndecodable` flag above. Either file may be absent, which reads as an empty contribution
rather than an error. A witnessed move and a reconstructed move share the same `moves` map.
Provenance is not kept as a separate field once parsed, so both are trusted identically by a
reading caller.

Each file has its own parser, recognizing only its own file's shapes. In `move-records.log` the
field count separates the two move shapes. In `choices.log` the marker in the second field names
which of the three choices a line records. An unrecognized line shape is silently ignored, so a
half-written trailing line from a crash costs one entry rather than the whole file. A line whose
shape is recognized but whose subject or resolution field cannot be parsed - a garbled path, an
unrecognized enum token - gets the same treatment. Neither parser lets `Path.of` or `Enum.valueOf`
escape as an unchecked exception; each drops just that one line instead.

| Disposition              | File               | Fields | Appended by              | Format                                                                                         |
|--------------------------|--------------------|--------|--------------------------|------------------------------------------------------------------------------------------------|
| Witnessed move           | `move-records.log` | 3      | `recordMove()`           | `source` DELIM `dest` DELIM `hash`                                                             |
| Reconstructed move       | `move-records.log` | 4      | `recordReconstructed()`  | `source` DELIM `dest` DELIM `hash` DELIM `RECONSTRUCTED`                                       |
| Skipped                  | `choices.log`      | 4      | `recordSkip()`           | `source` DELIM `SKIPPED_BY_USER` DELIM `timestamp` DELIM `reason`                              |
| Overlap resolved         | `choices.log`      | 5      | `recordOverlap()`        | `file` DELIM `OVERLAP_RESOLVED` DELIM `resolution` DELIM `timestamp` DELIM `reason`            |
| Corrupt sidecar resolved | `choices.log`      | 5      | `recordCorruptSidecar()` | `montage` DELIM `CORRUPT_SIDECAR_RESOLVED` DELIM `resolution` DELIM `timestamp` DELIM `reason` |

("DELIM" stands in for the literal `\u001F` character above, for readability.)

Each disposition has exactly one writer:

- A **witnessed** move is recorded by `ApplyEngine.recordThenMove()`, right before the move it
  guards runs. See [`apply-engine.md`](apply-engine.md).
- A **reconstructed** move is recorded by `ReconcileEngine`'s own rebuild sweep, once a group of
  pending moves resolves unambiguously. See [`reconcile-engine.md`](reconcile-engine.md).
- A **skip**, an **overlap** resolution, and a **corrupt-sidecar** resolution are each recorded by
  `PrepDirRemedies`, one CHOICE remedy per disposition. See
  [`prep-dir-remedies.md`](prep-dir-remedies.md).

## Reading it back

`ApplyPlanner.classify()` and `classifyFile()` consult `moves` and `skipped` to decide whether a
decision or an unreviewable file is pending, already done, given up on, or unresolvable. See
[`apply-planner.md`](apply-planner.md). `ApplyPlanner.resolveOverlaps()` and
`resolvedUnreviewable()` consult `overlaps`. `ApplyPlanner.collectMontage()` consults
`corruptSidecars`; see [`apply-planner.md`](apply-planner.md) and
[`prep-dir-remedies.md`](prep-dir-remedies.md).

## One snapshot per run, threaded through

`read()` is called once per public entry point - `apply()`, `diagnose()`, `reconcile()`. The
resulting `Ledger` is a plain parameter, passed to every collaborator that reads from it. Nothing
caches it. A `Ledger`'s four maps are copied on return, so sharing one snapshot across several
read-only consumers cannot let one of them mutate what another sees.

`reconcile()` takes its snapshot before either of its own filings. `validate()` and
`resolvedUnreviewable()` both consume that snapshot, and the sweep reads its skips. A snapshot
taken later would also read a filed-away choices file as merely absent, so the loss it is meant to
disclose would go unreported. See [`reconcile-engine.md`](reconcile-engine.md).

`ApplyPlanner` itself holds no ledger reference at all - see [`apply-planner.md`](apply-planner.md).
`PrepDirDoctor` holds only the read-only `LedgerReader` view, never the append-capable
`MoveLedger` - see [`prep-dir-doctor.md`](prep-dir-doctor.md).

## Related

- The classification that reads `moves` and `skipped`: [`apply-planner.md`](apply-planner.md).
- The overlap-resolution consultation that reads `overlaps`: [`apply-planner.md`](apply-planner.md).
- The CHOICE remedies that append a skip, overlap, or corrupt-sidecar resolution:
  [`prep-dir-remedies.md`](prep-dir-remedies.md).
- The witnessed move written before every real move: [`apply-engine.md`](apply-engine.md).
- The reconstructed move written by an offline rebuild:
  [`reconcile-engine.md`](reconcile-engine.md).
