# Sluice on the command line

Sluice organises photos and videos. It files a dump of media into folders by year and
month, and can look at the photos themselves and set junk and near-copies aside. What you
keep moves into a Library folder you keep for good.

Everything the desktop application does is also a command. This page is for people who
would rather type than click, and for anyone setting Sluice up on a machine with no
desktop at all. It covers the workflows. For the exact arguments a command takes, run
`sluice <command> --help`.

## The three folders

Sluice ships with nothing configured. It starts, and refuses every job, until three
folders are set.

**The working folder** is where Sluice stages everything: your Inbox, sorted media waiting
to be moved, and its logs. This is transient staging, so keep it off a cloud-synced folder
like OneDrive or iCloud Drive.

**The Library** is where keepers live for good. This is the folder worth backing up.

**The Inbox** is where new media arrives to be sorted.

The Library and the Inbox must not contain each other. The Inbox must not be, or contain,
the working folder. A working folder that contains the Inbox is the ordinary layout.

### The settings file

It is YAML, and it lives in a folder your operating system chooses:

| Operating system | Path                                                                             |
|------------------|----------------------------------------------------------------------------------|
| Windows          | `%APPDATA%\Sluice\config.yml`                                                    |
| macOS            | `~/Library/Application Support/Sluice/config.yml`                                |
| Linux            | `$XDG_CONFIG_HOME/sluice/config.yml`, or `~/.config/sluice/config.yml` when unset |

The three folders, on macOS and Linux:

```yaml
sluice:
  paths:
    repo-root: /home/example/Sluice
    library-root: /home/example/Pictures/Sluice
    inbox: /home/example/Sluice/Inbox
```

And on Windows:

```yaml
sluice:
  paths:
    repo-root: C:\Users\example\Sluice
    library-root: C:\Users\example\Pictures\Sluice
    inbox: C:\Users\example\Sluice\Inbox
```

Every other setting is documented in `config.example.yml`, which ships with the app. That
copy is never read. It exists so each setting has somewhere to be explained that nothing
rewrites.

An environment variable beats the file. The name is the setting in capitals with dots and
dashes as underscores, so `sluice.paths.inbox` is `SLUICE_PATHS_INBOX`. That is the route
to use on a server or in a container, where there may be no config file at all.

### Changing a folder later

Three routes, and they are not interchangeable.

**Edit the settings file.** The permanent one. The next command you run reads it.

**Name the setting on one command**, as `--sluice.paths.library-root=D:\Photos`. That run
uses it and nothing is written down. The environment variable above does the same for
every command in that shell.

**Change it in the desktop application's Settings.** This is the only route that does
anything about the photos already filed. It makes you choose: copy the old Library across
into the new folder, or start a fresh record and leave the old Library where it is.

The command line does neither of those for you. Point it at a new Library and the photos
already filed stay in the old folder. Sluice also keeps a record of everything it has
filed there, and that record does not move either. So a photo already in the old Library
is still read as one Sluice has seen. A later `sort` clears it from your Inbox, and it
never reaches the new folder. Its bytes are safe in the old Library, which is where they
were all along.

## Your first run

```
sluice import ~/Pictures/CameraDump     # bring media into the Inbox
sluice sort                             # file it by year, oldest year first
sluice commit 2019                      # move a sorted year into the Library
```

`import` copies by default and leaves your originals alone. `--move` deletes each original
once the file is in your Inbox.

`sort` on its own takes the oldest year in your Inbox. That is deliberate: a large backlog
is easier to work through a year at a time.

`commit` refuses to run bare. Write `commit 2019`, or `commit all` to move everything
sorted. There is no default, because moving files into your Library on a typo is not a
mistake worth making easy to make.

Nothing above needs a vision model, an account, or a key.

## Sifting

Sifting is the part that looks at your photos. Sluice builds contact sheets of a year's
sorted photos, has each sheet judged, and moves anything it does not keep out of `Sorted`.
One kind of keeper leaves too, and that is `funny`, below.

Junk is always on and is not a card you configure. The rest of the categories are yours:
you set them in your settings file, and Sluice ships with scenery, food and funny to start
from. Each becomes a folder under `Review` for you to look through. **`funny` is the
exception, and it goes straight into your Library.** So a sift reaches your Library without
a `commit`, for that one category.

A sift decides two more things that are not categories. Photos that look like near-copies
of each other are grouped under `Duplicates`, one folder per group. That lets you compare
them before throwing any away. And anything the sift could not judge at all goes to
`Unreviewable`.

**Everything else stays in Sorted, where `commit` will find it.** So the three things that
put photos in your Library take different photos. `commit` takes what a sift left in
Sorted, plus anything you never sifted. `rescue` takes one `Review` folder, once you have
been through it and thrown away what you did not want. And a sift itself takes `funny`.

Sifting is optional. The three commands above it file your media by year and month and
move it into your Library, and that is a complete way to use Sluice. Sift when you also
want the photos themselves looked at, and the ones it decides against moved out of
`Sorted`.

Two providers do the judging, and you choose which in your settings.

**`external-agent`** is the default and needs no key. `sluice sift 2019` builds the sheets
and stops, waiting for decisions. It prints the instructions for judging them as it stops,
so what you hand your agent is that text. Your agent writes a decision file per sheet, and
`sluice resume 2019` applies them. Nothing is sent anywhere by Sluice on this route, and
nothing is charged to a provider account. Your agent still reads every sheet, so the
judging spends whatever subscription or account that agent runs on.

Those instructions are written from the categories that run was prepped under, so they
match the sift they came from even if you have since renamed a category. Reprint them at
any time by running `sluice resume 2019` again on a run still waiting: it stops the same
way and prints them again.

**`anthropic`** calls a vision model from inside the app, using your own account. One
command does the whole thing, and it spends from your account balance.

```
sluice sift 2019            # judge a year
sluice runs                 # what is on disk, and their progress
sluice resume 2019          # continue one that is waiting
```

### What a sift costs, and the limit you cannot raise

On a paying provider, `sift` prints what it expects to spend before it starts. It never
prompts and never stops for you to agree. Nothing is lost by starting a sift, so there is
nothing there to guard.

Sluice also computes a ceiling for each run, from what one sheet was expected to cost. A
run that goes far past it stops and says so. **This is not a setting, and there is nothing
for you to raise.** A run that reaches it was consuming several times what a sheet should.
Look at the model you configured and the sheet grid before resuming. Resuming computes a
fresh ceiling the same way, so a run whose cause has not changed stops again.

## When a sift goes wrong

```
sluice troubleshoot 2019             # repair what can be repaired, list what cannot
sluice answer 2019 <key> <option>    # resolve one of the things it listed
sluice redo 2019                     # reopen the sheets whose answers were rejected
sluice discard 2019 --yes            # give up on the run
sluice purge --yes                   # clear finished runs' records for good
```

`troubleshoot` runs the repairs Sluice can make on its own, then prints what is still
open. Each open item has a key and a set of options. `answer` takes both.

`discard` archives a run's records rather than deleting them. They stay on disk for 30
days. `purge` is the one that deletes, and it says so before it does.

Whatever you discard is not lost from your Library. The photos a run covered stay in your
sorted folders, so a later sift over the same period picks them up again.

## Scope

Three commands narrow to part of your collection, and they do not narrow alike.

| Command  | With no scope                | `--months`                 | `--oldest N`                   |
|----------|------------------------------|----------------------------|--------------------------------|
| `sort`   | The oldest year in the Inbox | a span: `6-8`              | yes, ordered by date           |
| `commit` | Refused. Write `commit all`  | a span: `6-8`              | not accepted                   |
| `sift`   | Refused                      | a span or a list: `6,8,11` | yes, ordered by file timestamp |

`sift` is the one that takes a gapped list, because sifting spends from your provider
account balance. Skipping a month you would rather handle on its own is worth the extra
spelling there. `sort` and `commit` take a span.

## Reading what a command says

Standard output carries the answer. Standard error carries progress, warnings and the
reason for a refusal. So a command can be piped into another program without its progress
arriving in the middle of the result.

`--json` puts the result on standard output as one document on one line, which is what to
use from a script. `--quiet` turns progress off and changes nothing else.

Standard error carries what a person needs and nothing else. The app's own logging is quiet
here, unlike in the desktop application, so a class name and a thread never land in the
middle of a run's progress. Turn it up for one run with `--logging.level.root=INFO` when you
are chasing something down.

| Exit | Meaning                                                             |
|------|---------------------------------------------------------------------|
| 0    | Done                                                                |
| 1    | An unexpected failure. The stack trace is the whole of what is known |
| 2    | The arguments were not understood                                    |
| 3    | Refused. Nothing happened                                            |
| 4    | A run paused with work left                                          |
| 5    | A run is blocked and needs a look                                    |
| 6    | A run was stopped part-way                                           |

Only `sort`, `commit`, `rescue`, `import`, `sift` and `resume` can end in 6. The rest run
to completion once started.

### Stopping a command

Type `c` and press Enter. The run finishes whatever it is on, then reports what it did.
For `sort`, `commit`, `rescue` and `import` that is the file it is copying. For `sift` it
is the sheet it is judging, which can be about a minute. Type `c` again to abandon a file
that is mid-copy rather than let it finish. Ctrl-C is not caught, so it kills the process
outright and you get no report.

This reaches the commands that move files: `sort`, `commit`, `rescue`, `import`, `sift`
and `resume`. The rest are short enough to run to completion once started.

## What Sluice will and will not delete

**Sluice never deletes media unless the same bytes already exist somewhere else.** Four
deletions are allowed:

- A file in your Inbox whose bytes are already in your Library. That is a re-import, and
  the copy in the Library is the one that survives.
- Byte-identical copies inside one batch, keeping one.
- The original of an `import --move`, deleted only after its bytes are read back and
  hashed in your Inbox.
- A spent `.json` sidecar, which is metadata rather than a photo.

Everything else is a move or a copy, and no photo or video is ever overwritten. A name
collision gets a ` (2)` suffix instead.

That guarantee rests on the hashes recorded in your Library index.

**The sidecar sweep is wider than Google Takeout, and it is the one deletion that keeps no
copy.** A `.json` in your Inbox is swept if its name looks like a per-photo sidecar. It is
also swept if the name it derives from is 46 characters or longer, which is what a name
Takeout has cut short looks like. So an unrelated `.json` with a long name goes too. A
short one, such as `metadata.json`, stays.

Sluice's own files are a separate matter. It rewrites those in place as it works: your
settings file when you save, and a run's records as that run progresses.

**One Sluice at a time, per working folder.** A running command claims your working folder,
so a second command, or the desktop application, is refused while it holds it. This is a
convenience guard against two engines writing the same tree at once. It is not a safety
device, and the never-delete rule above does not depend on it. Commands that only read,
`runs` and `skill`, claim nothing, so they still answer while the desktop application is
open.

**There is no undo.** Sorting and moving relocate files, so you can reverse them by hand.
The one thing you cannot get back that way is a swept sidecar: reverse a sorted Takeout
import and it comes back without its metadata.

Photos kept for a second look stay under your working folder, in `Review`,
`Duplicates`, and `Unreviewable` for the ones a sift could not judge. None of that is
thrown away where you cannot find it.

## Driving Sluice with an agent

Sluice carries a skill an agent can learn these commands from. `sluice skill` prints it.
Save it wherever your agent reads its instructions from:

```
sluice skill > ~/.claude/skills/sluice/SKILL.md
```

The skill tells the agent to ask you before running anything that spends your money or
loses work, and never to read your provider key.

**Sluice does not enforce that, and cannot.** It is an instruction to a piece of software
that follows instructions imperfectly. Nothing in Sluice checks that a person agreed, and
nothing could: every proof a command line could demand is a string an agent can produce by
itself. Deliberately so. The point of driving Sluice with an agent is to remove the work
of driving it yourself. A confirmation the agent can answer on your behalf buys nothing.

The text is a starting point, and it is your file once you save it. Edit a line, or tell
your agent to work another way, and that holds. Sluice neither knows nor minds.

So these are the commands to keep an eye on. If you did not agree to one, your agent
should not have run it:

| Command                            | What it does that you would want a say in                         |
|------------------------------------|-------------------------------------------------------------------|
| `sift`                             | Spends from your provider account balance                         |
| `resume`                           | Re-sends every unanswered sheet, so a failed sift spends again    |
| `resume --allow-partial`           | Applies what was judged and leaves the rest unreviewed            |
| `redo`                             | Reopens rejected sheets, and the next sift spends on them again   |
| `discard`                          | Gives up sheet decisions you already paid for                     |
| `answer <run> <key> DISCARD --yes` | The same, reached through a listed problem                        |
| `purge --yes`                      | Deletes every finished run's records, with no way back            |
| `commit all`                       | Moves every sorted year into your Library at once                 |
| `import --move`                    | Deletes each original once the file is in your Inbox              |
| `--sluice.paths.library-root=`     | Files that run into a different Library, copying nothing across   |

Retrying a failed sheet is not capped either. Nothing stops an agent from trying the same
sheet over and over, and each attempt on a paying provider spends again. Sluice's own
per-run ceiling is the only thing that will stop a single run, and it does not span runs.

## Your provider key

Only the `anthropic` provider needs one. `external-agent`, the default, does not.

**The key never goes in the settings file.** Sluice reads no credential from there, so a
key pasted in is an ignored setting sitting in plain text.

Two routes carry it. The environment variable `ANTHROPIC_API_KEY`, or the desktop
application's Settings screen, which stores it in your operating system's own credential
store. Where no credential store answers, Settings falls back to a file in a `secrets`
folder beside your settings file. That file is restricted to your own account, one
credential to a file. On a filesystem that cannot restrict it that way, Sluice refuses to
store the key at all rather than leave it readable and tell you otherwise.

On a machine with no desktop, the environment variable is the route rather than a
workaround. A headless Linux box often has no credential store to write to in any case.

Do not put a key in a command's arguments. It lands in your shell history.

When a command fails for want of a key, Sluice says which places it looked. It names the
environment variable too, so you can see which route it expected.
