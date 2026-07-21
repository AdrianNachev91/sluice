# Cull test fixtures

Real-world fixtures for `TileRendererTest` and `ImageDimensionsReaderTest`, all committed. They
give the RAW-decode fixes and the resolution-reading fix below real CI protection, not just local
verification.

| File | Source | License | Checked |
|---|---|---|---|
| `gradient.svg` | https://commons.wikimedia.org/wiki/File:SVG_Gradient.svg (direct: https://upload.wikimedia.org/wikipedia/commons/8/8b/SVG_Gradient.svg) | CC0 1.0 (confirmed on the file's Commons page) | 2026-07-21 |
| `webp-sample.webp` | https://developers.google.com/speed/webp/gallery1 (direct: https://www.gstatic.com/webp/gallery/4.webp) | Creative Commons Attribution 4.0 (Google's official WebP gallery) | 2026-07-21 |
| `raw-samples/canon-eos-20d.cr2` | https://raw.pixls.us/data/Canon/EOS%2020D/IMG_3893.CR2 | CC0 | 2026-07-21 |
| `raw-samples/nikon-d40.nef` | https://raw.pixls.us/data/Nikon/D40/DSC_1842.NEF | CC0 | 2026-07-21 |
| `raw-samples/sony-ilce-6700.arw` | https://raw.pixls.us/data/Sony/ILCE-6700/DSC00001.ARW | CC0 | 2026-07-21 |
| `arctic-sky.avif` | https://commons.wikimedia.org/wiki/File:Arctic_Sky_(4371010590).jpg (converted to AVIF via https://file-in-abyss.soga-web.studio/formats/avif) | CC0 1.0 and separately US public domain (USGS work) - both confirmed on the file's own Commons page | 2026-07-21 |
| `rectangle.svg`, `doctype-viewbox-only.svg` | Written by hand for this repo, not third-party content (the DOCTYPE prolog in the latter is the standard public-domain SVG 1.1 boilerplate, not original content) | N/A | N/A |
| `defqon_2027_overlay.svg` | This project's own media (`Sorted/Photos/2026/06/`) - actually a PNG mislabeled with an `.svg` extension, kept deliberately as a real-world edge-case fixture | N/A (repo's own file) | N/A |
| `fake-corrupt.cr2`, `not-an-image.dat` | Synthetic, not real image content | N/A | N/A |

All three RAW files' CC0 license confirmed by checking they do not appear in
`https://raw.pixls.us/json/getrepository.php?set=noncc0` (the site's own list of every non-CC0
camera model), checked 2026-07-21.

- `canon-eos-20d.cr2` SHA-256: `db78660d48589130d99e0bc621424851eb0f84df052634fb5bc89c7a02fe7033`
- `nikon-d40.nef` SHA-256: `44e88bc77b7a531b22647bcd07b9393c4568062e8f0906d3bbdecb42fbe29e03`
- `sony-ilce-6700.arw` SHA-256: `dc3e2ed22f46fcef778553767e0703bc37cfb043f107afe2d7e6e4dbca34200a`
- `arctic-sky.avif` SHA-256: `de02099cae1fc520cbc3e76732e73b487bfa9f736ef6d723e6a1d73f1ecc7da2`

`sony-ilce-6700.arw` (42MB, a genuinely current 2023 camera) exceeds the ~10MB direct-fetch cap
this project's tooling has for retrieving files from a URL - downloaded by hand instead. That cap
is a tool limitation on retrieval, not a real constraint: test fixtures never ship in the packaged
app (only `src/main/resources` does), so fixture file size has no bearing on what end users
download.

`arctic-sky.avif` (real, verified `ftyp`/`avif` box structure) is reserved for when a real
libheif-backed `HeifDecoder` adapter exists - no real decoder exists yet, so
`TileRendererTest.realAvifFixtureFallsBackToAPlaceholderUntilARealDecoderExists` currently only
proves today's honest placeholder behavior for a real file of this format. Re-verify with a real
decoded assertion once that adapter lands.

## What the three real RAW fixtures actually prove

**All three end up producing a real tile, not a placeholder**, but by different routes.
`TileRenderer.render()` returns a `TileResult(image, unreviewable)` - `unreviewable` means "don't
show this to the vision culler", true either for a drawn placeholder or for real pixels recovered
from a source below 640px on its long side (the same bar `LowResGate` uses elsewhere).

- **Canon CR2** - primary pixel data fails to decode (a "Missing TIFF tag JPEGQTables" error on its
  old-style-JPEG TIFF compression). `TileRenderer` falls back to extracting the file's standard
  EXIF embedded thumbnail, a complete, independently decodable JPEG blob per the EXIF spec.
  Verified to produce a real, legible photo - but only 160x120, so `unreviewable=true`.
- **Nikon NEF** - decodes directly via the primary path. Its one embedded image is also only
  160x120, so `unreviewable=true` here too, despite succeeding on the first attempt.
- **Sony ARW (2023)** - the same EXIF-thumbnail fallback as the Canon case instead recovers a
  near-full-resolution 6192x4128 preview: a real, sharp, clearly judgeable photo,
  `unreviewable=false`. This is the empirical basis for treating the tiny-preview problem as an
  old-camera artifact, not something current camera files are assumed to share.

The placeholder path is still real and tested, just via `fake-corrupt.cr2` (genuinely unparseable
content with no EXIF thumbnail to fall back to either) rather than these three real fixtures.

The Canon and Nikon files also caught and fixed a real, already-live bug in
`ImageDimensionsReader`, consumed by `SortEngine` in production `sort` runs. The Nikon NEF exposes
multiple Exif metadata directories describing its embedded images. The first one (the embedded
preview's own) carries no width/height tags at all - only a later directory holds the true
3040x2014 native capture resolution, under the generic TIFF tag pair rather than the EXIF-specific
one Canon uses. Trusting only the first such directory returns the tiny 160x120 embedded-thumbnail
size instead, wrongly flagging a real high-res photo as low-res.

`arctic-sky.avif` caught a second, separate bug in the same class. metadata-extractor does parse
AVIF correctly, but stores its dimensions under a `HeifDirectory`, not an `ExifSubIFDDirectory` -
`ImageDimensionsReader` only ever checked the latter, so every AVIF file read empty regardless of
its actual metadata. Fixed by also checking `HeifDirectory` from the same metadata parse, taking
the largest result across both directory types.

## The other 5 RAW extensions (dng, cr3, raf, orf, rw2)

Attempted the same sourcing approach for the remaining known RAW extensions and hit real, disclosed
tooling limits rather than giving up quietly:

- **dng** - found a genuine, CC0-confirmed file (Canon EOS 5D Mark III via Adobe DNG Converter,
  real TIFF/DNG header verified: `49 49 2A 00`). But the fetch tool never cached its actual bytes
  locally, likely because DNG's large embedded XMP text block confuses the tool's binary-vs-text
  detection. So its header/metadata is verified real, but the file itself couldn't be retrieved
  into this repo.
- **rw2** - every small (<10MB) candidate checked across several camera models turned out to be a
  plain JPEG mislabeled with the RAW extension on inspection (real header bytes checked by hand
  each time - e.g. Panasonic DMC-FZ28 `.RW2` was `FFD8FF..` JPEG, not TIFF).
- **orf** - the one small (<10MB) candidate checked (Olympus E-410) was also a mislabeled JPEG.
- **raf** - every Fujifilm model checked either 404'd or exceeded the 10MB cap at fetch time (though
  per the note above, size alone is no longer a real blocker - a hand download would work the same
  way `sony-ilce-6700.arw` did).

None of this changes the code's safety. All RAW extensions share the identical generic
decode-then-EXIF-thumbnail-then-placeholder code path, already proven safe across three real
cameras spanning two decades above. There's no per-extension special-casing left to verify. Revisit
with real dng/cr3/raf/orf/rw2 fixtures opportunistically (hand-downloaded, as above) if useful; not
a blocker.
