# Media store

How `adapter/fs/NioMediaStore` places a file into a destination directory without ever
overwriting an existing one
(`app/src/main/java/photos/sluice/adapter/fs/NioMediaStore.java`).

## Collision-safe placement (shared by move and copy)

```mermaid
flowchart TD
    A["ensureDirectory(destDir)"] --> B["candidate = destDir/leaf"]
    B --> C{"candidate exists?"}
    C -- no --> D(["use candidate"])
    C -- yes --> E["n = 2"]
    E --> F["candidate = destDir/base (n)ext"]
    F --> G{"candidate exists?"}
    G -- yes --> H["n++"]
    H --> F
    G -- no --> D
    D --> I["Files.move or Files.copy(source, candidate)"]
```

`base`/`ext` split on the last `.` in the leaf name; a leaf with no extension (or a leading-dot
dotfile) keeps the whole name as `base` and an empty `ext`. The loop only ever increments `n` -
once a free numbered candidate is found it is used immediately, matching first-free-wins.

## Scenarios

| Scenario | Outcome |
|---|---|
| `destDir` doesn't exist yet | Created (and any missing parents) before the collision check |
| No file at `destDir/IMG_1234.jpg` | Placed at the original name, no suffix |
| `IMG_1234.jpg` already present | Placed at `IMG_1234 (2).jpg` |
| `IMG_1234.jpg` and `IMG_1234 (2).jpg` both present | Placed at `IMG_1234 (3).jpg` |
| Leaf has no extension (e.g. `README`) | Suffix still applies: `README (2)`, `README (3)`, ... |
| `move` | Source is removed; destination holds the bytes |
| `copy` | Source is left in place; destination holds a duplicate |

## Related

- `delete` and `ensureDirectory` are thin `Files` wrappers with no branching worth diagramming;
  both rewrap `IOException` as `UncheckedIOException` with a contextual message, matching every
  other adapter in this package.
