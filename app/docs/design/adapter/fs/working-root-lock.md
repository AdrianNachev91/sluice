# Working root lock

How `adapter/fs/FileChannelWorkingRootLock` lets one process at a time work in a given working root
(`app/src/main/java/photos/sluice/adapter/fs/FileChannelWorkingRootLock.java`).

The claim is an OS lock held on a `.sluice-lock` marker file inside the root, for as long as the
process holds that root. The kernel drops it when the process dies, whichever way it dies, so a
crash or a deliberate force-quit leaves nothing to clean up. A file recording a process id could not
offer that.

## Taking a claim

```mermaid
flowchart TD
    A["acquire(workingRoot)"] --> B["root = canonical(workingRoot)"]
    B --> C{"already holding root?"}
    C -- yes --> D(["return, nothing to do"])
    C -- no --> E{"root in the registry?"}
    E -- yes --> F(["WorkingRootBusyException"])
    E -- no --> G["add root to the registry"]
    G --> H["open the marker file"]
    H --> I["tryLock()"]
    I -- "null: another process" --> J["close the channel"]
    J --> K["drop the registry entry"]
    K --> F
    I -- "lock taken" --> L["record the claim"]
    L --> M{"was another root held?"}
    M -- no --> N(["holding the new root"])
    M -- yes --> O["close the old channel, drop its entry"]
    O --> N
```

Two orderings in that flow are load-bearing.

**The registry is consulted before the marker is opened.** An OS file lock belongs to the process,
not to the object holding it. On Linux the kernel releases a lock of this kind as soon as any
descriptor for that file closes in the same process. So a refusal that opened the marker and closed
it again would release the lock the first claim still believes it holds. That leaves a claim which
only looks held. Refusing from the registry means no second descriptor is ever opened.

**The new root is taken before the old one is given up.** A refusal throws with the old claim still
intact, so a rejected settings save changes nothing. Doing it the other way round would leave a
process holding neither root whenever the new one turned out to be taken.

## Root identity

`canonical()` normalises the path, then resolves it. Normalising first matters: `toRealPath()`
throws on any component that does not exist, and a relative step like `sub/..` names one that need
not. Resolving matters because a symlink, a junction or a re-spelled path all name one directory,
and the registry has to recognise them as one root. Otherwise a settings save that only re-spells
the path would look like a move onto a root this process already holds. It would then refuse the
user their own root.

A path that does not resolve at all falls back to its normalised form. Claiming it fails a moment
later on the open, which reports the problem in terms of the path the user actually configured.

The one case this does not cover is a directory renamed while its claim is live. The new name
resolves somewhere the registry has never seen, so the marker is opened a second time and the lock
itself does the refusing. The claim is still refused; one descriptor leaks.

## Giving a claim up

Closing the channel drops the OS lock with it. The registry entry goes afterwards, in a `finally`,
which is a trade rather than an oversight. A close that fails frees the key while the lock may still
be held, and nothing in this process can detect that. Holding the key instead would strand the root
until the process exits, which is worse and far easier to hit.

The marker file stays on disk. Deleting it would race a process opening it at that moment, and an
abandoned marker is an empty file.

## What the claim is worth

A convenience, not a safety device, and the difference is worth keeping straight. A delete is
authorised by a hash being present in an append-only index flushed per entry. Two processes
appending can interleave rows or tear a line, but neither can invent a hash that was never
committed. So the promise never to delete a file unless its bytes survive elsewhere does not rest on
this lock. What the lock prevents is a malformed row that fails a later parse, and the confusion of
two engines moving one tree.

Work that only reads never claims a root. A status check from elsewhere would otherwise fail
whenever the desktop app happened to be open, which only teaches people to close it to look at
something.
