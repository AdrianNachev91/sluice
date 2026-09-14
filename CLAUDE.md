# Sluice - agent instructions

Sluice sorts a photo dump into a dated library and helps you throw out the junk. It's a JavaFX
desktop app and a CLI over the same core, packaged with Hydraulic Conveyor for Windows, macOS and
Linux.

## Build & test

    mvn -f app/pom.xml test

CI (`.github/workflows/ci.yml`) runs the same command on an ubuntu/windows/macos matrix.

## Module boundaries

Strict hexagonal, one Maven module, enforced by ArchUnit (`ArchitectureTest`) rather than a module
split:

- `domain/` - pure core. No I/O, no Spring/JavaFX imports. `java.nio.file.Path` as a value is fine;
  touching the filesystem is not. A port a domain class *consumes* lives here too, not under
  `application/port/out`.
- `application/port/in/` / `application/port/out/` - use-case entry points and effect interfaces
  only.
- `application/service/` - use-case implementations. Depend on ports, never on adapters.
- `adapter/` - effect implementations (`fs`, `metadata`, `imaging`, `vision`, `cli`, `ui`). Adapters
  depend on the core; they never depend on each other.
- `config/` - Spring wiring (DI/config only, no web starter) and `@ConfigurationProperties`.

## Adding a vision provider

Sluice ships one reference provider (`anthropic`, `adapter/vision/AnthropicSieve.java`) behind the
`VisionSieve` port. See
[`app/docs/design/adapter/vision/adding-a-provider.md`](app/docs/design/adapter/vision/adding-a-provider.md)
for the contract a new provider has to satisfy. `LICENSE-EXCEPTION.md` covers the licensing terms
that apply to a provider plugin specifically.

## Conventions

- Class-level Javadoc on every class under `app/src/main/java`; test files use `//` only, never
  Javadoc.
- User-facing wording (screens, CLI output, `--help`, error and refusal messages) favors clarity
  over precision. State the rule rather than the mechanism behind it. Never say "spends" without
  naming what.
- A comment says only what a reader can't get from the finished code: no change narration, no
  restating a caller's job, no justifying where something lives.

## Design docs

`app/docs/design` is the full design-doc set: GitHub-rendered, mermaid diagrams included, and also
an Obsidian vault - open the folder in Obsidian for graph view and backlinks.

## License

AGPLv3 only, with a narrow exception for vision-provider plugins. See `LICENSE`,
`LICENSE-EXCEPTION.md`, `TRADEMARK.md`. A pull request needs `CLA.md` signed first (automated by
`cla.yml`); read `CONTRIBUTING.md` before opening one.
