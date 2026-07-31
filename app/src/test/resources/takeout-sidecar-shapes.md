# Observed Takeout sidecar naming shapes

What the orphan sweep owes each sidecar naming shape Google was actually observed to emit. Read by
`TakeoutSidecarShapesTest`, which asserts the Verdict column against `SidecarSweep`.

Every name here is synthetic. The shapes, and the counts in the Seen column, come from surveying one
real Google Takeout export of 14,789 sidecars in June 2026. That export is gone. This file is what
remains of it, so a later change can still be judged against real-world naming instead of against
invented cases.

A Seen count of 0 means the shape is documented in `takeout-sidecar-pairing.md` but that particular
export carried none of it. Those rows still matter. The plain `<name>.<ext>.json` form is what an
export from before late 2024 is made of.

A shape appears twice where the verdict turns on whether its media is still there. `-` in the Media
column means nothing is left in the directory.

Counting a repeated shape once, the Seen column accounts for every sidecar in the survey.
13,848 + 144 + 18 + 773 + 4 + 1, plus the single excluded shape below, is 14,789. Two kinds of row
sit outside that sum. `edited-copy-shares-original` counts 8 *media* files rather than sidecars,
since the sidecar it shares is already inside the 13,848. The rows at 0 are shapes this export
happened not to carry.

## One shape is missing from the table on purpose

A suffix that is both truncated and dup-numbered (`SAMPLE_0007.jpg.supple(1).json` beside
`SAMPLE_0007(1).jpg`) is swept while its media is still in the Inbox. The surveyed export held one
such file. It was examined and left alone as not worth the fix, for the reasons written up under
"Known limitation" in `app/docs/design/domain/scan/sidecar-sweep.md`.

It has no row below because asserting the current verdict would record it as a rule worth
preserving, which it is not.

## Shapes

| Shape                           | Sidecar                                             | Media                                               | Verdict | Seen  | Why                                          |
|---------------------------------|-----------------------------------------------------|-----------------------------------------------------|---------|-------|----------------------------------------------|
| full-supplemental               | SAMPLE_0001.jpg.supplemental-metadata.json          | SAMPLE_0001.jpg                                     | KEPT    | 13848 | owner key equals the media name              |
| full-supplemental-spent         | SAMPLE_0001.jpg.supplemental-metadata.json          | -                                                   | SWEPT   | 13848 | nothing left owns it                         |
| plain                           | SAMPLE_0002.jpg.json                                | SAMPLE_0002.jpg                                     | KEPT    | 0     | owner key equals the media name              |
| plain-spent                     | SAMPLE_0002.jpg.json                                | -                                                   | SWEPT   | 0     | nothing left owns it                         |
| part-truncated-suffix           | SAMPLE_0003.jpg.suppleme.json                       | SAMPLE_0003.jpg                                     | KEPT    | 144   | paired through the prefix fallback           |
| part-truncated-suffix-spent     | SAMPLE_0003.jpg.suppleme.json                       | -                                                   | SWEPT   | 144   | a real sidecar, and nothing owns it          |
| dup-numbered-plain              | SAMPLE_0004.jpg(1).json                             | SAMPLE_0004(1).jpg                                  | KEPT    | 0     | dup number moves before the extension        |
| dup-numbered-supplemental       | SAMPLE_0005.jpg.supplemental-metadata(1).json       | SAMPLE_0005(1).jpg                                  | KEPT    | 18    | dup number comes off before the suffix strip |
| dup-numbered-supplemental-spent | SAMPLE_0005.jpg.supplemental-metadata(1).json       | -                                                   | SWEPT   | 18    | nothing left owns it                         |
| edited-copy-shares-original     | SAMPLE_0006.jpg.supplemental-metadata.json          | SAMPLE_0006-edited.jpg                              | KEPT    | 8     | the pairing maps the edited copy to it       |
| fully-truncated                 | Screenshot_2019-01-01-00-00-00-00_000000000000.json | Screenshot_2019-01-01-00-00-00-00_000000000000d.jpg | KEPT    | 773   | 46-char prefix of a remaining media name     |
| fully-truncated-spent           | Screenshot_2019-01-01-00-00-00-00_000000000000.json | -                                                   | SWEPT   | 773   | 46 characters says it was a cut sidecar name |
| album-descriptor                | metadata.json                                       | -                                                   | KEPT    | 4     | names no media file, too short to be a cut   |
| export-manifest                 | user-generated-memory-titles.json                   | -                                                   | KEPT    | 1     | same rule                                    |
| unrelated-app-file              | notes.json                                          | -                                                   | KEPT    | 0     | same rule                                    |
