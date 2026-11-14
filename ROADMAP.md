# Roadmap

Taffy exists so the so.alaz Paper plugins stop carrying their own copies of the same infrastructure.
Each plugin shades and relocates the Taffy modules it uses, so a server owner still installs one jar per
plugin.

Taffy is built out and made stable first. Plugins move onto it after that.

## Rules

- Extract code that already runs in a plugin. Do not invent.
- Java 25 and Paper only. No Kotlin in a runtime module.
- No third-party type in a public signature except Paper, Adventure and `org.jetbrains.annotations`.
- Every runtime module depends only on `taffy-platform`, plus `taffy-test` for its tests. `taffy-test`
  depends on no Taffy module.
- The library ships no resource files and holds no static plugin, logger, executor or thread. The owner
  passes them in. A `taffy-test` helper may own a thread for the length of one call.
- A class arrives together with its tests. Each module holds 80 percent instruction coverage with no
  exclusions, and each class at least 50 percent.
- Code under a copyleft license is read for ideas only and never copied in.

Every module has a `PublicApiRulesTest` that runs `ApiRules` from `taffy-test` over its own classes. The
root `check` fails when a module listed in `taffy.modules` has none.

## Where it stands

| Part | State |
|---|---|
| Build tooling (`tooling/taffy-gradle`, `tooling/taffy-catalog`) | Built and tested. Not published. |
| `taffy-platform` | Complete for the planned list: 26 classes, 346 tests. Not published. |
| `taffy-test` | 7 helpers, 64 tests. Not published. |
| `taffy-config` | 7 classes, 161 tests. Not published. |
| `taffy-storage`, `taffy-hooks`, `taffy-gui`, `taffy-metrics`, `taffy-comficonf` | Not started. |

## What `taffy-platform` holds

| Area | Classes |
|---|---|
| Scheduling | `Scheduler`, `Dispatcher`, `ThreadTag`, `EntityThread`, `EntityUnavailableException` |
| Shutdown | `TaskGate`, `PendingSettlements`, `Shutdown`, `ErrorReports` |
| Text | `Text`, `Messages`, `LegacyText` |
| Items | `ItemBuilder`, `SkullBuilder`, `Items`, `InventoryFit`, `PdcKey`, `Colors`, `Sounds` |
| Time | `Durations`, `Expiry`, `CooldownManager` |
| Logging | `ThrowLog`, `Classes` |
| Discord | `DiscordWebhook`, `WebhookPayload` |

## What `taffy-test` holds

Test helpers for Taffy's modules, published so plugin test suites can use them too.

| Helper | What it does |
|---|---|
| `ApiRules` | Checks a Taffy module's public signatures, static fields and jar contents. Taffy modules only. |
| `CapturedLog` | A `java.util.logging.Logger` that records what it is given. |
| `FakeItems` | Item stand-ins that stack and compare like Paper's. |
| `FakeFolders` | A folder whose listing fails, to test unreadable directories. |
| `JvmDefaults` | Runs a block under another default locale or time zone, then restores it. |
| `RaceRunner` | Starts several threads at once to race one operation. |
| `SimulatedClasspath` | A class loader that hides or adds named classes. |

## What `taffy-config` holds

| Class | What it does |
|---|---|
| `ConfigLoader` | Writes the shipped `config.yml` on first start, upgrades an older file to the shipped version and keeps the owner's values and comments. |
| `StrictYaml` | Parses YAML strictly: UTF-8 only, and a clean `ConfigLoadException` for anything Paper rejects, a self-referencing anchor, or aliases that expand past 100000 values. |
| `ConfigReader` | Reads typed values and reports every problem at once: a warning uses the fallback, an error is collected for the caller. `parsed` reads any value through a parser such as `Durations` or `Colors`. |
| `ConfigHolder` | The reload guard. A reload that fails keeps the settings in effect. |
| `RestartKeys` | Lists the restart-only keys a reload changed. |
| `YamlFolder` | Loads a folder of YAML files keyed by file name, skips a bad file with a reason, reloads one file by id. |
| `ConfigLoadException` | The one checked exception of the module. Its message is written for the server owner. |

The package Javadoc of `taffy-config` walks through loading and reloading a plugin's settings with these.

## Design defaults

These were open and are now fixed in code. Nothing is published, so each can still change.

| Question | Default |
|---|---|
| Logger type | `java.util.logging.Logger`, passed in. It is the value of `plugin.getLogger()`. |
| Plugin identity | No context object. A class takes the narrowest thing it uses: a `Plugin`, a `Logger`, a `Path`, an `Executor`. |
| Message values | MiniMessage placeholders passed as `TagResolver`, inserted after the template is parsed. A template may write `{name}` as well as `<name>`. |
| Placeholder order | `{name}` rewrite, then PlaceholderAPI on the template, then MiniMessage with the values. PlaceholderAPI never runs on a supplied value. |
| Message prefix | Shown only where a template writes `<prefix>`. Never prepended. |
| Missing message key | Renders as red `missing:key`. Never throws. |
| Scheduler at disable | It never waits and never runs leftover work. Every method reports refusal: `false`, a failed future, the `retired` callback, or `RejectedExecutionException` from `asyncExecutor()`. Work that must settle is tracked in `PendingSettlements`. |
| Error sink | One `ErrorReports` instance the plugin creates and passes around. A metrics module installs its reporter into it. |
| Testing Paper code | Mockito mocks of Paper interfaces, static and construction mocks where code touches a static or `new ItemStack`. No mock server library. |
| Package layout | One flat package per module. `so.alaz.taffy.platform` is not split: growth goes into modules. |
| Disable order | `Scheduler.close`, `TaskGate.close`, `PendingSettlements.settleAll`, storage close, `DiscordWebhook.close`. The package Javadoc states it. |
| Config base | `ConfigLoader` and `ConfigReader` take a data folder `Path`, a supplier of the shipped file and a `Logger` or a line consumer. Nothing calls `JavaPlugin.getConfig` or `reloadConfig`. |
| Config problems | Collected, never thrown one at a time. A warning uses the fallback and loading goes on; an error is collected and the caller decides. Lines name no plugin: the logger already does. |
| Parser convention | `Durations`, `Colors` and `Expiry` expose a public `EXPECTED` text and fail with `invalid <thing> '<value>': expected <EXPECTED>`. Null is treated like any other bad value. `ItemBuilder`, `SkullBuilder` and `DiscordWebhook` are outside it. |
| Values that must be quoted | A date with a time, a hex colour and a code such as `0451`. `ConfigReader` warns and asks for quotes when it meets one unquoted. |
| ComfiConf | Its own opt-in module, `taffy-comficonf`, so a plugin that does not use ComfiConf carries none of it. |
| Test conventions | `so.alaz.java` runs tests under the `tr-TR` locale and the `Pacific/Auckland` zone, passes the Mockito jar on each test task's classpath as a Java agent, and runs javadoc with `-Xdoclint:all,-missing` and `--no-fonts`. |

## Next modules

In this order, each as its own module that depends only on `taffy-platform`:

1. `taffy-storage`: JDBC over HikariCP for SQLite, MySQL, MariaDB and PostgreSQL, with transactions,
   dialects and a migrator. Its storage contract test base goes into `taffy-test`.
2. `taffy-hooks`: custom items, economy, permissions, regions, PlaceholderAPI expansion builder.
3. `taffy-gui`: chest menus, pagination, click handling.
4. `taffy-metrics`: bStats and FastStats wrappers that install into `ErrorReports`.
5. `taffy-comficonf`: the ComfiConf registration, connect guard and YAML file provider. It builds on
   `StrictYaml`, `ConfigHolder` and `RestartKeys`, and is the one module allowed ComfiConf types in its
   public signatures.

## Before the first publish

A `v*` tag publishes everything, runtime modules included, and makes these permanent:

| Decision | Today |
|---|---|
| Plugin ids | `so.alaz.settings`, `so.alaz.repositories`, `so.alaz.java`, `so.alaz.coverage`, `so.alaz.paper-api`, `so.alaz.library`, `so.alaz.paper-plugin` |
| Version model | One version for the plugins, the catalog and the runtime modules. The catalog has a `taffy` version and one library per module: `alaz.taffy.platform`, `alaz.taffy.test`, `alaz.taffy.config`. |
| Repository name | `taffy-api` on GitHub, `taffy` everywhere else |

Also unverified: the publish credentials for this repository, whether the token may write the plugin
marker paths outside `so/alaz/taffy`, and CI on Gradle running Java 17 and Java 25.

## Differences from the plugin copies

A plugin that replaces its own class with the Taffy one meets these. Classes not listed behave as their
plugin copy did.

| Class | Difference |
|---|---|
| `CooldownManager` | `set(key, Duration)` and `extend(key, Duration)` replace `setMillis` and `extendMillis`. A length past the clock's range is capped, not wrapped. An expired entry is removed only if it still holds the expiry that was read, so a cooldown set by another thread is kept. |
| `TaskGate` | A refused `enter()` is never counted, so `close` neither waits for nor reports a task that was not admitted. `exit()` with no admitted task does nothing. |
| `PendingSettlements` | `settleAll` takes `Consumer<Throwable>`. A settlement that throws an `Error` is passed to it and the rest still run. What the callback throws is discarded. |
| `Durations` | Units in any letter case; `ms` is a unit; a sign, more than 18 digits and non-ASCII digits are rejected; an oversized value is `null` or `IllegalArgumentException`, never `ArithmeticException`. |
| `Expiry` | No `describe()`. Relative values follow `Durations`. Errors read `invalid expiry` and `invalid start`, followed by the public `EXPECTED` text. |
| `LegacyText` | Converts colour and format codes only. `{player}`, `{arg}` and `{random:...}` are left alone. New `sectionToMiniMessage` converts section-sign codes only and reads the paired hex form as one tag. |
| `Colors` | A signed hex value or a signed channel is rejected. Errors read `invalid color '<value>': expected` followed by `EXPECTED`, which shows the hex form in quotes. Null is rejected like any bad value. |
| `Sounds` | A null spec reads as blank: valid, and nothing is played. |
| `Classes` | `present` takes the logger to warn on. No static logger. The warning says the class is treated as absent. |
| `ItemBuilder` | Glow uses the enchantment glint override, with no enchantment and no item flag. A glowing item built this way does not stack with one built by the enchantment form. |
| `ErrorReports` | An instance, not static methods. `install` takes a `BiConsumer<String, Throwable>`. `report` discards anything the target throws. |
| `Shutdown` | `closeQuietly` is static and takes the logger and the `ErrorReports`. It logs plain text. |
| `Scheduler` | `new Scheduler(plugin)`. `global`, `async`, `delayedAsync` and `repeatingAsync` return `boolean` and never throw at disable. Delays are `Duration`. `repeatingAsync` returns no task handle: `close()` cancels the repeating tasks. No database tag or executor. A failed Folia lookup logs the `Classes.present` warning. |
| `SkullBuilder` | `fromName` and `fromUuid` are instance methods on `new SkullBuilder(executor)`. No thread pool of its own; the cache belongs to the instance. A rejected lookup gives a blank head. `fromUrl` rejects a quote or backslash. |
| `Text` | `new Text(logger)`. Values go in as `TagResolver` after parsing. The viewer is the parse target, so audience placeholders resolve. A supplied `{name}` inside a click or insertion value is not filled in. A section-sign code in the template or in PlaceholderAPI output is converted instead of throwing. |
| `Messages` | Takes the data folder, the bundled English file as a supplier and a logger. Prefix is opt-in. A missing key renders a marker. Every non-section YAML value is kept as text. `renderTemplate` replaces `format`; `fill` has no equivalent. Locale codes match in lower case with `-` read as `_`. A default locale with no file is warned about. A language file that is not valid UTF-8 is skipped with a warning. |
| `ConfigLoader` | Messages name no plugin. Owner-keyed sections are a constructor argument. The upgrade write is atomic, forced to disk, and gives a new file the usual permissions. A shipped file that is not UTF-8 stops the load. |
| `ConfigReader` | `parsed` with a fallback text replaces `charge`. Messages name no plugin. Blank counts as absent. New range, choice, decimal and long readers. An unquoted date with a time asks for quotes. |
| `DiscordWebhook` | New `isWebhookUrl`. Otherwise unchanged; it still owns its HTTP client threads, the one exception to the rule on passed-in executors. |

## Open API questions

None blocks the next module. Each is settled before the first publish.

- Shape of a handled failure: `DiscordWebhook` reports a `String`, `ErrorReports` a source and a
  `Throwable`, `Text` swallows a failing placeholder pass without a log.
- `Scheduler.dispatch`: a task Paper accepted may never run when the plugin disables, so its future can
  stay incomplete. Failing such futures in `close()` would run callers' continuations on the disabling
  thread.
- `Text.render` still throws for a `Placeholder.parsed` value that holds a section-sign code.
- `CooldownManager` has no atomic check-and-start.
- Additions later modules may need: `ItemBuilder` from an existing stack, list values in `Messages`,
  delayed and repeating tasks on the entity and global threads, a handle for one repeating task,
  `PdcKey.remove`.
- Tests pin the full text of several warnings and exception messages that no Javadoc promises.
- `ConfigReader.text`, `strings` and `requiredText` treat an unquoted date differently from `parsed`.
- `StrictYaml.read` has no file size cap, and a warning about a long list prints the whole list.
- `ConfigLoader` leaves a stray temporary file if the JVM is killed mid-write.
- `verifyPublicApiRulesTests` checks that each module has a rules test, not that it points at its own
  module.
- The convention changes in `so.alaz.java` reach every plugin build that applies them. Only Taffy's own
  modules have run under them.

## Tooling notes

- With the build cache on, `cleanTest` restores test results. A forced rerun needs `--no-build-cache`.
- `--no-fonts` needs javadoc from JDK 23 or newer. `so.alaz.java` assumes the Java 25 toolchain.
- A plugin can override the test locale and zone with `systemProperty` on its test task.

## A usage graph for a shaded library

A shaded library has no download counter. It can still have a live graph: `taffy-metrics` reports to a
metrics page that belongs to Taffy, from inside every plugin that shades it, with a chart of the Taffy
version. Every server running any so.alaz plugin then counts toward one Taffy graph.

This needs a metrics entry created for Taffy, and `taffy-metrics` to exist.

Taffy stays shaded. Libraries shipped as a separately installed plugin show a download count that
mostly mirrors their own dependents, and pay for it with version drift between the library and the
plugins on a server.

## Ideas noted from other libraries

- Let the server owner choose how a duration or number renders, as a parameter inside the placeholder.
- A guard that fails when a plugin shades Taffy without relocating it.
- Derive every global identifier (PDC namespace, metrics chart id) from the host plugin, so two shaded
  copies on one server do not collide.
- Docs split by audience: server owner pages written once for the library and linked from each plugin.

## Not planned

- `UpdateChecker`, `SemanticVersion` and a generic HTTP helper: neither plugin has one to extract.
- A plugin context or kernel type: neither plugin has one. Constructor parameters are the default.
- Tradefloor's `Delay`: it fires on the JDK common pool and cannot be cancelled at disable.
  `Scheduler.delayedAsync` is the delay. It is looked at again with `taffy-storage`.
- Tradefloor's `ItemCodec`: it is the shape of one plugin's storage row.
- Single-plugin helpers (`InFlightGuard`, `ClaimLedger`, `ConfirmationTracker`, `CallGuard`,
  `CommandAliases`, `Materials`, `Placeholders`): they stay in their plugin until a second user appears.
- A mock server library for tests.
- Shipping Taffy as a standalone plugin.
