---
name: sluice
description: Drive Sluice, a desktop photo and video organiser, from its command line. Use this to sort a camera dump or a Google Takeout export into folders by year, to sift sorted photos for junk and near-duplicates with a vision model, to move keepers into the user's Library, to import files into their Inbox, and to recover a sift that stopped part-way. Also use it to set Sluice up for the first time, or to work out why a sluice command refused. Triggers include sluice, sort my photos, organise my photos, cull my photos, deduplicate photos, tidy my camera roll, and file a Takeout export.
---

# Driving Sluice from the command line

Sluice organises photos and videos. It files a dump of media into folders by year and
month, and sets junk and near-duplicates aside. The keepers move into a Library folder the
user keeps for good. It runs on Windows, macOS and Linux, as a desktop application and as
the `sluice` command these instructions cover.

These instructions describe {{version}}. Run `sluice skill` on the user's own install to
print the copy that matches their build.

You are driving somebody's photo collection. Read "Ask before you run these" below before
you run anything.

## What Sluice does to files

**It never deletes media unless the same bytes already exist somewhere else.** Four
deletions are allowed:

- A file in the Inbox whose bytes are already in the Library. That is a re-import, and the
  Library's copy survives.
- Byte-identical copies inside one batch, keeping one.
- The original of an `import --move`, deleted only after its bytes are read back and
  hashed in the Inbox.
- A spent `.json` sidecar, which is metadata rather than media.

Everything else is a move or a copy. A media name collision gets a ` (2)` suffix rather
than an overwrite.

The media guarantee rests on hashes recorded in the Library index. It does not rest on the
process lock below.

**The sidecar rule is wider than Google Takeout, and it is the one deletion with no copy
anywhere.** A `.json` in the Inbox is swept when its name looks like a per-photo sidecar.
It is also swept when the name it derives is 46 characters or longer, which is what a name
Takeout cut short looks like. So an unrelated `.json` with a long name goes too. A short
one, like `metadata.json`, is kept.

**One Sluice at a time per working folder.** A running command claims the user's working
folder, so a second command, or the desktop application, is refused while it holds it.
This is a convenience guard against two engines writing one tree. It is not a safety
device, and nothing about the never-delete rule depends on it. A read-only command
(`runs`, `skill`) claims nothing, so it still answers while the desktop application is
open.

**There is no undo.** A sort or a move can be reversed by hand, with one exception. The
sidecars a sort sweeps are gone, so a reversed Takeout import comes back without its
metadata. Photos routed for a second look stay under the working folder, in `Review`,
`Duplicates`, and `Unreviewable` for the ones a sift could not judge. Discarding a sift
archives its records for 30 days. Purging deletes them outright.

## Ask before you run these

Sluice does not enforce this list. Three rows below ask for `--yes` and refuse without it,
and an agent can type `--yes` as easily as a person can. Nothing here is made to prove a
person authorised it, so the whole of the check is you performing it.

**Ask the user, in plain words, and wait for their answer before running any of these.**
Say what it costs or what it loses, then run what they choose. Do not choose for them, and
do not run one of these because a previous one failed.

What the first four rows cost depends on the configured provider. On `anthropic`, Sluice
calls a vision model and spends from the user's account balance. On `external-agent`, the
default, Sluice sends nothing anywhere and charges nothing. The judging falls to you, or to
another agent you hand it to, so every sheet spends the usage of whichever subscription or
account is behind that. Neither route is free, and on both a re-sift pays for the same
sheets twice. The rows about losing records or moving files apply whichever provider is
configured.

| Command                            | Ask first because                                                                      |
|------------------------------------|----------------------------------------------------------------------------------------|
| `sift`                             | It sends photos to the user's vision provider and spends from their account balance    |
| `resume`                           | It re-sends every sheet without a valid answer, so a failed sift spends again          |
| `resume --allow-partial`           | It applies what was judged and leaves the rest unreviewed, which nothing revisits      |
| `redo`                             | It frees a run's rejected sheets, and the next sift spends on those sheets again       |
| `discard`                          | It gives up sheet decisions already paid for                                           |
| `answer <run> <key> DISCARD --yes` | Same as `discard`, reached through a finding                                           |
| `purge --yes`                      | It deletes every finished run's records, with no way back                              |
| `commit all`                       | It moves every sorted year into the Library at once, from one short command            |
| `import --move`                    | It deletes each original once the file is in your Inbox                                |
| `--sluice.paths.library-root=`     | It files that run into a different Library, and copies nothing across to it            |

A retry is the user's call, every time. Do not send a sheet again on your own judgement,
whatever it failed on.

`sort`, `runs`, `troubleshoot`, `rescue`, `commit <year>`, `import` and `skill` need no
such question.

## Setting Sluice up

Sluice ships with no folders configured, and refuses every job until three are set. Give
the user the exact text or command to run themselves. Do not ask them to paste a
credential to you.

The settings file is YAML, and lives in an OS-native folder:

| OS      | Path                                                                              |
|---------|-----------------------------------------------------------------------------------|
| Windows | `%APPDATA%\Sluice\config.yml`                                                     |
| macOS   | `~/Library/Application Support/Sluice/config.yml`                                 |
| Linux   | `$XDG_CONFIG_HOME/sluice/config.yml`, or `~/.config/sluice/config.yml` when unset |

The three folders it needs. Write the user's own paths in their platform's own shape:

```yaml
# macOS and Linux
sluice:
  paths:
    repo-root: /home/example/Sluice
    library-root: /home/example/Pictures/Sluice
    inbox: /home/example/Sluice/Inbox
```

```yaml
# Windows. Forward slashes work here too, and stay safe if the value is ever quoted.
sluice:
  paths:
    repo-root: C:\Users\example\Sluice
    library-root: C:\Users\example\Pictures\Sluice
    inbox: C:\Users\example\Sluice\Inbox
```

`repo-root` is the working folder, holding the Inbox, sorted media and logs. It is
transient staging, so keep it off a cloud-synced folder. `library-root` is where keepers
live for good. The Library and the Inbox must not contain each other. The Inbox must not
be, or contain, the working folder.

Every setting is documented in the `config.example.yml` that ships with the app. An
environment variable overrides the file: `sluice.paths.inbox` is `SLUICE_PATHS_INBOX`.

### Changing a folder after it is set

Three routes, and they are not interchangeable.

Edit the settings file. Permanent, and the next command reads it.

Name the setting on one command, as `--sluice.paths.library-root=/home/example/Photos`.
That run uses it and nothing is written down. The environment variable does the same for
every command in that shell.

Send the user to the desktop application's Settings. That is the only route that does
anything about the photos already filed. It makes them choose: copy the old Library into
the new folder, or start a fresh record.

Neither of your two routes moves a photo. Point Sluice at a new Library and everything
already filed stays in the old one. The record of what was filed does not move either, so
a photo already in the old Library is read as one Sluice has seen. A later `sort` clears
it from the Inbox and it never reaches the new folder. Tell the user this before you
change their Library root, and prefer sending them to the desktop application.

### The vision provider, and its key

Sluice ships configured for `external-agent`, which needs no key and no account of its
own. On that provider, `sift` builds sheets and waits for an agent to write decisions.
Reading them spends the usage of whichever agent does it rather than Sluice's, whether that
is you or one you hand it to. The other provider, `anthropic`, calls a vision model from
inside the app and spends from the user's own account.

**A key never goes in the settings file.** Sluice reads no credential from there, so one
pasted in is an ignored setting sitting in plaintext.

Two routes carry it, and both keep the value off this conversation. The environment
variable `ANTHROPIC_API_KEY`, or the desktop application's Settings screen. Settings uses
the operating system's credential store, falling back to a file in a `secrets` folder
beside the settings file where no store answers.

**Do not ask the user for the key's value, do not read it, and never put it in a
command's arguments.** Not from the environment, not from the `secrets` folder, not from
a file they point you at. You never need the value: Sluice reads it for itself, and an
argument carrying it lands in shell history. Give the user something to run themselves,
and let them type the key into their own terminal:

```bash
# macOS and Linux. Works in bash and in zsh, which is the macOS default.
printf 'Anthropic API key: '
stty -echo; read KEY; stty echo; printf '\n'
export ANTHROPIC_API_KEY="$KEY"
```

That lasts for the one terminal session. To keep it, add the `export` line to the user's
shell profile: `~/.zshrc` on macOS, `~/.bashrc` on most Linux.

```powershell
# Windows
$key = Read-Host -AsSecureString "Anthropic API key"
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($key))
[Environment]::SetEnvironmentVariable("ANTHROPIC_API_KEY", $plain, "User")
```

That sets it for the user's account, so it survives a reboot. A new terminal is needed
before Sluice sees it. The desktop application's Settings screen is the other route on
any machine that has a desktop.

Everything other than the key is fair to ask for, and fair to read. Secrets live outside
the settings file by design, so that file boundary is the boundary to respect.

## The verbs

| Command        | What it does                                                                       |
|----------------|------------------------------------------------------------------------------------|
| `sluice`       | On its own, prints help at exit 0. Arguments naming no verb are a usage error      |
| `app`          | Opens the desktop application. Takes no arguments                                  |
| `runs`         | Lists the sifts on disk and their progress. Reads only                             |
| `sort`         | Files the Inbox into `Sorted`, under the year each file was taken                  |
| `commit`       | Moves what is in `Sorted` into the Library                                         |
| `rescue`       | Moves a `Review` folder's files into the Library, then removes the folder if empty |
| `sift`         | Judges what is in `Sorted`, and moves anything it does not keep out of it          |
| `resume`       | Continues a sift that is waiting                                                   |
| `import`       | Brings folders and files into the Inbox                                            |
| `troubleshoot` | Repairs what can be repaired without asking, and reports what is still open        |
| `answer`       | Resolves one open finding, by its key and one of its option ids                    |
| `redo`         | Frees a run's rejected sheets and returns fresh instructions to judge them         |
| `discard`      | Gives up on a run, archiving its records for 30 days                               |
| `purge`        | Deletes the records of every finished run, with no way back                        |
| `skill`        | Prints these instructions                                                          |

Two flags work on every command. `--json` writes the result to standard output as one
JSON document. `--quiet` reports no progress and leaves the result unchanged.

`--version` works on `sluice` itself and on no verb.

### Scope, which is not the same on every verb

Three verbs take a scope, and no two take it alike. Reading the wrong row is the commonest
way to get a refusal here.

| Verb     | Bare, with no scope          | Year | `--months`                 | `--oldest N`                   |
|----------|------------------------------|------|----------------------------|--------------------------------|
| `sort`   | The oldest year in the Inbox | yes  | a span only: `6-8`         | yes, ordered by date           |
| `commit` | Refused. Write `commit all`  | yes  | a span only: `6-8`         | not accepted                   |
| `sift`   | Refused                      | yes  | a span or a list: `6,8,11` | yes, ordered by file timestamp |

A year is four digits with no leading zero. `--months` refuses a value mixing the two
spellings, such as `6-8,11`, on every verb. `sift` is the one that takes a gapped list,
because sifting spends from the user's account balance and skipping a month is worth the
extra spelling there. `sort` and `commit` take a span.

A year and `--oldest` name different photos, so no verb takes both.

### Addressing a run

`resume`, `troubleshoot`, `answer`, `discard` and `redo` each take a run. Two spellings
work: the scope tag exactly as `runs` prints it, or the run folder's absolute path.
A relative path is read as a tag, and refused when nothing matches it.

## Reading the result

Standard output carries the answer and nothing else, so a run can be piped straight into
another program. Standard error carries progress, warnings and refusal text, under
`--json` as well.

With `--json`, standard output is exactly one document on exactly one line:

```json
{"command": "sift", "status": "WAITING", "result": {}}
```

`result` is absent where the command had nothing to report. `command` is absent when the
app refused before it read the command, which happens on a broken settings file.

| Exit | `status`    | What it means and what to do next                                                                          |
|------|-------------|------------------------------------------------------------------------------------------------------------|
| 0    | `DONE`      | It did what it was asked                                                                                   |
| 1    | `FAILED`    | Something nothing has a reading for. `result.trace` and standard error carry the trace. Report it as a bug |
| 2    | none        | The arguments were not understood. No document is written. Read standard error, fix the command            |
| 3    | `REFUSED`   | Nothing happened. `result.kind` says which refusal; standard error says it in words                        |
| 4    | `WAITING`   | Work is left. From `sift` and `resume`, `result.reason` names which of the three below                     |
| 5    | `BLOCKED`   | The run needs a look. Run `troubleshoot` on it, unless it was `troubleshoot` that said this                |
| 6    | `CANCELLED` | The run was stopped part-way. It still reports what it did before stopping                                 |

On a 4 from `sift` or `resume`, `result.instructions` carries the judging text where there
is one.

`troubleshoot` also answers 4 and 5, and its payload carries `state` rather than `reason`.
Its own 5 means it has already looked and something is still wrong, so running it again
only repeats itself. Read `result.open[]` instead.

A usage error writes no document. Neither does `--help` or `--version`, which print their
text at exit 0. Everything else writes one.

A refusal is one code with a typed reason, rather than a code each. Switch on
`result.kind`, never on the sentence.

### Stopping a running command

Type `c` and press Enter. The run finishes whatever it is on, then reports what it did.
What that is depends on the verb: `sort`, `commit`, `rescue` and `import` stop between
files, and `sift` stops between sheets, which can be about a minute. Type `c` again to
abandon a file mid-copy instead of finishing it.

`troubleshoot`, `discard` and `purge` offer no stop, and say so rather than printing the
hint. Their work is a handful of small files and none of them reads the signal.

Ctrl-C is not caught and kills the process outright, which leaves no report.

## Sifting, end to end

A sift builds contact sheets of the sorted photos, has them judged, and moves anything it
does not keep out of `Sorted`. `funny` leaves too, and where each kind lands is below. On
`external-agent` the judging is you, or another agent on this machine you hand it to.

Where each photo lands: a judged category becomes a folder under `Review`, one per
category, for the user to look through. The exception is `funny`, which a sift moves
straight into the Library. So a sift reaches the Library without a `commit`, for that one
category. Photos that look like near-copies of each other go to `Duplicates`, one folder
per group, with the kept one copied in beside them. Anything the sift could not judge goes
to `Unreviewable`.

```
sluice sift 2019                    # builds the sheets
                                    # exit 4, reason SHARDS_OUTSTANDING
                                    # -> prints the instructions for judging them
sluice resume 2019                  # applies them
                                    # exit 0 when every sheet came back
                                    # exit 5 when something is wrong
sluice troubleshoot 2019            # repairs what it can, lists what it cannot
sluice answer 2019 <key> <option>   # resolve one finding
sluice resume 2019                  # once troubleshoot reports nothing open
```

**A sift that stops with its sheets unjudged prints what to do about them**, on standard
output under the waiting sentence, and as `result.instructions` in the document. That text
is the whole contract. Which file to write per sheet, what belongs in it, and how to match
a tile to the photo it came from.

It is written from the categories that run was prepped under, not from the settings as they
stand now. So read the text that run printed rather than one you kept from an earlier sift.
Read it before you start, and if you hand the judging on, hand on the whole of it rather
than a summary.

`sift` prints its expected cost on standard error before it starts, on a provider that
spends. It never prompts and never gates on it.

### The three ways a sift pauses

All three are exit 4. `result.reason` tells them apart.

| `reason`             | What happened                           | Next move                           |
|----------------------|-----------------------------------------|-------------------------------------|
| `SHARDS_OUTSTANDING` | The sheets are built and unjudged       | Follow the instructions it printed  |
| `CANCELLED`          | Somebody stopped the run                | `resume` when they want to continue |
| `CEILING_REACHED`    | The run went far past its expected cost | Read what it spent, then decide     |

**A ceiling stop is not a setting, and there is nothing for the user to raise.** Sluice
computes a limit per run from what one sheet was expected to cost. Reaching it means the
run was consuming several times that. Look at the configured model and the sheet grid
before resuming. A run whose cause has not changed stops again.

### The two ways a sift ends at exit 5

`result.outcome` tells them apart, and they need different next moves.

**`Blocked`** means every sheet came back and applying them was refused. `result.scope` and
`result.prepDir` name the run, `result.findings[]` says what refused it, and
`troubleshoot <scope>` is the move.

**The provider gave up part way.** The document carries `result.problems`, which is the
provider's own list of what it could not finish, and `result.report` for what was spent
before it stopped. It carries no scope and no prepDir, deliberately: the provider raised
this without them, and inventing an address would hand you one that nothing produced. Use
the scope you asked for. Money was already spent, so this is not a "nothing happened" and
re-running the sift pays again.

### Findings you can answer

`troubleshoot --json` lists what is still open as `result.open[]`. Each entry carries a
`key` and an `options` array. Pass both to `answer`. An entry with no `key` is not
answerable by this route.

| Finding                       | `key` is                 | `options`                                 |
|-------------------------------|--------------------------|-------------------------------------------|
| `DecisionUnreviewableOverlap` | the decision's file path | `TRUST_DECISION`, `TREAT_AS_UNREVIEWABLE` |
| `CorruptSidecar`              | the sheet's name         | `SET_ASIDE`, `APPLY_ANYWAY`               |
| `MissingSource`               | the missing file's path  | `SKIP`                                    |
| `StrayShard`                  | the shard's file name    | `SET_ASIDE`                               |
| `CorruptIndex`                | the index file's path    | `DISCARD`                                 |

`DISCARD` gives up the whole run and needs `--yes`. Ask the user first.

**`InvalidCategory` means a bad decision file, not a settings problem.** A sift records the
categories it was prepped under, so renaming one in Settings leaves sifts already on disk
valid. Rewrite the offending decision file with a category the sift itself declares.

One run is the exception. Where an index had to be rebuilt, nothing on disk remembers the
set it was prepped under, so the configured categories are substituted. On that run alone
the finding can mean a genuine mismatch.

### When answers come back wrong

`redo` sets aside the answers a diagnosis blames and returns fresh instructions for those
sheets. It is not a job, and it refuses when the diagnosis blames no sheet. The sheets it
reopens are judged again by the next `resume`, which spends again on a paying provider.

Nothing caps how often this can go round. When a sheet keeps coming back rejected, put it
to the user: try again, or discard the run. Do not pick for them.
