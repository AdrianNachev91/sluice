# PhotoOrganizer

An AI-driven photo/video organizer. You dump raw files into `Inbox\`, then ask one of two agents to
sort, de-duplicate, and set aside junk. It does not delete your photos. The only deletions are ones
whose exact bytes are already safe elsewhere. Keepers are staged locally, then committed to a
**OneDrive** library so they back up and sync automatically.

The heavy lifting runs in committed PowerShell engines (`scripts\`). The agents are thin, so they
cost little to run.

## Two agents

- **`photo-sorter`** (cheap, no vision) dates, de-dupes, and moves files by invoking the `scripts\`
  engines. Handles `sort`, `commit`, `rescue`.
- **`photo-culler`** (vision) looks at *downscaled* photos and *montaged* clusters to flag junk and
  pick the best of a near-duplicate set. Handles `cull` and `curate`.

## Paths
- **Repo (local scratch):** `D:\Repos\Sluice` holds `Inbox\`, `Sorted\`, `Duplicates\`, `Review\`,
  `logs\`, `scripts\`, `tools\`.
- **Library (OneDrive):** `D:\OneDrive\PhotoLibrary` holds `Photos\`, `Videos\`, `Funny\`.

## How to use

1. Copy a batch of photos/videos into `Inbox\` (a mix is fine, and so are Google Takeout's nested
   folders).
2. In Claude Code, just ask. The mode keyword picks the agent:
   - `dry-run sort 2019` ← plan only, writes `logs\plan-*.csv`, moves nothing (recommended first look)
   - `sort the oldest year in the Inbox` ← normal incremental use, one year per run
   - `sort 2020 months 6-8` / `sort oldest 30 in the Inbox`
   - `cull 2019` ← the vision pass over the freshly-sorted `Sorted\`
   - `commit 2019` / `commit all` ← move `Sorted\` keepers into the OneDrive library
   - `rescue Review\Food` ← after you weed a Review folder by hand, promote the leftovers (see below)
   - `curate 2019` ← sort then cull in one go (no auto-commit)
3. After a cull, review the scratch folders and delete what you don't want:
   - `Duplicates\YYYY-MM_<label>\` holds one near-dup group per folder: every candidate under its
     real name, plus a `<chosen-filename>.txt` note explaining which was kept and why.
   - `Review\YYYY-MM\`: individual junk (blurry, accidental, badly exposed, low-res, screenshots,
     documents). Each file keeps its real name, and the reason is listed in that folder's
     `_reasons.txt`.
   - `Review\Scenery\`: "meh" scenery to look at with a careful eye.
   - `Review\Food\`: food/meal photos (not kept by default).
   - `Review\Unsorted\`: files it couldn't date reliably. Add a date and move them yourself.
4. `commit` when happy. Your clean library lives in OneDrive: `Photos\YYYY\MM`, `Videos\YYYY\MM`,
   `Funny\` (flat).

To bring back keepers you left in a Review folder, weed it manually then run `rescue Review\<folder>`.
It re-dates the leftovers and moves them into the library, or into `Sorted\` with `… to sorted`.
Then it removes the emptied folder. It makes no keep/junk calls.

## Safety

- **Never deletes a unique file.** The only deletions are ones whose exact bytes survive elsewhere.
  There are three, and no bulk or recursive media deletes at all.
  - An Inbox file byte-identical to one already in the library. That is a re-import, and the library
    copy is confirmed on disk.
  - Byte-identical copies within a batch, keeping one.
  - A consumed Google Takeout JSON sidecar.
- **No overwrite** (collision → ` (2)`, ` (3)`, …). Stays inside the repo and library paths.
- **No run manifest and no undo** (kept the tool cheap). `sort`/`commit` only move files, reversible
  by hand, and the `Duplicates\`/`Review\` folders preserve everything set aside. `dry-run` previews
  any mode into `logs\plan-*.csv` without touching anything. Use it when in doubt.
- `logs\library-hashes.csv` indexes the library so re-import detection is fast and reliable.

## Helper tools (exiftool & ImageMagick)

- **exiftool** (`tools\exiftool.exe`) reads dates on **non-Takeout** dumps (Google Takeout uses its
  JSON sidecars instead). Optional. The engines fall back to Shell metadata → filename → mtime.
- **ImageMagick** (`D:\Programs\ImageMagick\`) is used by the culler to downscale photos and build
  cluster montages for cheap vision, and to convert HEIC for viewing. If absent, HEIC is date-sorted
  and flagged rather than culled blind.

## How "where did I leave off" works

Processed files are **moved out** of `Inbox\`, so the Inbox itself is the to-do list. Saying "the
oldest year in the Inbox" each session automatically advances to the next year.

## What it does and doesn't do

- **Photos:** dates and sorts, and deletes byte-identical duplicates, keeping one. The culler flags
  junk to `Review\`, meh scenery to `Review\Scenery\`, and food to `Review\Food\`. Funny screenshots
  go straight to the library `Funny\`, auto-kept, not staged or reviewed. It keeps the sharpest of a
  near-dup group, with the rejects preserved in `Duplicates\`. It keeps both copies of an edit only
  when the crop or framing differs.
- **Videos:** dates and sorts, and removes byte-identical clips only. It cannot watch videos, so it
  does not judge video quality or near-duplicates. Review those manually.
- **HEIC:** ImageMagick converts to a temp JPG to review. Otherwise it date-sorts HEIC and flags it.

## Storage note

This git repo is for the **tool only** (agents, `scripts\`, docs). `.gitignore` keeps scratch media
out of version control, and keepers live in OneDrive (not git). Access the library from onedrive.com
or your phone anywhere. A dead laptop loses nothing once sync completes.

## Models

`photo-sorter` runs on **Haiku** (it just invokes the engines and reads a summary, so it is cheap).
`photo-culler` runs on **Sonnet** (good visual judgement at low cost, kept low by downscaling +
montages). For ~16k photos, do one year per run (≈5-6 runs). Ongoing imports are tiny.

## License

Licensed under the GNU Affero General Public License, version 3 only ([`LICENSE`](LICENSE)). A
narrow exception ([`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md)) covers modules that implement
the `VisionCuller` vision-provider interface and use no other part of Sluice. "Sluice" is a
trademark of its maintainer, and a fork must use a different name
([`TRADEMARK.md`](TRADEMARK.md)). This is a display project with no support promise. See
[`CONTRIBUTING.md`](CONTRIBUTING.md).
