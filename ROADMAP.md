# Roadmap

Taffy exists so the so.alaz Paper plugins stop carrying their own copies of the same infrastructure.
Each plugin shades and relocates the Taffy modules it uses, so a server owner still installs one jar per
plugin.

Taffy is built out and made stable first. Plugins move onto it after that.

## Rules

- Extract code that already runs in a plugin. Do not invent.
- Java 25 and Paper only. No Kotlin in a runtime module.
- No third-party type in a public signature except Paper, Adventure and `org.jetbrains.annotations`.
  `taffy-comficonf` may also name `com.comficonf.api` types: `ApiRules` allows them only when the scanned class
  and the rules test's anchor are both in `so.alaz.taffy.comficonf`.
- Every runtime module depends only on `taffy-platform`, plus `taffy-test` for its tests. `taffy-comficonf` also
  depends on `taffy-config`, which it builds on. `taffy-test` depends on no Taffy module.
- The library ships no resource files and holds no static plugin, logger, executor or thread. The owner
  passes them in. Two classes own threads on purpose: `Storage` (its database threads, which it drains
  at close) and `DiscordWebhook` (its HTTP client). A `taffy-test` helper may own a thread for the
  length of one call. The SDKs behind `taffy-metrics` own threads of their own: a bStats-Metrics thread per
  started bStats instance, and FastStats' HTTP client with its tasks on Paper's async scheduler. FastStats
  error tracking installs the JVM default uncaught exception handler, which `Metrics.shutdown` detaches.
- A class arrives together with its tests. Each module holds 80 percent instruction coverage with no
  exclusions, and each class at least 50 percent.
- Code under a copyleft license is read for ideas only and never copied in.

Every module has a `PublicApiRulesTest` that runs `ApiRules` from `taffy-test` over its own classes. Its
`scanFindsTheModuleClasses` test requires classes of the module and fails when a scanned class lies outside the
test's own package, so an anchor taken from another module fails there. The root `check` fails when a module
listed in `taffy.modules` has no `PublicApiRulesTest`; it does not read the test.

## Where it stands

| Part | State |
|---|---|
| Build tooling (`tooling/taffy-gradle`, `tooling/taffy-catalog`) | Built and tested. Not published. |
| `taffy-platform` | Complete for the planned list: 26 classes, 374 tests. Not published. |
| `taffy-test` | 8 helpers, 98 tests. Not published. |
| `taffy-config` | 7 classes, 188 tests. Not published. |
| `taffy-storage` | 16 classes, 413 tests, 83 of them against MySQL, MariaDB and PostgreSQL in Docker, 15 of those over TLS. Not published. |
| `taffy-hooks` | 30 classes, 13 of them public, 316 tests. Not published. |
| `taffy-gui` | 41 classes, 23 of them public, 657 tests. Not published. |
| `taffy-metrics` | 10 classes, 3 of them public, 178 tests. Not published. |
| `taffy-comficonf` | 8 classes, 6 of them public, 132 tests. Not published. |

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
| `ApiRules` | Checks a Taffy module's public signatures, static fields and jar contents. Taffy modules only. `com.comficonf.api` is allowed in `so.alaz.taffy.comficonf` alone. |
| `CapturedLog` | A `java.util.logging.Logger` that records what it is given. |
| `FakeItems` | Item stand-ins that stack and compare like Paper's. |
| `FakeFolders` | A folder whose listing fails, to test unreadable directories. |
| `JvmDefaults` | Runs a block under another default locale or time zone, then restores it. |
| `RaceRunner` | Starts several threads at once to race one operation. |
| `SimulatedClasspath` | A class loader that adds named classes, or copies a module's own classes onto a loader that hides whole packages. The copies keep their code source, so coverage counts them. |
| `FaultyDataSource` | Wraps a `DataSource` and fails connections or statements on demand. |

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

## What `taffy-storage` holds

| Class | What it does |
|---|---|
| `Storage` | Starts a database from `StorageSettings`, runs the plugin's migrations, and runs work in transactions on its own database threads. On SQLite storage from `Storage.start`, writes run on one writer thread of their own, in the order they were submitted. `async` writes, `read` reads, `asyncRetrying` and `readRetrying` rerun work that a deadlock or serialization failure rolled back. `close` drains queued work. |
| `StorageSettings` | The dialect, the SQLite file or server address, credentials, SSL mode, pool sizes, thread count and backup count, validated when built. |
| `Dialect` | SQLite, MySQL, MariaDB and PostgreSQL: the SQL that differs between them and each driver's class name. |
| `Migration` | One numbered migration whose script the plugin ships at `db/migration/<dialect>/V<n>__<name>.sql`. |
| `Baseline` | Names the version table of a plugin's own migration runner. On a database with no version table of storage's, `Storage.start` takes it over once: it records the migrations up to that table's highest version as applied without running them, and renames the old table to `<table>_baseline` (or the first free `<table>_baselineN` when that name is taken), never dropping it. |
| `Tx`, `TxWork`, `Params`, `Row` | A transaction, the work run in it, statement parameters and result rows, including UUID, JSON and money columns. |
| `MoneyColumn` | Stores an amount as a scaled whole number, with the range it accepts. |
| `StorageException`, `CommitUnknownException` | A start or migration that cannot go on, and a commit whose outcome is unknown. |

The migrator is internal: it creates its own version table, checks each applied script's checksum,
refuses a database newer than the plugin, takes a SQLite backup before changing an existing file, and
takes a database lock on the server dialects so two servers sharing one database do not migrate at once.
The lock is keyed by the database (on PostgreSQL the schema) and the version table, so starts on other
databases of one server do not wait for it. A version table in another layout stops the start before
anything changes. With a `Baseline` and no version table of its own yet, it first takes over the old
runner's version table, in one transaction on SQLite and PostgreSQL and through one `RENAME TABLE` on
MySQL and MariaDB.

A plugin lists HikariCP and each driver it offers in `paperPlugin.runtimeLibraries`, and the same on its
test runtime classpath. `taffy-storage` publishes no dependency on them.

## What `taffy-hooks` holds

| Class | What it does |
|---|---|
| `CustomItems`, `CustomItemPlugin`, `CustomItemRef` | Detects ItemsAdder, Nexo, Oraxen and HeadDatabase, builds an item from a reference such as `itemsadder:ns:id` or `hdb:7129`, and reads a stack back to its `<key>:<id>`. Plugins are tried in that order. `statuses()` gives each plugin's state as it is now. |
| `EconomyProvider`, `EconomyResult`, `EconomyWatcher` | The Vault economy in `BigDecimal` and `UUID`, with `has`, `withdraw` (with a start check that runs before money moves), `deposit` and a result status. `EconomyProvider.vault` finds it and gives its state with a reason for the owner when there is none, such as no economy plugin registered with Vault. `EconomyWatcher` triggers a new lookup when an economy registers late. |
| `PermissionProvider` | LuckPerms groups and permissions: primary group, groups, add, add for a time, remove, set and unset a permission. |
| `RegionProvider` | The WorldGuard region ids at a location. |
| `PlaceholderExpansionBuilder`, `PlaceholderRegistration` | Registers one set of player, offline and global placeholders with PlaceholderAPI and MiniPlaceholders, whichever are present, gives the state of each backend, and unregisters them at disable. |
| `HookState`, `HookStatus`, `Detected` | What detection found for one integration, worded for the server owner: switched off, not installed, not enabled, not visible, missing the API, nothing registered, could not be hooked, on, or waiting for its items. A status can carry a reason that says what the owner can do. `Detected` holds the provider and reads its status when asked. |

Each integration sits behind one of these types. The class that calls it loads only after its plugin is found
installed and enabled and its API classes visible, so a server without the integration never loads a class that
names it. `AbsentIntegrationsTest` runs every entry point on a copy of the module that cannot see any integration.

A plugin lists each integration it uses in `paper-plugin.yml` under `dependencies.server` with `load: BEFORE`
and `required: false`. One that is enabled but not listed is `NOT_VISIBLE`, and its warning names the missing
entry. The package Javadoc walks through enable, showing the state to the owner, failures and disable.

## What `taffy-gui` holds

| Area | Classes |
|---|---|
| Layouts | `MenuLayout`, `ItemLayout`, `PagerLayout` and `MenuKind`: the rows, items and list slots of a menu, built in code or from the plugin's own files, and the actions a menu has. |
| Drawing | `Icon` (a button, a filler, a copy of a real item, or a stored item), `MenuText` (titles, names and lore from the layout or the lang file), `Frame` (with its footer) and `Pager`. Every shown copy carries the plugin's preview key. |
| Runtime | `Menus` registers menus, opens them on the viewer's thread, pages, loads data in the background, plays the menu sounds, reloads layouts and shuts down. `Menu`, `MenuSession`, `Click`, `ClickHandler`, `Route`, `MenuSettings`, `ViewMemory` and `Cue` go with it. |
| Click safety | The listener from `menus.listener()` cancels every click and drag in a menu, dispatches only plain clicks after a short opening window and a per-button debounce, and deletes a marked copy found anywhere outside a menu. `ClickGuard` names the clicks a button can get. |
| Text prompts | `Prompts` asks a player for text in chat or in an anvil, with a timeout and a cancel link, and returns to the menu. It is built from the menus and reads everything else from them; the listener from `prompts.listener()` reads chat answers and guards the prompt anvil. `PromptRequest`, `Answer`, `PromptMode` and `BedrockPlayers` go with it. |

The plugin reads its layout files and settings and passes the values in; the library ships no lang file.
`Menus.keys(kinds)` and `Prompts.KEYS` list the lang keys a plugin ships. The package Javadoc walks through
building, opening, prompting, reloading and disabling.

## What `taffy-metrics` holds

| Area | Classes |
|---|---|
| Charts | `MetricChart`: string, number, bool, string list and counter charts from one definition for both providers, taking only JDK types. Ids match `^[a-z0-9_]+$` and may not be one FastStats fills itself. A counter reads one running total, and each provider reports its own increase since it last reported. |
| Start | `MetricsBuilder`: the owner `Plugin`, its `ErrorReports` and its switch, then the bStats service id, the FastStats token and the charts, then `start()` once at the end of enable. An unset id or token leaves that provider off; a provider that fails to start logs one warning. |
| Running metrics | `Metrics`: readers for a diagnostics command (`bStatsStarted`, `fastStatsSdk`, `fastStatsEnabled`, `fastStatsSubmitting`, `fastStatsCharts`, `fastStatsErrorTracking`), `report` for a plugin that sends its handled errors to two places, and `shutdown` as the last disable step. |
| Handled errors | `start()` installs the FastStats route into `ErrorReports`, which delivers the reports held from enable. Each source gets its own FastStats tracker, so repeats merge with a count; the number of sources and kinds is bounded. Messages pass eight anonymize rules on top of the SDK's own. |
| Taffy usage | A `taffy_version` chart on Taffy's own bStats and FastStats entries, started from every plugin with its switch on, beside each provider the plugin itself started. Described below. |

No public signature names a bStats or FastStats type. The package Javadoc walks through enable, an enable that
gives up early, handled errors, diagnostics and disable.

## What `taffy-comficonf` holds

| Area | Classes |
|---|---|
| Connect guard | `ComfiConfConnection.connect(Plugin, ErrorReports, boolean, Supplier<ComfiConfLink>)`: switched on, bridge installed, enabled and its API visible to this plugin, then the plugin's own registration runs. It never returns null. `status()`, `resourceId()`, `notifyChanged()`, `lockFor(Path)` and `close()` follow; none of them passes a bridge failure on. Connecting again is `close()` of the held connection, then `connect` on the main thread with the same supplier, which registers the provider built once in the integration class. |
| Status | `ComfiConfState` and `ComfiConfStatus` with `describe()` and a reason for the owner, read when asked. A plugin jar that bundles its own copy of the API reads `NOT_VISIBLE` with a reason naming the copy; `NOT_REGISTERED` carries a reason. |
| Registration | `ComfiConfRegistration.register(Plugin, id, displayName, description, ConfigProvider)` returns a `ComfiConfLink`. It is reached only from the plugin's own integration class. |
| YAML file provider | `YamlFileProvider` over one YAML file: read from one byte array with its revision, the plugin's own `rules`, apply with the revision check, a merge into the current document, `SafeFileWriter`, the plugin's reload through `callSync`, restart-only fields against the startup values, and a truthful result. UTF-8 and line endings survive a round trip. |

`ComfiConfState`, `ComfiConfStatus`, `ComfiConfLink` and `ComfiConfConnection` name no ComfiConf type, so a
plugin's main class may hold them; `ComfiConfRegistration` and `YamlFileProvider` do. The plugin keeps its
schema, its field mapping and its resource id. The package Javadoc walks through the integration class, enable,
reloads and the plugin's own writes, the status line and disable.

## Design defaults

These were open and are now fixed in code. Nothing is published, so each can still change.

| Question | Default |
|---|---|
| Logger type | `java.util.logging.Logger`, passed in. It is the value of `plugin.getLogger()`. |
| Plugin identity | No context object. A class takes the narrowest thing it uses: a `Plugin`, a `Logger`, a `Path`, an `Executor`. |
| Message values | MiniMessage placeholders passed as `TagResolver`, inserted after the template is parsed. A template may write `{name}` as well as `<name>`. |
| Placeholder order | `{name}` rewrite, then PlaceholderAPI on the template, then MiniMessage with the values. PlaceholderAPI never runs on a supplied value. |
| Message prefix | Shown only where a template writes `<prefix>`. Never prepended. |
| Missing message key | Renders as red `missing:key`. Never throws. A menu button whose name key is missing shows the marker of its last key. |
| Scheduler at disable | It never waits and never runs leftover work. Every method reports refusal: `false`, a failed future, the `retired` callback, or `RejectedExecutionException` from `asyncExecutor()`. Work that must settle is tracked in `PendingSettlements`. `close()` completes no `dispatch` future: one whose task Paper accepted and then dropped stays incomplete, and the caller bounds it or tracks the work in `PendingSettlements`. |
| Error sink | One `ErrorReports` instance the plugin creates and passes around, with one target. Reports made before the first install or clear are held, the first per source and at most 16, and the first install receives them. `taffy-metrics` installs the FastStats route at `start()`, so a plugin installs no target of its own before it. |
| Handled failures | Two shapes, by who acts on them. A failure the server owner fixes (a bad webhook URL, Discord or the network down) is a one-line `String` reason for the plugin's log: `DiscordWebhook` passes it to its callback and reports nothing. A failure in code (Taffy, the plugin or an integration) is a source and a `Throwable` for `ErrorReports`, and a class that also logs it uses `ThrowLog`. A third-party pass that can fail on every call, such as a PlaceholderAPI expansion inside `Text` or a placeholder request in `taffy-hooks`, is logged through `ThrowLog` and not reported. |
| Testing Paper code | Mockito mocks of Paper interfaces, static and construction mocks where code touches a static or `new ItemStack`. No mock server library. |
| Package layout | One flat package per module. `so.alaz.taffy.platform` is not split: growth goes into modules. |
| Disable order | `ComfiConfConnection.close`, `PlaceholderRegistration.unregister`, `Menus.shutdown`, `Scheduler.close`, `TaskGate.close`, `PendingSettlements.settleAll`, storage close, `DiscordWebhook.close`, `Metrics.shutdown`. The package Javadoc states it. |
| Storage base | Tradefloor's storage package. `Storage.start` takes the owner `Plugin`, its `ErrorReports`, the `StorageSettings` the plugin built, its migrations and a clock; the name, version, data folder, logger and migration scripts come from the `Plugin`. Config reading stays in the plugin. |
| Rollback retry | Opt in, through `asyncRetrying` and `readRetrying`. Plain `async` and `read` never rerun work, because work may have effects outside the database. |
| Storage close | `close(drain)` waits up to the drain on the calling thread, then refuses the queued work and interrupts the database threads. It does not wait for running work after that: that work ends on its own daemon database thread, where its future completes. |
| Config base | `ConfigLoader` and `ConfigReader` take a data folder `Path`, a supplier of the shipped file and a `Logger` or a line consumer. Nothing calls `JavaPlugin.getConfig` or `reloadConfig`. |
| Config problems | Collected, never thrown one at a time. A warning uses the fallback and loading goes on; an error is collected and the caller decides. Lines name no plugin: the logger already does. |
| Parser convention | `Durations`, `Colors` and `Expiry` expose a public `EXPECTED` text and fail with `invalid <thing> '<value>': expected <EXPECTED>`. Null is treated like any other bad value. `ItemBuilder`, `SkullBuilder` and `DiscordWebhook` are outside it. |
| Message text | Log lines and exception messages are written for the server owner or for the developer reading the log, and their wording can change in any release. Only text a Javadoc spells out is a contract, such as the parser messages and their `EXPECTED` texts, the `describe()` texts of `HookStatus` and `ComfiConfStatus`, `ConfigReader`'s warning and error shapes and `describe`, the `StrictYaml` and `YamlBytes` message shapes (such as `<source> is larger than 16 MiB` and `<source> is not valid YAML: <problem> at line <n>, column <m>`), the clause form of `ConfigLoadException` and the `missing:key` marker. A `ConfigReader` warning names the value it refuses, cut as `describe` cuts it; a YAML parse error names a line and column, not the line's text. Code branches on exception types, statuses and return values, never on a message. Taffy's own tests pin the full text so that a change is deliberate; a deliberate change updates its test and the changelog. |
| Values that must be quoted | A date with a time, a hex colour and a code such as `0451`. `ConfigReader` warns and asks for quotes when it meets one unquoted. |
| Integration loading | Detected once at enable: switched on, installed, enabled, API classes visible, then the adapter is built. An adapter class loads only after every check passes and re-checks that its plugin is enabled on each call. No listener for an integration enabled later; the Vault economy is looked up again when `EconomyWatcher` fires. With ItemsAdder on, each `CustomItems.detect` leaves at most one more listener, registered until the owner disables. |
| Integration entry points | The owner `Plugin`, then `ErrorReports`, then the owner's switch (a `boolean`, or a `Set<CustomItemPlugin>` for custom items), then the integration's own options. One integration gives a `Detected`, a group gives `statuses()`. Every status is read when asked and reads not enabled while its plugin is disabled, except the economy's, which a new lookup replaces. |
| Hook failures | At detection, not visible and missing the API log one warning; could not be hooked logs one warning and is reported to `ErrorReports` under `hook.<name>`. Each economy lookup is a detection of its own, so it warns again. Later, a failure inside an integration is logged at most once a minute per kind of call; item builds and every LuckPerms and WorldGuard call are also reported, item id reads and placeholder requests are not. |
| Menu layouts | Values the plugin builds in code or reads from its own files. Taffy reads no menu file and ships no lang file. |
| Menu threads | A menu opens, draws and closes on the viewer's entity thread. Work from a click goes through `openLater`, `rerenderLater`, `closeLater` and `askLater`. |
| Metrics SDKs | bStats and FastStats are runtime dependencies of `taffy-metrics`: a plugin compiles without them and shades them relocated. `so.alaz.taffy.paper-plugin` relocates both and keeps one `META-INF/faststats.properties`. Neither is ever a Paper runtime library. |
| ComfiConf | Its own opt-in module, `taffy-comficonf`, so a plugin that does not use ComfiConf carries none of it. The API is `compileOnly` in Taffy and in the plugin, never bundled, never relocated and absent from the published metadata; the bridge provides it. |
| ComfiConf activation | The plugin's own reload, passed as a `Callable<Boolean>` such as `holder::reload`, run through `callSync` after the write. Its loader must not write the file, its schema constraints are no looser than its `ConfigReader` calls, and `rules` refuses what the loader rejects. Restart-only consumers read `ConfigHolder.startup()`. |
| ComfiConf and the plugin's own writes | A write of the exposed file outside ComfiConf holds `ComfiConfConnection.lockFor(file)` from reading the file to saving it, then reloads and calls `notifyChanged()`. |
| Test conventions | `so.alaz.taffy.java` runs tests under the `tr-TR` locale and the `Pacific/Auckland` zone, passes the Mockito jar on each test task's classpath as a Java agent, and runs javadoc with `-Xdoclint:all,-missing` and `--no-fonts`. |

## Next modules

Every planned module is built. What remains is the list below and the server runs.

## Before the first publish

A `v*` tag publishes everything, runtime modules included, and makes these permanent:

| Decision | Today |
|---|---|
| Plugin ids | `so.alaz.taffy.settings`, `so.alaz.taffy.repositories`, `so.alaz.taffy.java`, `so.alaz.taffy.coverage`, `so.alaz.taffy.paper-api`, `so.alaz.taffy.library`, `so.alaz.taffy.paper-plugin` |
| Version model | One version for the plugins, the catalog and the runtime modules. The catalog has a `taffy` version and one library per module: `alaz.taffy.platform`, `alaz.taffy.test`, `alaz.taffy.config`, `alaz.taffy.storage`, `alaz.taffy.hooks`, `alaz.taffy.gui`, `alaz.taffy.metrics`, `alaz.taffy.comficonf`. |
| Version numbers | Semantic Versioning from `0.1.0`. Before `1.0.0`, a breaking change raises the minor version and an addition or a fix raises the patch version; from `1.0.0`, a breaking change raises the major version, an addition the minor version and a fix the patch version. A breaking change removes or changes, incompatibly, a public type or member, a plugin id, a catalog alias or documented behaviour. |
| Removals | A public element is marked `@Deprecated(forRemoval = true)`, with Javadoc naming its replacement and a `### Deprecated` entry in the changelog, in a release before the one that removes it. |
| Snapshots | `main` carries the next version with `-SNAPSHOT`, and every push to `main` that passes CI publishes it to `repo.alaz.so/snapshots`. A release commit drops the suffix and is tagged `v<version>`, which publishes to `repo.alaz.so/releases`; the commit after it sets the next `-SNAPSHOT`. |
| Repository name | `taffy`, at `github.com/alazso/taffy` |

The plugin ids, the version model and the repository name are settled. Every plugin marker publishes under `so/alaz/taffy`.

Also unverified: the publish credentials for this repository. The MySQL, MariaDB and PostgreSQL suites of
`taffy-storage` pass against MySQL 8.4, MariaDB 11.4 and PostgreSQL 17 in containers, including two starts
migrating one shared database at once. The `sslTest` task connects to the same three servers over TLS with
a certificate for `localhost` from an authority in the JVM trust store: `DISABLE` connects unencrypted,
`TRUST` connects encrypted under either host name, and `VERIFY_FULL` connects encrypted as `localhost` and is
refused as `127.0.0.1`.

`taffy-hooks` is tested against the integrations' API jars with Mockito. Only a server run covers: Paper's
class loader isolation that gives `NOT_VISIBLE`, the Nexo, Oraxen and WorldGuard calls whose entry classes
cannot start off a server, PlaceholderAPI registration and request dispatch, and how LuckPerms merges a
temporary group with an existing one.

`taffy-gui` is tested with Mockito and fake views. Only a server run covers: the anvil built through
`MenuType.ANVIL` and the text it returns, whether the `ItemFlag`s hide the same tooltip lines Tradefloor's
data components hid, the lifetime of a cancel link's click callback, chat event order with signed
messages, the crafting or creative screen check that decides whether a finished prompt reopens its menu,
`registerEvents` on the two package-private listeners (a unit test covers Paper's `EventExecutor.create`),
and whether Folia's shutdown thread owns every player when plugins disable at a server stop, which leaves
only a disable while the server runs with menus open.

`taffy-metrics` is tested against the real SDK classes with a loopback HTTP sink in place of the metrics
servers. Only a server run and a real dashboard cover: real bStats and FastStats submissions, bStats and
FastStats each taking one Taffy credential from many plugin jars on one server, and how they count that server, Paper's `ServerExceptionEvent` path into FastStats, the uncaught
exception handler chain across several shaded copies, and how long the final send blocks the disabling thread
on a stalled network.

`taffy-comficonf` is tested with the real `comficonf-api` value types and no bridge. Only a server run with the
bridge covers: `join-classpath` loading and the real `NOT_VISIBLE` path, registration and unregistration, the
real `callSync` and its 10 second timeout, `notifyChanged` reaching an open editor, what the bridge does with a
registration across its own disable and reload, the `FieldChange` paths it sends for list item moves, and
that `lockFor` gives the same lock the bridge's own write takes.

## Additions after the first publish

Each of these is purely additive, so none waits for the first publish. Each lands with its tests when a
plugin needs it.

- `CooldownManager.tryStart(Object key, Duration length)`: an atomic check-and-start. A length of zero or
  less starts nothing and returns whether the key was free. The class Javadoc's race sentence then points
  at it.
- `ItemBuilder(ItemStack base)`: a second constructor whose `build()` starts from a copy of the stack and
  keeps its amount, name, lore and glow unless they are set. `ItemBuilder(Material)` stays as it is.
- Delayed and repeating tasks on the global and entity threads: `delayedGlobal`, `repeatingGlobal`,
  `delayedEntity` and `repeatingEntity` on `Scheduler` only, never on `Dispatcher` or `EntityThread`, each
  returning `boolean` like the async ones. Their Javadoc defines the tick conversion (a `Duration` rounded
  up to whole 50 ms ticks, at least one), a removed entity (`retired` runs on the calling thread) and
  refusal at disable. The repeating ones join the tasks `close()` cancels.
- A handle for one repeating task: `repeatingAsyncTask(Runnable, Duration, Duration)` returning Paper's own
  `ScheduledTask`, or `null` when refused, with cancelled tasks pruned from the tracked list on each add.
  `repeatingAsync` keeps its `boolean` return.
- `PdcKey.remove(PersistentDataHolder holder)`. Its Javadoc notes that a removal from an `ItemMeta` reaches
  its item only through `ItemStack#setItemMeta`.
- An `EconomyProvider.vault` overload that takes the previous `Detected` and warns and reports only when
  the status changed. The five-argument form keeps warning on every lookup, as its Javadoc states.
- `HookStatus.line()` and `ComfiConfStatus.line()`, the describe text and the reason joined as the package
  Javadocs join them, added to both at once when a plugin's diagnostics command shows the line it wants.
- A test seam for plugin menu tests (fake menu inventories, a movable click clock, delivery of click, drag,
  close and quit events) in `taffy-gui`: a new public type or a `Menus` constructor overload, never a new
  `Menus.Parts` component.

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
| `ErrorReports` | An instance, not static methods. `install` takes a `BiConsumer<String, Throwable>`. `report` discards anything the target throws. Reports made before the first install are held, the first per source and at most 16, and delivered on that install; `clear()` or `install(null)` first discards them. |
| `Shutdown` | `closeQuietly` is static and takes the logger and the `ErrorReports`. It logs plain text. |
| `Scheduler` | `new Scheduler(plugin)`. `global`, `async`, `delayedAsync` and `repeatingAsync` return `boolean` and never throw at disable. Delays are `Duration`. `repeatingAsync` returns no task handle: `close()` cancels the repeating tasks. No database tag or executor. A failed Folia lookup logs the `Classes.present` warning. |
| `SkullBuilder` | `fromName` and `fromUuid` are instance methods on `new SkullBuilder(executor)`. No thread pool of its own; the cache belongs to the instance. A rejected lookup gives a blank head. `fromUrl` rejects a quote or backslash. |
| `Text` | `new Text(logger)`. Values go in as `TagResolver` after parsing. The viewer is the parse target, so audience placeholders resolve. A supplied `{name}` inside a click or insertion value is not filled in. A section-sign code in the template, in PlaceholderAPI output or in a `Placeholder.parsed` value is converted instead of throwing, and a PlaceholderAPI or MiniPlaceholders pass that throws is logged through `ThrowLog`, not reported. |
| `Messages` | Takes the data folder, the bundled English file as a supplier and a logger. Prefix is opt-in. A missing key renders a marker. Every non-section YAML value is kept as text, and a list as its lines (a bare `-` is an empty line). New `has` and `lines`. `renderTemplate` replaces `format`; `fill` has no equivalent. Locale codes match in lower case with `-` read as `_`. A default locale with no file is warned about. A language file that is not valid UTF-8 is skipped with a warning. |
| `ConfigLoader` | Messages name no plugin. Owner-keyed sections are a constructor argument. The upgrade write is atomic, forced to disk, and gives a new file the usual permissions. The temporary file has a fixed name (`config.yml.tmp`) and a leftover is removed at load. A shipped file that is not UTF-8 stops the load. |
| `ConfigReader` | `parsed` with a fallback text replaces `charge`. Messages name no plugin. Blank counts as absent. New range, choice, decimal and long readers. `text`, `strings`, `requiredText` and `strictText` read an unquoted date as yyyy-MM-dd text, `quoted` refuses any unquoted date, and an unquoted date with a time asks for quotes. A warning or error writes a value longer than 100 characters in short form. |
| `DiscordWebhook` | New `isWebhookUrl`. The failure callback runs at most once per post. Otherwise unchanged; it still owns its HTTP client threads. |
| `Storage` | From Tradefloor's copy. Starts PostgreSQL and MySQL as well. Takes the owner `Plugin`, the error sink, the settings, the migrations and a clock. Names no plugin in threads, pools or messages. A cancelled start releases what it built. `close` logs how much work it dropped. On SQLite storage from `Storage.start`, writes run one at a time on their own `<name>-db-writer` thread, and their futures complete on the read threads. |
| `CustomItems` | Merges the id reading of Tradefloor's copy with the item building of ProVouchers' copy. Plugins are tried ItemsAdder, Nexo, Oraxen, HeadDatabase. An unqualified reference is tried with its bare id. ItemsAdder items are built only once ItemsAdder has loaded them. An id read from a plugin still loading its items counts as a failure. An `Error` other than `LinkageError` propagates. |
| `HookStatus` | Tradefloor's detection states as an enum, plus a reason for the owner. The wording names no plugin or config key. A factory that returns `null` is `FAILED`. `NOT_VISIBLE` and `NOT_REGISTERED` are new, and every integration can be switched off. |
| `EconomyProvider` | From Tradefloor's provider, synchronous, with no transaction id, display name or offline policy. ProVouchers' `double` and `boolean` become `BigDecimal` and `EconomyResult`. A withdraw is covered only by the unrounded balance, and an unrounded overdraft is `OVERDRAWN`. |
| `PermissionProvider` | Takes a `UUID`, not a `Player`. LuckPerms loads only after detection. A temporary group replaces an existing one only when it ends later. A duration past `Instant.MAX` is refused. Failures are logged as well as reported. |
| `RegionProvider` | The owner plugin is passed in. A disabled WorldGuard gives no regions. Failures are logged as well as reported. Ids are compared with `equalsIgnoreCase`. |
| `Menus` | From Tradefloor's menu framework. Takes layouts as values (no YAML reading), the server, the plugin's preview key, a sounds supplier, a locale function and the thread seams, each once. Gives its listener through `listener()` and plays cues through `play`. `shutdown` ends the prompts first. A reload is checked against each menu's kind and a refused reload keeps the old layouts. Render failures from a scheduled draw are logged and reported. A cancelled load and a load after shutdown are not failures. |
| `MenuListener` | Built by `Menus` over its marker and registered through `menus.listener()`. Cancels at `LOWEST` and again at `HIGHEST`, dispatches plain clicks only, and reports a failing button to `ErrorReports` under `gui.click`. |
| `Prompts` | Chat and anvil only: the sign prompt stays in Tradefloor. Built from the menus, the mode, the Bedrock check and the timer; one listener holds the chat and anvil handlers. A second `Prompts` over one `Menus`, or over menus that were shut down, is refused. `askLater` asks from a button. Taffy `Messages` adds no prefix, so prompt and error lines show one only where the lang text writes `<prefix>`. |
| `IconStacks` | Hides an item's details through `ItemMeta` and `ItemFlag` instead of data components, so it runs off a server. |
| `MetricChart` | Ids are checked against `^[a-z0-9_]+$` and the FastStats built-in field names when the chart is declared, and nulls are refused. The string, number and bool suppliers may return null, which leaves the value out. On FastStats a supplier that throws is also reported to error tracking on every read. |
| `MetricsBuilder` | Takes a `Plugin`, the `ErrorReports` and the switch. `bStats(int)` and `fastStats(String)` replace `enable(MetricProvider, String)`; error tracking is always on with FastStats. A second `start()`, or a chart added after it, throws. A token that is not 32 lowercase letters and digits is refused before FastStats writes its shared config. A failed FastStats start is shut down without a send, and the JVM uncaught exception handler is detached when no error service exists. A jar without `META-INF/faststats.properties` is refused the same way, before the shared config is written. |
| `Metrics` | Readers read false or null once shut down, and `bStatsStarted` reads false when the server owner turned bStats off. New `fastStatsCharts`. `report` is public. `shutdown` clears the error sink, stops the Taffy usage metrics, then bStats, then makes the FastStats final send, and detaches the handler only while it is this copy's. Handled errors go to one tracker per source instead of one attribute per report, so identical reports merge. |
| `CounterMark` | After a total goes down, the FastStats mark follows it as the bStats mark does. |
| `Migrator` | Creates its own version table, refuses migrations that are not strictly increasing, reads and splits every pending script before the backup, names the failing migration and statement, runs SQLite migrations with foreign keys off and checks them before commit, and locks the database on the server dialects. Can take over the version table of the plugin's own runner through `Baseline`. |

## Open API questions

Every question is settled.

## Known limitations

- `Metrics.shutdown` makes the FastStats final send on the disabling thread. On a stalled name lookup or
  response it blocks for as long as the network stalls: the SDK's 3 second timeouts cover neither. Bounding
  it needs a thread that outlives the call, which the thread rule forbids. An opt-in bounded shutdown would
  be a new method.
- FastStats 0.32.2 clears every error tracker after an accepted error send, so a handled or uncaught report
  made while that send is in flight is lost. Only the SDK can fix it; it is on the FastStats version-bump
  checklist under Tooling notes.
- Each source sends at most 32 kinds of handled error for the life of a `Metrics` handle; further kinds from
  that source are dropped until the next `start()`. Taffy cannot see when FastStats accepts a send, which is
  the only point where a reset would keep the bound.
- `Metrics.fastStatsErrorTracking()` answers for `Metrics.report`, not for the error sink. A target the
  plugin installs after `start()` replaces the FastStats route, and the reader still reads `true`. A plugin
  that wants its reports in two places installs one target that calls `Metrics.report` and its own handler.
  A reader that also sees the sink would be a new method.
- On SQLite the future of a write completes on a read thread, so work or stages that wait for writes on every
  read thread at once (always the case with `asyncThreads` 1) keep that write's future from completing. On
  every dialect, work or a stage on a database thread that waits for other database work needs another
  database thread free.
- `Baseline` records the migrations up to the old table's version as applied on the plugin's word: storage
  checks that a migration with that version exists, not that the database matches the scripts before it.
- A takeover on MySQL or MariaDB that stops before its rename leaves `<prefix>schema_version_takeover` in the
  database until the next start completes the takeover.
- When `config.yml` is a symbolic link, each load removes a leftover `<name of the linked file>.tmp` beside
  the linked file, in a folder Taffy does not own. Saves write and replace that same file.
- The 16 MiB cap bounds file size, not parse time: one very long line or scalar can take SnakeYAML minutes to
  parse.
- A `ConfigReader` warning names the refused value cut to 100 characters, so part of a mistyped secret can
  reach the log. A YAML parse error without a position falls back to the parser's own message, which can
  quote a scalar.
- A shadow jar that relocates `com.comficonf.api` reads `FAILED`, not `NOT_VISIBLE` with the bundled-copy
  reason. `so.alaz.taffy.paper-plugin` never relocates the API.
- `PlaceholderExpansionBuilder.identifier` accepts upper case and lower-cases it, while placeholder names
  must already be lower case.
- An unquoted date and time whose offset brings it to midnight UTC, such as `2026-12-31T19:00:00-05:00`,
  reads in `ConfigReader` as the UTC date (`2027-01-01`) with no warning: YAML gives the same value for it
  as for an unquoted date, and the written text is not kept.
- On Folia, `Menus.shutdown` leaves the menu or prompt anvil of a viewer the disabling thread does not own
  open, with no listener guarding it. The viewer can take the copies it shows, each carrying the preview key,
  until the listener of a later enable deletes one on a click outside a menu.
- `YamlFileProvider` can report a restart-only change inside an object list as waiting even when its value
  is back at its startup value, because its path goes through the item index.

## Tooling notes

- With the build cache on, `cleanTest` restores test results. A forced rerun needs `--no-build-cache`.
- `--no-fonts` needs javadoc from JDK 23 or newer. `so.alaz.taffy.java` assumes the Java 25 toolchain.
- A plugin can override the test locale and zone with `systemProperty` on its test task.
- The tooling TestKit suite builds plugins against copies of `taffy-platform`, `taffy-config`, `taffy-metrics`
  and `taffy-comficonf`. Its first run in a fresh TestKit Gradle home needs network access to the Paper
  repository, Maven Central and repo.faststats.dev, and takes about a minute.
- `so.alaz.taffy.java` and the plugins built on it are published behaviour. Each convention they add (the test
  locale and zone and their override, the Mockito agent, the javadoc flags, `LICENSE` in `META-INF`) has a
  TestKit case in `ConventionPluginsTest`, and a change to one is listed in the changelog as a change to
  plugin builds. A plugin build meets it only when it moves to that Taffy version.
- At every FastStats version bump, re-check: whether an accepted error send still clears reports made during
  the send; that `SimpleContext.submissionActive` still gates every send (`TaffyUsageTest`, `MetricsTest` and
  `MetricsBuilderTest` assert that a suppressed shutdown sends nothing); the first-run notice, the
  `faststats.first-run` property and the opt-out keys; and that `META-INF/faststats.properties` is still read
  when a context is created.

## A usage graph for a shaded library

A shaded library has no download counter. It can still have a live graph: `taffy-metrics` reports to a
metrics page that belongs to Taffy, from inside every plugin that shades it, with a chart of the Taffy
version. Every server running any so.alaz plugin then counts toward one Taffy graph.

`TaffyUsage` holds Taffy's bStats service id, 35379, and its FastStats project token. Each plugin with its
metrics switch on runs a second bStats instance, with its own thread, when its own bStats instance started,
and a second FastStats context that sends metrics only and makes no final send, when its own FastStats
context started. A plugin on one provider reports Taffy to that provider only, so Taffy adds no provider,
configuration file or first-run notice that the plugin's own metrics do not. bStats' default plugin version
chart on Taffy's page shows the host plugins' versions, so only the `taffy_version` chart identifies Taffy.

Taffy stays shaded. Libraries shipped as a separately installed plugin show a download count that
mostly mirrors their own dependents, and pay for it with version drift between the library and the
plugins on a server.

## Ideas noted from other libraries

- Let the server owner choose how a duration or number renders, as a parameter inside the placeholder.
- A guard that fails when a plugin shades Taffy without relocating it.
- Derive every global identifier (PDC namespace, metrics chart id) from the host plugin, so two shaded
  copies on one server do not collide.
- Docs split by audience: server owner pages written once for the library and linked from each plugin.
- Report a restart-only change inside an object list by item key instead of index, using each list's key
  field from the schema.

## Not planned

- `UpdateChecker`, `SemanticVersion` and a generic HTTP helper: neither plugin has one to extract.
- A plugin context or kernel type: neither plugin has one. Constructor parameters are the default.
- Tradefloor's `Delay`: it fires on the JDK common pool and cannot be cancelled at disable.
  `Scheduler.delayedAsync` is the delay.
- Tradefloor's `ItemCodec`: it is the shape of one plugin's storage row.
- A sign text prompt: Tradefloor is its only user. Prompts fall back to chat.
- Single-plugin helpers (`InFlightGuard`, `ClaimLedger`, `ConfirmationTracker`, `CallGuard`,
  `CommandAliases`, `Materials`, `Placeholders`): they stay in their plugin until a second user appears.
- A mock server library for tests.
- Shipping Taffy as a standalone plugin.
