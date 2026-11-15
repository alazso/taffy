# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

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
- `taffy-storage` module: `Storage` (SQLite, MySQL, MariaDB and PostgreSQL over HikariCP, transactions
  on its own database threads, opt-in rollback retry), `StorageSettings`, `Dialect`, `Migration` with
  an internal migrator, `Tx`, `TxWork`, `Params`, `Row`, `MoneyColumn` and the storage exceptions.
  HikariCP and the drivers are compile-only and appear in no published dependency.
- `taffy-test` module: `ApiRules`, `CapturedLog`, `FakeItems`, `FakeFolders`, `FaultyDataSource`,
  `JvmDefaults`, `RaceRunner` and `SimulatedClasspath`.
- `Durations`, `Colors` and `Expiry` expose a public `EXPECTED` text and fail with
  `invalid <thing> '<value>': expected <EXPECTED>`.
- Catalog entries for Taffy's own modules: `alaz.taffy.platform`, `alaz.taffy.test` and
  `alaz.taffy.config` and `alaz.taffy.storage` resolve at the catalog's version. The module list is the Gradle property
  `taffy.modules`.
- `so.alaz.java` passes the Mockito jar on each test task's classpath as a Java agent, runs tests
  under the `tr-TR` locale and the `Pacific/Auckland` zone, and runs javadoc with
  `-Xdoclint:all,-missing` and `--no-fonts`.
- `so.alaz.coverage` gains `coverage.classMinimum`, a per-class floor. `so.alaz.library` sets it to
  0.50.
- The root `check` fails when a module listed in `taffy.modules` has no `PublicApiRulesTest`.
- `so.alaz.settings` settings plugin: applies `so.alaz.repositories` and exposes the shared version
  catalog as `alaz`.
- `so.alaz.repositories` settings plugin: applies the Java toolchain resolver and declares every
  repository the so.alaz plugins resolve from.
- `so.alaz.paper-plugin` convention plugin: one relocated shadow jar, `paper-plugin.yml` version
  expansion, the coverage gate, and the generated `RuntimeLibraries` class.
- `so.alaz.paper-api` convention plugin: sources and javadoc jars and the `library` publication to
  repo.alaz.so under the project group.
- `so.alaz.library` convention plugin: `so.alaz.paper-api` with tests and the coverage gate, for a
  published library module.
- `so.alaz.coverage` and `so.alaz.java` convention plugins: the coverage gate (`coverage.minimum`,
  `coverage.exclusions`) and the Java 25 toolchain, compiler flags and JUnit Platform on their own.
- `so.alaz.taffy:taffy-catalog` version catalog with the dependency versions of the so.alaz stack.
