# Roadmap

Taffy exists so the so.alaz Paper plugins stop carrying their own copies of the same infrastructure.
Each plugin shades and relocates the Taffy modules it uses, so a server owner still installs one jar per
plugin.

## Rules

- Extract code that already runs in a plugin. Do not invent.
- Java 25 and Paper only. No Kotlin in a runtime module.
- No third-party type in a public signature except Paper and Adventure.
- Every module depends only on `taffy-platform`.
- The library ships no resource files and holds no static plugin instance.
- Code under a copyleft license is read for ideas only and never copied in.

## Where it stands

| Part | State |
|---|---|
| Build tooling (`tooling/taffy-gradle`, `tooling/taffy-catalog`) | Built and tested. Not published. |
| `taffy-platform` | Started: `TaskGate`, `PendingSettlements`, `CooldownManager`. |
| `taffy-hooks`, `taffy-config`, `taffy-storage`, `taffy-gui`, `taffy-metrics`, `taffy-test` | Not started. |
| Plugins using Taffy | None. |

## Stages

### 1. First runtime module

`taffy-platform` with three classes that import nothing outside the JDK, copied from ProVouchers with
their tests.

`TaskGate` and `PendingSettlements` cover work still running when a plugin disables. They may change
shape when the Scheduler is extracted in stage 5.

### 2. Rehearse consumption

Publish to the local Maven repository, then build a throwaway plugin that resolves `so.alaz.settings`
and `so.alaz.paper-plugin` by id and version, depends on `taffy-platform`, and produces a shaded jar
that contains `<group>/libs/taffy/platform/TaskGate.class`.

No test resolves the plugins by id from a Maven repository today, so this is the first check of what a
real plugin does.

Open at this stage: a plugin has no way yet to name the Taffy runtime version. The catalog has no
`taffy-platform` entry.

### 3. First publish

Publish the plugins, the catalog and `taffy-platform` to repo.alaz.so.

A `v*` tag publishes everything, runtime modules included. Decide these before the first tag, because
a release makes them permanent:

| Decision | Today |
|---|---|
| Plugin ids | `so.alaz.settings`, `so.alaz.repositories`, `so.alaz.java`, `so.alaz.coverage`, `so.alaz.paper-api`, `so.alaz.library`, `so.alaz.paper-plugin` |
| Version model | One version for the plugins, the catalog and the runtime modules |
| Repository name | `taffy-api` on GitHub, `taffy` everywhere else |

Also unverified: the publish credentials for this repository, and whether the token may write the
plugin marker paths outside `so/alaz/taffy`.

### 4. First plugin on Taffy

ProVouchers shades `taffy-platform`, deletes its own copies of the three classes, and ships a release.
This is the first point at which Taffy runs on a server.

ProVouchers is the shorter road. Tradefloor is held back by its pinned SQLite driver version and by a
test that reads its own version catalog.

### 5. Grow module by module

Each class moves when a plugin needs it, with its tests. The library requires 80 percent instruction
coverage, and most of the remaining platform classes have no tests in the plugins, so tests come first.

Decisions that come due here, each when its first class is extracted:

| Decision | The two plugins today |
|---|---|
| Logger type | `java.util.logging` in Tradefloor, `ComponentLogger` in ProVouchers |
| Message placeholder style | `{name}` tokens in ProVouchers, MiniMessage tags in Tradefloor |
| Scheduler behaviour at disable | `TaskGate` and `PendingSettlements` in ProVouchers, tracked task handles in Tradefloor |

Next candidate: `Durations`, the first class both plugins have. The two copies differ in accepted input
(letter case, `ms`, a sign, an upper bound), so that is settled before the move.

## A usage graph for a shaded library

A shaded library has no download counter. It can still have a live graph: `taffy-metrics` reports to a
metrics page that belongs to Taffy, from inside every plugin that shades it, with a chart of the Taffy
version. Every server running any so.alaz plugin then counts toward one Taffy graph.

This needs a metrics entry created for Taffy. It starts counting once a released plugin shades
`taffy-metrics`, which is stage 4 at the earliest and only if `taffy-metrics` exists by then.

Taffy stays shaded. Libraries shipped as a separately installed plugin show a download count that
mostly mirrors their own dependents, and pay for it with version drift between the library and the
plugins on a server.

## Ideas noted from other libraries

- Insert placeholder values as components after the template is parsed, and apply PlaceholderAPI to the
  template before the values go in.
- Let the server owner choose how a duration or number renders, as a parameter inside the placeholder.
- A test that scans the library's classes and fails when a design rule is broken.
- A guard that fails when a plugin shades Taffy without relocating it.
- Scheduler: tell the caller when an entity task was retired, and never schedule a zero tick delay.
- Derive every global identifier (PDC namespace, metrics chart id) from the host plugin, so two shaded
  copies on one server do not collide.
- Docs split by audience: server owner pages written once for the library and linked from each plugin.

## Not planned

- `UpdateChecker` and `SemanticVersion`: neither plugin has one to extract.
- Shipping Taffy as a standalone plugin.
