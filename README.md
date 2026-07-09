# PhotoOrganizer

An AI-driven photo/video organizer. You dump raw files into `Inbox\`, then ask one of two agents to
sort, de-duplicate, and set aside junk — without deleting your photos (the only deletions are ones
whose exact bytes are already safe elsewhere). Keepers are staged locally, then committed to a
**OneDrive** library so they back up and sync automatically.

The heavy lifting runs in committed PowerShell engines (`scripts\`); the agents are thin so they cost
little to run.

## Two agents

- **`photo-sorter`** (cheap, no vision) — dates, de-dupes, and moves files by invoking the `scripts\`
  engines. Handles `sort`, `commit`, `rescue`.
- **`photo-culler`** (vision) — looks at *downscaled* photos and *montaged* clusters to flag junk and
  pick the best of a near-duplicate set. Handles `cull` and `organize`.

## Paths
- **Repo (local scratch):** `D:\Repos\Sluice` — `Inbox\`, `Sorted\`, `Duplicates\`, `Review\`, `logs\`, `scripts\`, `tools\`.
- **Library (OneDrive):** `D:\OneDrive\PhotoLibrary` — `Photos\`, `Videos\`, `Funny\`.

## How to use

1. Copy a batch of photos/videos (mixed is fine; Google Takeout's nested folders are fine) into `Inbox\`.
2. In Claude Code, ask — the mode keyword picks the agent:
   - `dry-run sort 2019` ← plan only, writes `logs\plan-*.csv`, moves nothing (recommended first look)
   - `sort the oldest year in the Inbox` ← normal incremental use; one year per run
   - `sort 2020 months 6-8` / `sort oldest 30 in the Inbox`
   - `cull 2019` ← the vision pass over the freshly-sorted `Sorted\`
   - `commit 2019` / `commit all` ← move `Sorted\` keepers into the OneDrive library
   - `rescue Review\Food` ← after you weed a Review folder by hand, promote the leftovers (see below)
   - `organize 2019` ← sort then cull in one go (no auto-commit)
3. After a cull, review the scratch folders and delete what you don't want:
   - `Duplicates\YYYY-MM_<label>\` — one near-dup group per folder: every candidate under its real
     name plus a `<chosen-filename>.txt` note explaining which was kept and why.
   - `Review\YYYY-MM\` — individual junk (blurry, accidental, badly exposed, low-res, screenshots,
     documents). Each file keeps its real name; the reason is listed in that folder's `_reasons.txt`.
   - `Review\Scenery\` — "meh" scenery to look at with a careful eye.
   - `Review\Food\` — food/meal photos (not kept by default).
   - `Review\Unsorted\` — files it couldn't date reliably; add a date and move them yourself.
4. `commit` when happy. Your clean library lives in OneDrive: `Photos\YYYY\MM`, `Videos\YYYY\MM`, `Funny\` (flat).

To bring back keepers you left in a Review folder, weed it manually then run `rescue Review\<folder>`
— it re-dates the leftovers and moves them into the library (or `Sorted\` with `… to sorted`), then
removes the emptied folder. It makes no keep/junk calls.

## Safety

- **Never deletes a unique file.** The only deletions are ones whose exact bytes survive elsewhere:
  an Inbox file byte-identical to one already in the library (a re-import, the library copy is
  confirmed on disk), byte-identical copies within a batch (keep one), and consumed Google Takeout
  JSON sidecars. No bulk/recursive media deletes.
- **No overwrite** (collision → ` (2)`, ` (3)`, …); stays inside the repo + library paths.
- **No run manifest and no undo** (kept the tool cheap). `sort`/`commit` only move files, reversible
  by hand, and the `Duplicates\`/`Review\` folders preserve everything set aside. `dry-run` previews
  any mode into `logs\plan-*.csv` without touching anything — use it when in doubt.
- `logs\library-hashes.csv` indexes the library so re-import detection is fast and reliable.

## Helper tools (exiftool & ImageMagick)

- **exiftool** (`tools\exiftool.exe`) — used for date reading on **non-Takeout** dumps (Google Takeout
  uses its JSON sidecars instead). Optional; the engines fall back to Shell metadata → filename → mtime.
- **ImageMagick** (`D:\Programs\ImageMagick\`) — used by the culler to downscale photos and build
  cluster montages for cheap vision, and to convert HEIC for viewing. If absent, HEIC is date-sorted
  and flagged rather than culled blind.

## How "where did I leave off" works

Processed files are **moved out** of `Inbox\`, so the Inbox itself is the to-do list. Saying "the
oldest year in the Inbox" each session automatically advances to the next year.

## What it does and doesn't do

- **Photos:** dates & sorts; deletes byte-identical duplicates (keeps one); the culler flags junk to
  Review, meh scenery to `Review\Scenery\`, food to `Review\Food\`, funny screenshots straight to the
  library `Funny\` (auto-kept, not staged or reviewed), and keeps the sharpest of a near-dup group
  (rejects preserved in `Duplicates\`). Keeps both copies of an edit only when the crop/framing differs.
- **Videos:** dates & sorts; removes byte-identical clips only. It cannot watch videos, so it does not
  judge video quality or near-duplicates — review those manually.
- **HEIC:** ImageMagick converts to a temp JPG to review; otherwise it date-sorts HEIC and flags it.

## Storage note

This git repo is for the **tool only** (agents, `scripts\`, docs) — `.gitignore` keeps scratch media
out of version control, and keepers live in OneDrive (not git). Access the library from onedrive.com
or your phone anywhere; a dead laptop loses nothing once sync completes.

## Models

`photo-sorter` runs on **Haiku** (it just invokes the engines and reads a summary — cheap).
`photo-culler` runs on **Sonnet** (good visual judgement at low cost, kept low by downscaling +
montages). For ~16k photos, do one year per run (≈5-6 runs); ongoing imports are tiny.
