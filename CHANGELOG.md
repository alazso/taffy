# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

## [0.1.0] - 2026-11-18

### Added
- `taffy-platform` module:
  - Scheduling: `Scheduler` (Paper and Folia, every method reports refusal while the plugin is
    disabling), `Dispatcher`, `ThreadTag`, `EntityThread`, `EntityUnavailableException`.
  - Shutdown: `TaskGate`, `PendingSettlements`, `Shutdown.closeQuietly`, `ErrorReports`.
  - Text: `Text` (MiniMessage with PlaceholderAPI and MiniPlaceholders when present), `Messages`
    (language files, per-player locale, reload), `LegacyText`.
  - Items: `ItemBuilder` with the `serialized:` item reference, `SkullBuilder`, `Items.giveOrDrop`,
    `InventoryFit`, `PdcKey`, `Colors`, `Sounds`.
  - Time: `Durations`, `Expiry`, `CooldownManager`.
  - Logging: `ThrowLog` (rate-limited warnings), `Classes.present`.
  - Discord: `DiscordWebhook`, `WebhookPayload`.
  - `PublicApiRulesTest` fails the build when a public signature names a type outside the JDK, Paper,
    Adventure and `org.jetbrains.annotations`, when a static field holds a plugin, logger, executor or
    thread, or when the jar holds a resource file.
- `taffy-config` module: `ConfigLoader` (writes and upgrades `config.yml`), `StrictYaml`,
  `ConfigReader` (typed reading that collects every problem, with range, choice, decimal, long and
  `parsed` readers), `ConfigHolder` (reload guard), `RestartKeys`, `YamlFolder` (folder of files) and
  `ConfigLoadException`.
- `StrictYaml.read` and `YamlFileProvider` refuse a config file larger than 16 MiB with a
  `ConfigLoadException` ("<file> is larger than 16 MiB").
- `taffy-storage` module: `Storage` (SQLite, MySQL, MariaDB and PostgreSQL over HikariCP, transactions
  on its own database threads, opt-in rollback retry), `StorageSettings`, `Dialect`, `Migration` with
  an internal migrator, `Tx`, `TxWork`, `Params`, `Row`, `MoneyColumn` and the storage exceptions.
  HikariCP and the drivers are compile-only and appear in no published dependency.
- `taffy-storage`: `Baseline` and `Storage.start(Plugin, ErrorReports, StorageSettings, List<Migration>,
  Baseline, Clock)` take over the version table of a plugin's own migration runner on a database with no
  version table of storage's. The takeover records the migrations up to the old table's highest version as
  applied, with the checksum of their current scripts, renames the old table to `<table>_baseline` (or the
  first free `<table>_baselineN` when that name is taken, never dropping it), and then runs the later
  migrations. On SQLite the old table is found whatever the case its name was declared in. An interrupted takeover leaves either no version table
  of storage's, so the next start takes the database over again, or a complete one. Once storage's own
  version table exists the baseline is ignored.
- `taffy-hooks` module: `CustomItems`, `CustomItemPlugin` and `CustomItemRef` for ItemsAdder, Nexo,
  Oraxen and HeadDatabase; `EconomyProvider`, `EconomyResult` and `EconomyWatcher` for a Vault
  economy; `PermissionProvider` for LuckPerms; `RegionProvider` for WorldGuard;
  `PlaceholderExpansionBuilder` and `PlaceholderRegistration` for PlaceholderAPI and MiniPlaceholders; and
  `HookState`, `HookStatus` and `Detected` for what detection found. Every entry point takes the owner
  plugin, the `ErrorReports` and the owner's switch, and every integration gives a `HookStatus` with a
  reason for the owner where there is something to do. The integration APIs are compile-only and appear
  in no published dependency.
- `taffy-gui` module: menu layouts (`MenuLayout`, `ItemLayout`, `PagerLayout`, `MenuKind`), drawing
  (`Icon`, `MenuText`, `Frame`, `Pager`), the runtime (`Menus`, `Menu`, `MenuSession`, `MenuSettings`)
  with click safety from `Menus.listener()`, and text prompts in chat or an anvil (`Prompts`,
  `PromptRequest`, `Answer`) with their listener from `Prompts.listener()`.
- `ItemLayout.of(id, slots, material)` and the withers `action`, `permission`, `name` and `lore`, each
  returning a copy with only that value replaced.
- `Messages` keeps a YAML list as its lines and gains `has` and `lines`.
- `taffy-metrics` module: `MetricChart` (string, number, bool, string list and counter charts for bStats and
  FastStats from one definition), `MetricsBuilder` and `Metrics` (diagnostics readers, `report`, `shutdown`).
  Handled errors reported to `ErrorReports` reach FastStats error tracking under their source. A
  `taffy_version` usage chart reports to Taffy's own bStats and FastStats entries, beside each provider the
  plugin itself starts. bStats and FastStats are runtime dependencies, relocated by the plugin.
- `ErrorReports` holds the reports made before its first install, the first per source and at most 16, and
  delivers them on that install.
- `taffy-comficonf` module: `ComfiConfConnection` (the bridge guard, status, `notifyChanged`, `lockFor`,
  `close`), `ComfiConfState` and `ComfiConfStatus`, `ComfiConfLink`, `ComfiConfRegistration` and
  `YamlFileProvider` (one YAML file read, validated, written with the revision check and activated through
  the plugin's own reload). It builds on `taffy-config`; `comficonf-api` stays compile-only.
- `ApiRules` allows `com.comficonf.api` types in the public signatures of `so.alaz.taffy.comficonf` only.
- `taffy-test` module: `ApiRules`, `CapturedLog`, `FakeItems`, `FakeFolders`, `FaultyDataSource`,
  `JvmDefaults`, `RaceRunner` and `SimulatedClasspath`. `SimulatedClasspath.hiding` copies a module's own
  classes onto a loader that hides chosen packages, with their code source kept so coverage counts them.
- `Durations`, `Colors` and `Expiry` expose a public `EXPECTED` text and fail with
  `invalid <thing> '<value>': expected <EXPECTED>`.
- Catalog entries for Taffy's own modules: `alaz.taffy.platform`, `alaz.taffy.test` and
  `alaz.taffy.config`, `alaz.taffy.storage`, `alaz.taffy.hooks`, `alaz.taffy.gui`, `alaz.taffy.metrics` and `alaz.taffy.comficonf` resolve at the catalog's version. The module list is the Gradle property
  `taffy.modules`.
- `so.alaz.taffy.java` passes the Mockito jar on each test task's classpath as a Java agent, runs tests
  under the `tr-TR` locale and the `Pacific/Auckland` zone, and runs javadoc with
  `-Xdoclint:all,-missing` and `--no-fonts`.
- `so.alaz.taffy.coverage` gains `coverage.classMinimum`, a per-class floor. `so.alaz.taffy.library` sets it to
  0.50.
- The root `check` fails when a module listed in `taffy.modules` has no `PublicApiRulesTest`.
- `so.alaz.taffy.settings` settings plugin: applies `so.alaz.taffy.repositories` and exposes the shared version
  catalog as `alaz`.
- `so.alaz.taffy.repositories` settings plugin: applies the Java toolchain resolver and declares every
  repository the so.alaz plugins resolve from.
- `so.alaz.taffy.paper-plugin` convention plugin: one relocated shadow jar, `paper-plugin.yml` version
  expansion, the coverage gate, and the generated `RuntimeLibraries` class.
- `so.alaz.taffy.paper-api` convention plugin: sources and javadoc jars and the `library` publication to
  repo.alaz.so under the project group.
- `so.alaz.taffy.library` convention plugin: `so.alaz.taffy.paper-api` with tests and the coverage gate, for a
  published library module.
- `so.alaz.taffy.coverage` and `so.alaz.taffy.java` convention plugins: the coverage gate (`coverage.minimum`,
  `coverage.exclusions`) and the Java 25 toolchain, compiler flags and JUnit Platform on their own.
- `so.alaz.taffy:taffy-catalog` version catalog with the dependency versions of the so.alaz stack.

### Changed
- Breaking: `Storage.start` takes `(Plugin, ErrorReports, StorageSettings, List<Migration>, Clock)` and
  `Storage.over` takes `(Plugin, ErrorReports, Dialect, DataSource, String prefix, Clock)`; the name,
  version, data folder, logger and migration scripts come from the `Plugin`.
- Breaking: `PlaceholderExpansionBuilder.player`, `offline` and `global` accept only names made of the lower
  case letters a to z, the digits, `_` and `-` (`[a-z0-9_-]+`), so every player and global placeholder
  resolves in both PlaceholderAPI and MiniPlaceholders. Any other name throws `IllegalArgumentException`.
- Breaking: `new CustomItemRef(hint, id)` refuses a blank hint with `IllegalArgumentException`. Pass `null`
  for no hint. `CustomItemRef.parse` still maps a blank hint to `null`.
- Breaking: `Pager.Page` has four components, `index`, `pages`, `from` and `to`, and its constructor refuses
  numbers that do not describe a page with `IllegalArgumentException`. `previous()` and `next()` are computed
  methods, no longer components.
- Breaking: parse errors from `StrictYaml`, `ConfigLoader` and `YamlFileProvider` name the line and column
  where parsing failed ("<file> is not valid YAML: <problem> at line N, column M") instead of quoting the
  line, and the `ConfigLoadException` carries no cause.
- Breaking: `Scheduler` takes the plugin's `ErrorReports`: `new Scheduler(plugin, errors)` replaces
  `new Scheduler(plugin)`, and a `null` plugin or `ErrorReports` throws `NullPointerException`, as does a `null`
  task or `retired` callback passed to `entity`, before anything is scheduled. A task Paper runs for `global`,
  `async` (also through `asyncExecutor` and `asyncOrRun`), `delayedAsync`, `repeatingAsync` or `entity`, or a
  `retired` callback Paper runs, that throws anything (an undeclared checked exception included) is reported to
  it once under the source `scheduler.global`, `scheduler.async` or `scheduler.entity`, then rethrown unchanged,
  so Paper still logs it. Paper's region, async and entity schedulers fire no `ServerExceptionEvent`, so such a
  throw reaches FastStats error tracking only through this report. A task or `retired` callback run on the
  calling thread is not reported and its throw reaches the caller; `dispatch` reports nothing, and its future
  carries what the task threw.
- `DiscordWebhook` calls its failure callback at most once per post.
- `Text` logs a PlaceholderAPI or MiniPlaceholders pass that throws, a `LinkageError` such as
  `NoSuchMethodError` included, through `ThrowLog` under the sources `placeholderapi` and
  `miniplaceholders` (one stack trace, then at most one line per source per minute), and still renders
  without that pass.
- A `Placeholder.parsed` value that holds a section-sign code renders the code as its tag in `Text` and
  `Messages` instead of throwing `ParsingException`.
- `ConfigReader.text`, `strings`, `requiredText` and `strictText` read an unquoted date such as `2026-12-31`
  as the text `2026-12-31`. An unquoted date with a time warns and uses the fallback (`text`), is skipped
  (`strings`) or is an error (`requiredText`, `strictText`), as `parsed` already did. `ConfigReader.quoted`
  adds its error for any unquoted date, and its error reads "... it reads as a number, a date or
  true/false."
- `ConfigReader.describe`, and so every `ConfigReader` warning and error, writes a list longer than 100
  characters as "a list of <n> entries" ("a list of 1 entry" for one entry), a group of keys inside a list
  longer than 100 characters as "a group of <n> keys" ("a group of 1 key"), and cuts a text or any other
  value longer than 100 characters to its first 100 followed by "...".
- `ConfigLoader` saves through a temporary file named `config.yml.tmp` (for a symbolic link, the linked
  file's name followed by `.tmp`, in that file's folder), and a leftover one from a save that was cut off is
  removed at the next `load()`.
- On SQLite storage from `Storage.start`, `Storage` runs writes on one thread of their own,
  `<name>-db-writer`, one at a time in the order they were submitted; reads keep running on the other
  database threads. The futures of `Storage.async`, `asyncRetrying` and `start` complete on a read thread
  (`<name>-db-N`), so a stage chained onto a write without an executor never holds up the writer thread.
  While `close` drains they complete on the thread that ran the write.
- `Storage.close` waits with no limit for a drain of `Long.MAX_VALUE` nanoseconds or more, such as
  `ChronoUnit.FOREVER.getDuration()`, instead of throwing `ArithmeticException` with the pools left open,
  and waits no time for a negative drain.
- `Storage` documents that work, a task from `executor()` and a stage on a database thread that wait for
  other database work need another database thread free; with `asyncThreads` 1 such a wait never ends.
- The migration lock is keyed by the database and the version table (on PostgreSQL by the schema and the
  version table), so starts on different databases or schemas of one server no longer wait for each other.
  A start whose connection has no current database or schema fails with a `StorageException`.
- A `<prefix>schema_version` table that storage did not create stops the start with a `StorageException`
  before any backup or migration, and is left unchanged. Only a missing column counts as another layout; a
  version table that cannot be read for another reason, such as a denied read, a damaged file or a lost
  connection, fails the start with the database's own error.
- `taffy-metrics` leaves the JVM default uncaught exception handler alone: `MetricsBuilder.start()` gives
  FastStats a context-unaware global error tracker, and `Metrics.shutdown()` restores no handler. An uncaught
  exception on a thread the plugin starts itself is printed by the JVM again instead of being taken by FastStats
  and not printed when no other handler was set, and shutting down several plugins that shade Taffy in any order
  no longer leaves a disabled plugin's handler installed. FastStats error tracking still receives the
  `ErrorReports` reports and the plugin's own `ServerExceptionEvent` exceptions; a plugin that wants an error
  from its own thread tracked catches it and reports it through `ErrorReports`.
- A jar without `META-INF/faststats.properties` is refused at `MetricsBuilder.start()` with one warning,
  before FastStats writes `plugins/faststats/config.properties`, so the server keeps its first run.
- A plugin jar that bundles its own copy of `com.comficonf.api` reads `NOT_VISIBLE` with a reason naming the
  copy, instead of `FAILED`; nothing is registered or reported.
- `ComfiConfConnection.status()` gives `NOT_REGISTERED` with a reason, and `ComfiConfStatus.reason()` is
  documented for `NOT_REGISTERED`.
- The `taffy-comficonf` package description documents connecting again (close, then connect with the same
  supplier) and builds the `YamlFileProvider` once behind an API-free `Supplier<ComfiConfLink>`.
- `CapturedLog`'s Javadoc states that the wording of Taffy's own log lines is not part of its API and can
  change in any release.
