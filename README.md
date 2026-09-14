# Sluice

Sluice sorts a pile of photos into a dated library, and helps you throw out the junk. It is a
desktop application for Windows, macOS and Linux, with a command-line interface alongside it for
scripting and for driving it from an AI agent.

Drop a photo dump into an Inbox folder. Sluice dates each file, dedupes it against what you already
have, and files it into your library by year and month. A separate pass judges the rest.
Near-duplicates get grouped so you pick the keeper. Junk and low-value shots go to a Review folder
for a second look. Everything else moves into your library. Nothing is ever deleted unless the exact same
bytes already exist somewhere safe - see "The safety model" below.

## Installing

Download the build for your platform from the
[latest release](https://github.com/AdrianNachev91/sluice/releases/latest). Installed copies update
themselves in the background.

- **Windows:** `sluice.exe` is a self-installing bootstrapper. Requires Windows 10 (build 17763,
  October 2018 Update) or later.
- **macOS:** download the zip for your Mac (Apple Silicon or Intel), unzip it, and drag the app to
  Applications. Requires macOS 11 or later, either architecture.
- **Linux:** the `.deb` installs on Debian- and Ubuntu-based distributions. Requires glibc 2.34 or
  newer, which covers Debian 12, RHEL 9 and Ubuntu 22.04 onward.

The command line ships inside the same install; `sluice --help` lists every command once it's on
your `PATH`.

## Configuration

Sluice starts with nothing configured and shows a welcome screen until you set three folders, either
there or in `config.yml`:

- **Working root** - where Sluice stages everything: your Inbox, media waiting to move to your
  library, and logs. Transient by nature, so it should not sit inside a cloud-synced folder.
- **Library root** - where your keepers live for good.
- **Inbox** - where new media arrives to be sorted. Lives under the working root by default.

The config file lives at an OS-standard location: `%APPDATA%\Sluice\config.yml` on Windows,
`~/Library/Application Support/Sluice/config.yml` on macOS, and
`$XDG_CONFIG_HOME/sluice/config.yml` (or `~/.config/sluice/config.yml`) on Linux. A fully commented
copy describing every setting ships alongside the app, and inside this repository at
[`app/src/main/resources/config.example.yml`](app/src/main/resources/config.example.yml). The app's
own copy is never read: Settings rewrites the real file without comments on every save.

## Judging your photos: two ways

Sluice never judges a photo itself. Something else has to look at each one and decide what to keep,
and you choose what that is:

- **An agent you run yourself** (the default, `external-agent`). Sluice writes photo contact sheets
  to disk and prints instructions. Any coding agent you already have - Claude Code and similar
  tools - reads them and writes its decisions back as JSON. Free beyond what you already pay for
  that agent. Most agents call out to a remote model to judge the sheets, the same way they call
  out to answer any other question you ask them. This is not a local-only path. It runs under
  whatever privacy and data-handling terms your agent already operates under.
- **A vision API called from inside the app** (`anthropic` today; the provider is pluggable). Sluice
  calls the model directly and shows you a running cost estimate. This spends from your provider
  account balance on every run.

Run `sluice skill` to print ready-to-save agent instructions for the first option.

### Provider caveats

A vision provider is a swap-in, not a drop-in equal. Before trusting a new, weaker, or free model:

- **Parity is not free with the plug.** Only the reference provider is validated by default. Earn
  trust in a different or free model against a known scope first. Check three things: schema
  validity, a sane distribution of judgements, and a spot-check that it never kept a photo it
  should have deleted.
- **Structured-output discipline varies.** Sifting depends on a valid decision file per contact sheet
  and on reading fine detail in small tiles (a photo of a screen, a document). A weaker model misses
  more of both. Sluice validates every shard and fails loudly rather than silently mis-filing, so a
  shaky provider costs you redone sheets, not lost photos.
- **Free tiers have quotas.** They rate-limit hard under anything but light, incremental use.
- **The safety net holds regardless of provider.** Never-delete-media, routing anything discarded
  to Review instead of removing it, and a hard validation gate on every decision file. All three
  apply no matter which provider judged your photos. A bad provider wastes your time. It does not
  lose photos.

## The safety model

Two invariants hold regardless of what else changes:

- **Sluice never deletes a photo or video unless the exact same bytes already exist somewhere
  safe.** That means in your library, already staged, or freshly verified at their destination.
  Everything else it sets aside is moved into a staging folder, never removed. There are no bulk or
  recursive deletes, and no file is ever silently overwritten; a naming collision gets a suffix
  instead.
- **A file Sluice cannot date confidently goes to a clearly-named Unsorted folder** rather than a
  guessed date. It never fabricates a folder for a photo it isn't sure about.

**Library-integrity assumption.** Sluice trusts its own index as proof that a file already lives
safely in your library. It does not re-verify that a recorded path still exists on disk between
runs. Between runs, do not hand-edit, move, or delete files inside your library folders outside the
app. Do not restore your library from a backup either. The index can silently drift from reality if
you do, and a library broken this way is outside what Sluice can detect or defend against.

## Support

This is a display project built and maintained by one person, not a commercially supported product.
Bug reports and feedback are genuinely welcome, and the app itself will sometimes point you at
opening one. Active support, and a guaranteed response, should not be expected. This is a
statement about support, not about quality: bugs are treated seriously when they're found, there's
just no promise of a maintainer standing by.

## Versioning

Sluice follows semantic versioning across three surfaces a script or agent might depend on. They
are the `config.yml` schema, the `--json` output of every command, and the `decisions-NNN.json`
shard format an external agent writes back. Any breaking change to one of the three raises the
major version.

1.0.0 is Sluice's first public release, so there is nothing to migrate from. Future breaking
changes will get their own notes here.

## License

Licensed under the GNU Affero General Public License, version 3 only ([`LICENSE`](LICENSE)), with a
narrow exception for vision-provider plugins ([`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md)).
"Sluice" is a trademark of its maintainer, and a fork must use a different name
([`TRADEMARK.md`](TRADEMARK.md)). Before opening an issue or a pull request, read
[`CONTRIBUTING.md`](CONTRIBUTING.md) and [`CLA.md`](CLA.md).

Packaged with [Hydraulic Conveyor](https://conveyor.hydraulic.dev), free for open-source projects.

## Design docs

The design docs live in [`app/docs/design`](app/docs/design). GitHub renders them, mermaid
flowcharts included, so reading them needs nothing else.

That folder is also an [Obsidian](https://obsidian.md) vault. Obsidian adds a graph view and
backlinks over the links between the docs, which GitHub can only show one page at a time. It is
optional, and the committed settings enable core plugins only, so there is nothing to install. To
open it, choose "Open folder as vault" and pick `app/docs/design`.
