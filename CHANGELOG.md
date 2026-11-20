# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Added
- `StorageSettings.versionTable(String)` names the table that records the applied migrations, on every dialect and
  with no table prefix added, so a plugin whose tables carry a fixed name keeps its version table beside them. The
  name is 1 to 54 lowercase letters, digits and underscores, starting with a letter or underscore.
  `StorageSettings.versionTable()` gives the name that was set, or `null` for the default.
- `StorageSettings.sqlite(String sqliteFile)` and `StorageSettings.server(Dialect dialect, Server server)` give
  settings with a pool of `Pool.of(4)`, 2 database threads and 5 SQLite backups, and `pool(Pool)`,
  `asyncThreads(int)`, `backupKeep(int)` and `versionTable(String)` each give a copy with that value replaced.
  `StorageSettings.Server.of(host, port, database, user, password, sslMode)` gives a server with no table prefix
  and `tablePrefix(String)` a copy with one. `StorageSettings.Pool.of(int maxSize)` gives a pool that keeps 1 idle
  connection and lets a caller wait 10 seconds, and `maxSize(int)`, `minIdle(int)` and
  `connectionTimeout(Duration)` each give a copy.
- `StorageSettings.SslMode.PREFER` encrypts the connection when the server offers TLS and connects without
  encryption when it does not, and never checks the server's certificate: `sslMode=PREFERRED` on MySQL (with
  `allowPublicKeyRetrieval=true`, for a login that ends up unencrypted) and `sslmode=prefer` on PostgreSQL, which
  is what those drivers do for a URL that names no mode. A plugin that connected with a bare JDBC URL so keeps what
  its owners have. The MariaDB driver has no such mode, so on MariaDB `PREFER` is `sslMode=disable`, that driver's
  own default: the connection is not encrypted, and `TRUST` or `VERIFY_FULL` encrypts it.
- `StorageSettings.Server.socketTimeout(Duration)` gives a server with another socket timeout, a whole number of
  seconds from 1 to 2147483, for a plugin whose statements or lock waits may pass the 30 seconds every server
  connection had. `Server.of` still gives 30 seconds, `Server.socketTimeout()` reads the value, and migrations
  still run without the timeout.
- `StorageSettings.Server.properties(Map<String, String>)` gives a server with properties that are handed to the
  JDBC driver with every connection, such as `connectTimeout`, `sessionVariables` or `ApplicationName`, and
  `Server.properties()` reads them as an unmodifiable map. A name that would replace a value storage sets itself
  throws `IllegalArgumentException`, in any letter case: `user`, `password`, `sslMode`, `ssl`, `useSSL`,
  `requireSSL`, `verifyServerCertificate`, `trustServerCertificate`, `disableSslHostnameVerification`,
  `sslfactory`, `allowPublicKeyRetrieval`, `useAffectedRows` and `socketTimeout`. `Server.toString()` shows the
  socket timeout and the names of the properties, never their values.
- `ConfigLoader.baselineVersion(int)` gives a loader that reads an owner's `config.yml` without a usable
  `config-version` as that version, where it counts as version 0. A plugin whose earlier releases shipped the file
  without the key sets the baseline to the layout those releases wrote: with `baselineVersion(1)` and a shipped
  `config-version: 1` such a file is neither rewritten nor copied, and once the shipped version is 2 it is upgraded
  from 1 and kept as `config.yml.v1.bak`. A file that holds a version is not affected.
- `StrictYaml.readLenient(Path, String, Consumer<String>)`, `YamlFolder.lenient(Consumer<String>)` and
  `Messages.loadLenient(Path, Supplier, Logger, String, boolean)` read a file that is not saved as UTF-8, such as
  one an owner saved as Windows-1252: each byte sequence that is not UTF-8 is read as the replacement character
  U+FFFD, and one warning per file names the file and the first line with a replaced character
  (`vouchers/gold.yml is not saved as UTF-8, so each byte that is not UTF-8 is read as the replacement character
  U+FFFD; the first is on line 3. Save the file as UTF-8.`). A lenient `Messages` keeps the other keys of such a
  language file, where the file was skipped as a whole. Strict reading stays the default of all three, and
  `ConfigLoader` and the bundled language file are always read strictly.
- `new YamlFolder(Path, BiConsumer<String, Throwable>)` hands the consumer the file name and the exception whenever
  the read or the parser throws a `RuntimeException`, so a bug in a plugin's parser can be logged with its stack
  trace. The file still gets its line in `Loaded.errors()` and the other files still load.

### Changed
- Breaking: `StorageSettings`, `StorageSettings.Server` and `StorageSettings.Pool` are final classes with no public
  constructor and are no longer records, so a value a later release adds breaks no plugin. The methods that read a
  value keep their names, the three still compare by value, and `Server.toString()` still hides the password.
  `StorageSettings.sqliteFile()` is empty for a server, and `StorageSettings.server(Dialect.SQLITE, server)` throws
  `IllegalArgumentException`. A record pattern over one of the three no longer compiles. Replace
  `new StorageSettings(Dialect.SQLITE, file, null, pool, threads, keep)` with
  `StorageSettings.sqlite(file).pool(pool).asyncThreads(threads).backupKeep(keep)`,
  `new StorageSettings(dialect, file, server, pool, threads, keep)` with
  `StorageSettings.server(dialect, server).pool(pool).asyncThreads(threads)`,
  `new StorageSettings.Server(host, port, database, user, password, sslMode, prefix)` with
  `StorageSettings.Server.of(host, port, database, user, password, sslMode).tablePrefix(prefix)` and
  `new StorageSettings.Pool(maxSize, minIdle, timeout)` with
  `StorageSettings.Pool.of(maxSize).minIdle(minIdle).connectionTimeout(timeout)`.
- Breaking: `StorageSettings.SslMode` has a fourth constant, `PREFER`, between `DISABLE` and `TRUST`, so a `switch`
  expression over the three earlier constants no longer compiles, a `switch` statement runs no branch for it, and
  the ordinals of `TRUST` and `VERIFY_FULL` are one higher. The Javadoc of the enum now states that constants may
  be added in a later release. Add a `default` branch to each `switch` over `SslMode`, and store a mode by its
  name, not its ordinal.
- Breaking: on MySQL, MariaDB and PostgreSQL the applied migrations are recorded in
  `<prefix><plugin>_schema_version`, where `<plugin>` is the plugin's name in lowercase with every character other
  than a lowercase letter, a digit or an underscore replaced by an underscore, and no longer in
  `<prefix>schema_version`. Two plugins that share a database and a table prefix therefore keep a migration history
  and a migration lock each, where the second to start stopped with `set up by a newer version of the plugin` or
  `changed after it ran on this database`. On the first start under `0.4.0`, a `<prefix>schema_version` in storage's
  layout that records at least one migration with the checksum of one of this plugin's scripts is this plugin's
  earlier version table: its rows are copied into the new table, one line names both tables, and the earlier table
  stays as it was and is not read again, so a server that still runs a build on `0.3.2` against the same database
  keeps starting. The start holds the migration lock of the earlier table too while it copies. A migration applied
  under `0.4.0` is recorded only in the new table, so a build on `0.3.2` or earlier that holds that migration runs
  it again: update every server that shares the database before the plugin ships a new migration. SQLite keeps
  `schema_version`. The warning about tables under another prefix names only a version table that holds this
  plugin's checksums. A plugin, test or query that reads `<prefix>schema_version` reads the new name, or the plugin
  keeps the earlier name with `settings.versionTable(settings.tablePrefix() + "schema_version")`.
- Breaking: `Storage.start` throws `IllegalArgumentException` when `StorageSettings.asyncThreads()` is above
  `StorageSettings.Pool.maxSize()`, where it started and the threads beyond the pool's size failed with a
  connection timeout under load. Each database thread uses one connection at a time, so connections above
  `asyncThreads` are never used. Set `maxSize` to `asyncThreads` or more.
- Breaking: a migration script that is still to run stops the start, before the backup and before any migration,
  when it holds what the statement splitter cannot read: a single-quoted string that does not end on the line it
  starts, the start of a block comment (`/*`) or dollar quoting (`$$`) outside a quoted string, or a quote directly
  after a backslash. The `StorageException` names the file and the line. Such a script was split wrongly or ran by
  accident. A script that already ran is only compared by its checksum and never stops a start for this. Write
  comments as `--`, keep each quoted string on one line, write a quote inside a string as two quotes, and create a
  function or trigger outside the migration scripts.
- Breaking: a commit the database refuses because it rolled the transaction back (an
  `SQLTransactionRollbackException`, or an `SQLException` whose SQLState or that of a cause starts with `40`, such
  as the `40001` a PostgreSQL `SERIALIZABLE` transaction gets at its commit) completes the future of `async` and
  `read` with that exception and is run again by `asyncRetrying` and `readRetrying`, where it completed the future
  with a `CommitUnknownException` that was never retried. Nothing was written by such a commit. Any other failed
  commit is a `CommitUnknownException` as before. Code that caught `CommitUnknownException` to retry a
  serialization failure itself calls `asyncRetrying` and drops that branch.
- Breaking: on SQLite storage from `Storage.start`, a write transaction (`async`, `asyncRetrying`) begins with
  `BEGIN IMMEDIATE`: the writer connection is opened with `transaction_mode=IMMEDIATE`. While another process holds
  the file's write lock, the work waits for it up to the busy timeout of 5 seconds and then reads what that process
  wrote, where work that read and then wrote failed at once with `SQLITE_BUSY`. Work passed to `async` that only
  reads now holds the write lock for as long as it runs, so pass work that only reads to `read`. A test that opens
  `Storage.over` on SQLite and should behave as `start` adds `transaction_mode=IMMEDIATE` to its properties.
- Breaking: the file name of a SQLite backup holds the plugin version with every character other than an ASCII
  letter, a digit, a dot, an underscore or a hyphen written as an underscore, so a version such as `1.4.0+build.7`
  gives `data-1.4.0_build.7-<time>.db` and a version with a path separator cannot leave the `backups` folder.
  Backups an earlier release wrote are still counted and pruned. A tool that looks a backup up by the version
  replaces those characters the same way.
- Breaking: `ConfigLoader.load` throws `ConfigLoadException` with `the config.yml inside the plugin jar has no
  whole number of 1 or more under config-version` when the shipped file has no `config-version`, or has one that is
  quoted, a decimal, text, below 1 or above 2147483647. It throws on every load, the first start included, and
  writes nothing. Such a shipped file counted as version 0, so no owner's file was ever upgraded and nothing was
  logged. Write `config-version: 1`, without quotes, into the shipped `config.yml`; a plugin whose released
  versions shipped the file without the key also calls `baselineVersion(1)`, so the files owners have are left as
  they are.
- Breaking: the message of `StrictYaml.read` for a file that is not UTF-8 names the line: `<source> is not saved as
  UTF-8: the first byte that is not UTF-8 is on line <n>`, where it ended after `UTF-8`. The same message is the
  error line of `YamlFolder` for such a file and the message of `ConfigLoader.load` for such a `config.yml`. Code
  that compares the whole message compares its start: `message.startsWith(source + " is not saved as UTF-8")`.
- Breaking: the messages for a file or folder that cannot be read or written no longer hold its path.
  `StrictYaml.read` gives `cannot read <source>: <exception class>`, followed by a space and the reason when the
  I/O failure states one (`cannot read config.yml: NoSuchFileException`), where it gave
  `cannot read <path>: <exception class> <path>`. `ConfigLoader.load` gives `cannot write config.yml: ...` and
  `YamlFolder.load` gives `cannot read the folder <folder name>: ...` in the same form, and so does the reason in
  the `Could not save the upgraded config.yml` warning. The I/O failure is still the cause, so a logged stack trace
  holds the path. Code that read the path out of the message takes it from the `Path` it passed, or from
  `FileSystemException.getFile()` of the cause.
- Breaking: `YamlFolder` skips every file whose name starts with a dot, without an error line, so a hidden file and
  the `._<name>.yml` files macOS writes beside a file are no longer reported as files that are not UTF-8, and
  `loadOne` returns `null` for an id that starts with a dot. `YamlFolder.load` also adds the error line
  `<name>.yaml: rename it to <name>.yml to have it read` for a regular file whose name ends in `.yaml`, which was
  skipped without a word. Give an entry file a name that does not start with a dot, and rename a `.yaml` file to
  `.yml` or drop its line from `Loaded.errors()`.
- Breaking: `new ConfigLoader(Path, Supplier, Logger, Map)` throws `IllegalArgumentException` when a listed
  owner-keyed path is blank or when one listed section lies inside another, such as `kits.special` beside `kits`.
  The Javadoc already ruled out the second, and which of the two sections applied to a key was not defined. List
  each owner-keyed section once and none inside another.
- The warning of `Messages` for a language file that is not UTF-8 names the line:
  `lang/<name> is not saved as UTF-8 (the first byte that is not UTF-8 is on line <n>), so its messages are not
  used.` A file that is not valid YAML or cannot be read keeps `lang/<name> is not valid YAML saved as UTF-8, so
  its messages are not used.`

### Fixed
- The checksum of a migration script ignores line endings and a leading byte order mark: it is taken over the text
  with every line ending as a line feed and without the mark. A build whose scripts were checked out with CRLF (git
  `autocrlf` without a `.gitattributes` entry) starts on a database that ran the LF scripts, where every start
  stopped with `migration V1__... changed after it ran on this database`. A row written by `0.1.0` to `0.3.2` holds
  the checksum of the script's exact bytes and is accepted while the script still has those bytes, and new rows
  hold the new checksum. A script with a byte order mark runs: the mark is no longer sent to the database with the
  first statement.
- On MySQL and MariaDB each statement of a migration and its progress record commit in one transaction, so a start
  interrupted between the two no longer runs a statement that changes rows, such as
  `UPDATE accounts SET balance = balance * 10000`, a second time. A DDL statement still commits on its own before
  its record and must stay rerunnable.
- On PostgreSQL a start waits at most a day for the migration lock, as on MySQL and MariaDB, and then fails with
  `another start has held the migration lock of <version table> in database <schema> for a day; restart once the
  other server has started`, where it waited with no limit for a holder that stayed connected.
- On SQLite a migration is no longer rolled back for rows that broke a foreign key before it ran.
  `PRAGMA foreign_key_check` runs before the migration and again before it commits, the migration fails only for a
  row the second check finds and the first did not, and one warning per start names the tables of the rows that
  were already broken.
- The Javadoc of `Params.time` states its range: an instant whose milliseconds since the epoch fit a `long`. It
  throws `ArithmeticException` for `Instant.MAX`, `Instant.MIN` and any other instant outside that range, as
  before, so a plugin stores "never" as `null`.
- The Javadoc of `Storage.async` states that on MySQL, MariaDB and PostgreSQL writes run at the same time on up to
  `asyncThreads` threads, each on a connection of its own, in no order relative to each other. Work that reads a
  row and then writes a value computed from it is correct on SQLite and loses an update on a server unless it
  writes with one conditional or relative `UPDATE` or selects the row with `Dialect.forUpdate()`, whose Javadoc
  points back to this rule. It also states that on MySQL and MariaDB a select without `forUpdate()` that runs
  again inside one `TxWork` sees the transaction's first snapshot (`REPEATABLE READ`), so a loop that reads,
  computes and writes until its write counts a row runs one `TxWork` per attempt or reads with `forUpdate()`.
- The save of a `ConfigLoader` upgrade writes every text on one line. A text longer than 80 columns, such as a
  MiniMessage line, was folded over several indented lines. The class Javadoc lists what the save may still
  change: quotes, the form of a number, a date or `true`/`false`, the place of an added key (after the keys the
  owner has in that section), comments inside a list, line endings and a byte order mark.
- A `ConfigLoader` upgrade whose text cannot be produced no longer throws out of `load`: a text with half of a
  surrogate pair (`"a\ud83db"`) made the YAML writer throw a `RuntimeException` after the `.bak` copy was written.
  The text is now produced before anything is written, parsed again and compared with the upgraded settings. When
  it cannot be produced, or a value would not be read back as it is (the writer turns a text with a control
  character such as `"\a"` into `!!binary`, which loads as bytes), nothing is written, no copy is made, the warning
  `Could not save the upgraded config.yml: ...` names the reason and `load` returns the upgraded settings.
- The `config.yml.v<N>.bak` copy of a `ConfigLoader` upgrade is written through a temporary file that is flushed to
  the device before the upgraded file replaces the original, so a power loss cannot leave an upgraded file beside
  an empty copy. The copy holds the bytes the upgrade read and gets the POSIX permissions of `config.yml`.
- `ConfigReader.parsed` treats a `DateTimeException` of the parser as a rejection, as it treats an
  `IllegalArgumentException`: `LocalTime::parse` for `25:99` and `ZoneId::of` for `Mars/Phobos` give the fallback
  and the warning, where the `DateTimeParseException` or `ZoneRulesException` left `parsed` and failed the load.
  Any other exception of the parser is still thrown on.
- `ConfigReader.integer` and `optionalInteger` tell an owner the range for a whole number that does not fit an
  `int`: `limit is 5000000000; expected a whole number from -2147483648 to 2147483647, so 10 is used.`, where the
  warning said `expected a whole number`. `integer(path, fallback, min, max)` names its own range
  (`expected 1 to 10`), and `whole` names the `long` range for a number beyond it.
- `RestartKeys.changed` compares a `!!binary` value by its bytes, also inside a list, where an unchanged binary
  value was reported as changed on every reload. Its Javadoc states that a number written in another form
  (`3306` and `3306.0`) counts as a change.

## [0.3.2] - 2026-11-20

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `EconomyResult.moved()` tells whether the money moved: `true` for `OK` and `OVERDRAWN`, `false` for
  `INSUFFICIENT`, `UNSUPPORTED`, `FAILED` and `NOT_STARTED`. A plugin that refused a purchase on
  `status() != Status.OK` kept the money of an `OVERDRAWN` withdraw and delivered nothing. An `OVERDRAWN`
  withdraw is a completed payment: the plugin delivers what was paid for and may log it, and one that accepts no
  overdraft gives the amount back with `deposit`.
- `EconomyProvider.vault(VaultOptions options)` looks the Vault economy up with a `VaultOptions`, which carries
  what the other entry points take as arguments and the options they have no argument for.
  `VaultOptions.of(Plugin owner, ErrorReports errors, boolean folia)` gives a lookup that is switched on, uses the
  economy's own decimal places and calls the economy on the global region thread, and `switchedOn(boolean)`,
  `scale(Integer)` and `threadRule(VaultOptions.ThreadRule)` each give a copy with that value replaced.
  `vault(owner, errors, switchedOn, folia, scale)` is unchanged and gives the same result as
  `vault(VaultOptions.of(owner, errors, folia).switchedOn(switchedOn).scale(scale))`.
- `VaultOptions.ThreadRule` names the thread the Vault economy is called on. `GLOBAL`, the default, is the rule of
  every earlier release: `thread(player)` answers `ThreadTag.GLOBAL`. `ENTITY` is an opt-in for an economy plugin
  that is safe to call on region threads: `thread(player)` answers `ThreadTag.entity(player)` for a player who is
  online, so a plugin that is already on that player's region thread calls the economy inline, and
  `ThreadTag.GLOBAL` for a player who is not online. Under `ENTITY` the calls for one player are ordered on that
  player's thread and, on Folia, are not ordered against another plugin that moves the same account on the global
  region thread.
- `VaultOptions.FundsCheck` names who decides whether a balance covers an amount, set with
  `VaultOptions.fundsCheck(VaultOptions.FundsCheck)`. `EMULATED`, the default, is the check of every earlier
  release: `has` compares the balance the provider reads with the amount, and `withdraw` gives `INSUFFICIENT` for
  a short balance without asking the economy. `NATIVE` is an opt-in for an economy that allows an overdraft or a
  loan, or keeps rules of its own: `has` answers what Vault's `Economy.has` answers for the amount rounded to
  `scale()`, and `withdraw` reads no balance, runs `start` and lets `withdrawPlayer` decide. Under `NATIVE` a
  withdraw the economy refuses gives `FAILED` with the economy's message, for a short balance too, since a Vault
  response has no type for insufficient funds, and a withdraw never gives `INSUFFICIENT` or `OVERDRAWN`: one that
  leaves the balance below zero gives `OK`. The amount, the thread and the disabled economy plugin are checked as
  under `EMULATED`.
- `Params.decimal(BigDecimal)` and `Row.decimal(String)` bind and read a decimal number without scaling or
  rounding it, for a balance that passes `MoneyColumn.MAX` (about 9.22e14), which an inflated economy reaches:
  `Params.money` throws `MoneyOutOfRangeException` for such a balance and `Params.moneyOrNull` stores `NULL`.
  The value is bound as a `DECIMAL` on MySQL and MariaDB and a `NUMERIC` on PostgreSQL, and as the text of
  `BigDecimal.toString` on SQLite. The plugin declares the column in each migration script:
  `DECIMAL(precision, scale)` on MySQL and MariaDB (up to 65 digits), `NUMERIC` on PostgreSQL and `TEXT` on
  SQLite, where a column declared `NUMERIC` or `DECIMAL` loses digits beyond the 15th. On SQLite the stored
  values compare as text, so `ORDER BY`, `<`, `>`, `MIN` and `MAX` over the column are not numeric there (`10`
  sorts before `9`), and arithmetic in SQL, `SUM` included, converts the text to a floating-point number and
  loses digits; a column that is sorted, compared or summed in SQL on every database stays a `money` column.
  `Row.decimal` returns the scale the column holds, so compare with `compareTo`.
- `Params.signedMoney(BigDecimal)` binds a signed amount for a `money` column like `moneyOrNull`, and throws
  `MoneyOutOfRangeException` for an amount above `MoneyColumn.MAX` in magnitude where `moneyOrNull` stores
  `NULL`. `MoneyColumn.toStoredSigned(BigDecimal)` is the encoding behind it. `moneyOrNull` and
  `MoneyColumn.toStoredOrNull` are unchanged.

### Changed
- `balance`, `has`, `withdraw` and `deposit` of the Vault economy throw `IllegalStateException` when the calling
  thread is not the one `thread(player)` names, with a message that names `thread(player)` and
  `Scheduler.dispatch`. This is a fix: the `EconomyProvider` Javadoc always stated that every call runs on that
  thread, and nothing enforced it, so a call from a Folia region thread or an async thread ran unordered against
  the other callers of the economy and the balance check of a withdraw could pass twice. The thread is the one
  `Server.isGlobalTickThread()` is `true` on for `ThreadTag.GLOBAL` and the one
  `Server.isOwnedByCurrentRegion(player)` is `true` on for an entity tag; under `VaultOptions.ThreadRule.ENTITY` a
  call for a player who is not online is checked against the global region thread. The check comes after the
  `null` checks and before the amount is checked or anything in the economy is called, so no money moves.
  `scale()`, `thread`, `probe()` and the lookup are not checked. Upgrading: a call made on the main thread of a
  Paper server, or through `Scheduler.dispatch` on the thread `thread(player)` names, is unchanged. A call made
  anywhere else threw nothing before and throws now: an async thread on Paper, and every region thread on Folia
  under the default rule, so the mistake shows mostly on Folia. Run each call through
  `scheduler.dispatch(economy.thread(player), () -> economy.withdraw(player, amount))`, or, for a plugin that
  calls the economy inline on the player's region thread with an economy plugin that is safe there, look the
  economy up with `VaultOptions.threadRule(VaultOptions.ThreadRule.ENTITY)`.
- `addGroup`, `addTempGroup`, `removeGroup`, `setPermission` and `unsetPermission` of
  `PermissionProvider.luckPerms` throw `IllegalStateException` when called on a server thread, with a message that
  names the calling thread and `Scheduler.async`. This is a fix: the `PermissionProvider` Javadoc always stated
  that writes run off the server thread, since a write loads the player's data when it is not cached and blocks
  until the change is saved, and nothing enforced it, so a write from an event handler held the tick for a
  storage round trip. A server thread is one `Server.isPrimaryThread()` is `true` on: the main thread on Paper,
  and every region thread and the global region thread on Folia. The check comes after the arguments are checked
  and before LuckPerms is asked, so nothing is loaded or changed, and it is made whether or not LuckPerms is
  available. `available`, `primaryGroup` and `groups` are not checked. Upgrading: a write made on an async thread
  is unchanged. A write made on a server thread returned its result before and throws now: move it off the
  thread with `scheduler.async(() -> permissions.addGroup(player, group))` and take the result there. A write
  made in `onDisable`, directly or through `Scheduler.asyncOrRun`, blocked and saved before and throws now, since
  `asyncOrRun` runs the task on the calling server thread once the plugin is disabling, and `Scheduler.async`
  returns `false` there and never runs it: make that write before the plugin disables.

### Fixed
- The Vault economy of `EconomyProvider.vault` no longer refuses a payment over the representation error of a
  `double`. An economy plugin that keeps balances as `double` holds `0.09999999999999998` after a player with
  `1.00` pays `0.90`, and `has` answered `false` and `withdraw` gave `INSUFFICIENT` for `0.10` without asking
  the economy, although `balance` read `0.10`. A balance read from Vault is now rounded half-even to 8 decimal
  places, four more than `EconomyProvider.MAX_SCALE`, before it is compared or rounded to `scale()`, and a
  balance within 16 units in the last place of the `double` of an amount at `scale()` reads as that amount, so a
  stored `1000000428.0799999`, which is `1000000456.18 - 28.10`, covers `1000000428.08`. The balance read after
  a withdraw is measured in units of the balance read before it. A balance that is short by more than that, such
  as `0.0999` or `0.995`, is still short, and one below zero by more than that is still `OVERDRAWN`. Where one
  such unit nears the smallest amount at `scale()`, which is from about 1e13 at 2 decimal places and from about
  1e11 at 4, a `double` no longer holds the amount and a comparison can be off by one unit of `scale()`.
- A `withdraw` of the Vault economy returns `OK` when the economy confirmed it and the balance read that follows,
  which tells `OVERDRAWN`, throws an exception or a `LinkageError`. The throwable left `withdraw`, so a withdraw
  that had moved the money read as one with an unknown outcome. The overdraft check is skipped for that call and
  a warning is logged to the owner's logger, at most one line a minute after the first. Any other `Error` still
  propagates.
- `withdraw` and `deposit` of the Vault economy return `FAILED`, with the message
  `the economy plugin <name> is disabled`, while the plugin that registered the economy is disabled. The provider
  kept the `Economy` object found at lookup and called it whatever its plugin's state, so during a server stop,
  or in a plugin with no `EconomyWatcher`, a payment reached a disabled economy plugin. The refusal comes before
  the amount is checked and calls nothing in the economy, so no money moves. `balance` and `has` read the economy
  as before, and the status of the `Detected` still changes only through a new lookup.
- `PermissionProvider.removeGroup` removes a membership that `addTempGroup` added. It built a permanent group
  node and asked LuckPerms to remove that node, which matches no node with an expiry or a context, so a rank
  granted for 30 days and revoked early stayed and the call returned `false`. It now clears every membership of
  the group set directly on the player, permanent or temporary, in every context, and returns `false` with
  nothing saved when the player holds none. The group name is matched without regard to case, and only saved data
  is changed, as with `unsetPermission`.
- `PermissionProvider.setPermission` changes a node the player already holds. LuckPerms refuses to add a node
  whose name and context are already held, whatever its value or expiry, so `setPermission(player, "fly.use",
  false)` on a player who held `fly.use` granted returned `false`, saved nothing and left the permission granted,
  which a caller could not tell from a node that was already denied. It now replaces every node of that name set
  directly on the player without context: a granted node becomes denied, a denied one granted, and a temporary
  one permanent, with one save. It returns `false` with nothing saved only when the player already holds the
  node without context and without an expiry at that value. A node of the same name set in a context is left as
  it is. When LuckPerms refuses the new node once the held ones are cleared, that is logged as a warning and
  reported under `hook.luckperms`, and the call still saves and returns `true`, since the held nodes are gone.
- A write of `PermissionProvider.luckPerms` reaches the other servers of a network at once. The provider saved
  the change and told nobody, so a server that had the player's data loaded kept the old groups and permissions
  until its next sync. After a successful save the provider now calls `pushUserUpdate` on LuckPerms'
  `MessagingService` when one is set up, and pushes nothing when there is none. A push that throws is logged as a
  warning and reported under `hook.luckperms`, and the write still returns `true`, since the change is saved.

## [0.3.1] - 2026-11-20

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `CustomItems.close()` unregisters every listener `CustomItems.detect` registered for the owner, such as the
  one that waits for `ItemsAdderLoadDataEvent`. A plugin that detects again from its reload command calls it on
  the `CustomItems` it holds first, so no listener of an earlier detection stays registered. A closed
  `CustomItems` keeps answering, but a plugin that was still loading its items stays `LOADING` in it. A second
  call does nothing. A plugin that detects again without calling it leaves one listener registered for each of
  ItemsAdder, Nexo, Oraxen and HeadDatabase whose integration is on, where `0.3.0` left one for ItemsAdder only,
  until the plugin disables.
- `CustomItems.build(String reference)` builds the item a reference names as `create` does and returns a
  `CustomItems.Built`: the item, or a `Built.Reason` that says why there is none. `UNKNOWN_PLUGIN` is a
  reference whose text before the first colon names no plugin and that no plugin knows whole, `PLUGIN_OFF` a
  plugin with no integration on or no longer enabled, `NOT_READY` a plugin still loading its items, `UNKNOWN_ID`
  an id the plugin does not know, and `FAILED` a plugin that threw. `create` is unchanged: it returns
  `build(reference).item()`, so `null` for every one of these.
- `ItemBuilder.deserializeOrThrow(String reference)` rebuilds the item of a `serialized:<base64>` reference or
  throws `IllegalArgumentException` with a message that tells three failures apart: a reference that does not
  start with `serialized:` (no cause), text after the prefix that is not base64 (the cause is the decoder's
  exception), and bytes the server refused because they are corrupt or were written by a newer data version (the
  cause is the server's exception). A plugin that stores items calls it to log why a stored item is gone.
  `ItemBuilder.deserialize` is unchanged: it returns `null` for each of these and still skips the prefix by
  length without checking it. The `ItemBuilder.serialize` Javadoc now states that an empty stack has no
  reference: the item goes to `ItemStack.serializeAsBytes()` as it is, the server throws for air or an amount of
  0, and a `null` item throws `NullPointerException`.

### Fixed
- `Detected.status()` and `CustomItems.statuses()` say why an integration stays off after its plugin was loaded
  again as a new plugin instance, as a plugin manager does. An integration stays bound to the instance found at
  detection, so its status read `NOT_ENABLED` with no reason while the new instance was enabled. The
  `NOT_ENABLED` status now carries a reason in that case, which tells the owner to restart the server or to run
  the plugin's reload command if it detects again. The state, the provider and every other status are unchanged.
- `RegionProvider.worldGuard` gives `NOT_VISIBLE`, with a reason that names the missing `WorldEdit` entry, when
  WorldGuard is visible and WorldEdit's `BukkitAdapter`, which every region lookup goes through, is not. It was
  `FAILED` with a report under `hook.worldguard` and no word on what to change. A plugin that uses WorldGuard
  lists both `WorldGuard` and `WorldEdit` under `dependencies.server` in its `paper-plugin.yml`.
- `CustomItems.create` builds an ItemsAdder id written without its key, such as `gems:ruby`. The text before
  the first colon was read as a plugin key, `gems` named no plugin, and `create` returned `null` without asking
  any plugin. A reference whose text before the first colon names no `CustomItemPlugin` is now offered whole to
  each plugin in order, as a reference without a colon is, and the first item built wins. A reference that starts
  with a plugin's key or alias still goes only to that plugin, and `CustomItemRef` parses as before.
- `CustomItems` waits for Nexo and Oraxen to load their items before it builds one. Nexo knows no item until
  its first load ends, so during a server start `create("nexo:ruby")` returned `null` as for an unknown id while
  `ready()` read `true` and `statuses()` read `ON`. Nexo is now `LOADING` until `NexoItemsLoadedEvent` and
  Oraxen until `OraxenItemsLoadedEvent`, or `ON` at once when the plugin already knows an item name at
  detection, which is the usual case for Oraxen, since it loads its items while it enables. While one of them
  loads, `create` skips it, `build` gives `NOT_READY`, `ready()` is `false` and `statuses()` reads `LOADING`.
  `resolve` and `idOrNull` still read a Nexo or Oraxen id during the load, since the id is in the item's own data. Each of the two integrations registers a listener for the owner, which
  `CustomItems.close()` unregisters, so `detect` runs while the owner is enabled, as it already does for
  ItemsAdder. A `detect` that follows an items-loaded event an earlier `detect` of the same plugin heard in this
  server run reads `ON` at once. With no such event heard, a Nexo or Oraxen that loaded with no item at all reads
  `LOADING` after a `detect` that follows its load, until its next items-loaded event.
- `CustomItems` waits for HeadDatabase to load its database before it builds a head. HeadDatabase knows no head
  until that load ends shortly after startup, so `create("hdb:1234")` in `onEnable` returned `null` as for an
  unknown id while `ready()` read `true` and `statuses()` read `ON`. HeadDatabase is now `LOADING` until
  `DatabaseLoadEvent` when it gives no random head at detection: `create` skips it, `build` gives `NOT_READY`
  and `ready()` is `false` until then. Its integration registers a listener for the owner, which
  `CustomItems.close()` unregisters. A `detect` that follows a `DatabaseLoadEvent` an earlier `detect` of the
  same plugin heard in this server run reads `ON` at once. HeadDatabase has no query for whether its database is
  loaded, so any other `detect` asks it for a random head and reads `ON` at once when it gives one. A database
  that holds no head gives none, so it then reads `LOADING` until its next `DatabaseLoadEvent`.

## [0.3.0] - 2026-11-20

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `Answer.Later(CompletableFuture<Answer>)` is a third result of a `PromptRequest` answer function, for a
  decision that needs storage or an economy and must not block the player's entity thread. The future completes
  on any thread and its result is applied on the player's entity thread as a result the function returned: an
  `Accept` opens its menu, a `Retry` tells the player and waits for the next answer, another `Later` waits again.
  While the future runs the ask takes no chat line and no anvil submit, and an anvil stays open. The ask's first
  deadline, the cancel link, a closed anvil, another menu, a newer ask, a quit and `Menus.shutdown` still end it,
  and the result of a future that completes after that, or for a player who left the server, is dropped. A future
  that fails or completes with `null` is logged, reported under `gui.prompt`, tells the player `gui.error` and
  opens the origin, as an answer function that throws does; a cancelled future opens the origin with no message.
- `Frame.list(int count, int requestedPage, IntFunction<Frame.Entry> entryAt)` fills the list from the number
  of entries and a function that gives the entry at an index. It works out the page first and asks for the
  entries on that page only, each once and in order, so a menu over a long list no longer builds an icon for
  every row of every page on each render. `Frame.list(List, int)` is unchanged and runs on top of it.
- `Menu.stateType()` names a type every state of the menu is an instance of. `Menus.open(Player, Route)` and
  `Menus.openLater(Player, Route)` throw an `IllegalArgumentException` that names the menu, that type and the
  type given for a route whose state is no instance of it, before anything opens or is scheduled. The default is
  `Object`, which accepts every state as before, so a wrong state then still fails inside `render` with a
  `ClassCastException`; a menu overrides it, such as `return Integer.class;` in a `Menu<Integer>`, to get the
  check.
- `EconomyProvider.vault(Plugin, ErrorReports, boolean switchedOn, boolean folia, Integer scale)` takes the server
  kind as a flag, `true` on Folia, so a plugin with no Taffy `Scheduler` can look the economy up. A plugin with
  one passes `scheduler.mode() == Scheduler.Mode.FOLIA`.
- `TextSource` is the text function the menus and prompts read: `render(Locale, String key, TagResolver...)` gives
  the component of a lang key, so a plugin that keeps its text outside Taffy's `Messages` passes a lambda such as
  `(locale, key, placeholders) -> lang.component(locale, key, placeholders)`. `has` and `lines` have defaults
  (every key is held, and the text is one line), which a source overrides to leave optional text out, such as lore
  no file holds, and to give lore one component per line. `TextSource.of(Messages)` is the source over Taffy's
  lang files, and `Menus.Parts.of` takes either a `TextSource` or the `Messages` themselves.
- `Frame.Entry.key(String)` gives a list row a stable key, such as the id of the listing it shows, and
  `Frame.Entry.key()` reads it, `null` for none. The key names the row across redraws: a keyed row that a redraw
  left in its slot stays clickable whatever its icon shows, so a row with a countdown or an animation takes
  clicks, a row whose key is new to its slot drops the clicks of the click debounce window after that redraw,
  and the per-button click debounce follows the key, so a row that moved to another slot is still debounced.
  `Frame.Entry` has `key` as a fifth record component; `Entry.click`, `Entry.display` and the four-argument
  constructor give a row with no key, and `exclusively()` keeps the key.
- `PromptRequest.abandoned(Consumer<PromptEnd>)` gives a question a step that runs once when its ask ends and the
  prompts open no menu for it, with how it ended as the new enum `PromptEnd`: `TIMED_OUT` and `CANCELLED` when
  the origin did not open because the player is dead, offline or has another plugin's inventory open, the server
  refused the origin's inventory or opening it failed, `LEFT` when the player quit, was gone, or was dead or
  offline when an accepted or failed answer was to open its menu, `REPLACED` when another menu, a newer ask or
  another inventory took the place of the ask or kept an answer's menu or anvil from showing, and also when the
  menu an accepted or failed answer was to open did not open, because the server refused its inventory, its route
  names no registered menu or the menu failed to draw, and `CANCELLED` also when `Menus.shutdown` ends the ask of
  a player the disabling thread owns.
  Before, nothing told a menu that had handed its session to an ask, with `Menu.closed(session, false)`, that the
  ask would never open it again, so what it kept for the reopened menu, such as escrowed items, was lost. An ask
  that opens a menu, its origin or the route of an `Answer.Accept`, calls nothing, and neither does one whose
  anvil the server refused to open, which closes the menu with `left = true`. The step runs on the player's
  entity thread, except `LEFT` for a player who is gone, and one that throws is logged and reported under
  `gui.prompt`. `PromptRequest` has `abandoned` as a fifth record component, and the four-argument constructor
  gives a request whose step does nothing. `PromptEnd` and `PromptMode` may gain constants in a later release,
  as their Javadoc now states, so a `switch` over either has a `default` branch.

### Changed
- Breaking: `Answer` has a third permitted subtype, `Answer.Later`, so a `switch` over an `Answer` that names
  `Accept` and `Retry` and has no `default` branch no longer compiles, and one compiled against 0.2.2 throws a
  `MatchException` when it is given a `Later`. An answer function that returns an `Accept` or a `Retry` needs no
  change. Give the `switch` a `default` branch, which also covers a result a later release adds, or a case for
  `Answer.Later`.
- Breaking: a record pattern over `PromptRequest` needs a fifth component, for `abandoned`. Every `new
  PromptRequest(question, initial, origin, answer)` compiles and behaves as before. Add the component to the
  pattern, as in `PromptRequest(var question, var initial, var origin, var answer, var abandoned)`.
- Breaking: a click on a menu slot is dropped for the click debounce window (250 ms by default) after a redraw
  put another button in that slot, as a click within that window of the menu opening already is. Before, a click
  that arrived just after `Menus.rerender` or a load redrew the menu ran the handler of whatever the slot showed
  by then, so when a sold listing shifted the rows up, a viewer aiming at one listing bought the next. Another
  button is a list row with another `Frame.Entry.key`, for a row with no key a row with another icon, a button
  of another layout item, or any button in a slot that had none; a layout action that redraws with other text
  or another icon keeps taking clicks. A list row with no key whose icon changes on every redraw, such as an
  animated one, therefore drops the clicks of the window after each redraw, and a record pattern over
  `Frame.Entry` needs a fifth component. Give each clickable row a key that stays the same while the row shows
  the same thing, as in `Frame.Entry.click(icon, handler).key(listing.id().toString())`.
- Breaking: a load clears `MenuSession.busy()` only for the hold that belongs to it: the exclusive button whose
  handler started the load, or a `busy(true)` taken in the same handler or `Menu.opened` as the `Menus.load` call.
  It clears that hold also when a later load started meanwhile. Before, the end of the latest load of a session
  released every hold, so a refresh or a page turn that ended while a claim held the session with `busy(true)`
  let the same claim run a second time. A handler that throws likewise clears `busy()` only when the session was
  not busy before it ran, so a failing button that is not exclusive no longer releases the hold of a claim in
  flight. A handler that takes `busy(true)` for work outside `Menus.load` and relied on a later load or a failing
  handler to release it calls `session.busy(false)` when its work ends.
- Breaking: `Menus.register` and `Menus.reload` throw an `IllegalArgumentException` for a material that is not an
  item a slot can show, air included: the material of an `ItemLayout` (shown or not), the `filler` of a
  `MenuLayout`, the `emptyMaterial` of a `PagerLayout`, and, in `register`, the `filler` and `loadingItem` of the
  `MenuSettings`. The message names the menu and the item, such as "item buy of the shop menu has the material
  WALL_TORCH, which is not an item". Before, such a layout registered and every open of the menu failed with a
  stack trace. A refused `reload` leaves the layouts as they were. Give each of these an item material, and
  catch the exception where the plugin reads layouts or settings from a file, to show the owner the message.
- `EconomyProvider.vault(Plugin, ErrorReports, boolean, Scheduler.Mode, Integer)` is deprecated and a later
  release removes it. It still works and gives the same result as the overload with the `folia` flag. Pass
  `scheduler.mode() == Scheduler.Mode.FOLIA` in place of `scheduler.mode()`.
- Breaking: `MenuSettings`, `ItemLayout`, `MenuLayout`, `PagerLayout` and `Menus.Parts` are final classes with no
  public constructor and are no longer records, so a value added to one in a later release breaks no caller. Each
  is built through a static factory that takes what has no default, and a method named after each other value
  gives a copy with that value replaced: `MenuSettings.DEFAULTS.pageSize(28)`, `ItemLayout.of(id, slots,
  material).action("buy")`, `PagerLayout.of(slots).emptySlot(22).emptyMaterial(Material.BARRIER)`,
  `MenuLayout.of(menu, rows).filler(material).pager(pager).items(items).title(title)` and `Menus.Parts.of(plugin,
  messages, tasks, entity,
  errors).layouts(layouts).settings(settings).locales(locales).sounds(sounds).home(home)`. The methods that read a
  value keep their names, `MenuSettings`, `ItemLayout`, `MenuLayout` and `PagerLayout` still compare by value, and
  every check a constructor made is made by the factory or the method that takes the value. `Menus.Parts` compares
  by identity, and a record pattern or `getRecordComponents()` over one of the five no longer works. Replace each
  `new` of these types with its factory and the methods for the values that differ from the defaults: no action,
  permission, name or lore and shown for an item, no empty state for a list, no filler, list, items or title for a
  layout, and no layouts, `MenuSettings.DEFAULTS`, `Player::locale`, no sounds, no home menu and the plugin's own
  server, logger and `new NamespacedKey(plugin, "preview")` for the parts.
- Breaking: `MenuLayout.items(Collection<ItemLayout>)` takes the items themselves, in the order
  `MenuLayout.items()` keeps, and keys each by its own id, where the constructor took a map and refused a key that
  differed from its item's id. Two items with one id are refused with an `IllegalArgumentException` such as "item
  buy is in the shop menu twice". `MenuLayout.item(ItemLayout)` adds one item, or replaces the item of the same id
  in its place. `MenuLayout.items()` still returns the map. Pass `map.values()` where the plugin built a map.
- Breaking: `Menus` and `Prompts` register their own listeners, so a menu can no longer open with nothing to
  cancel its clicks. `Menus.Parts.of` takes the owning `Plugin` first, `new Menus(parts)` registers the menu
  listener with the plugin manager of `Parts.server()` as a listener of `Parts.plugin()`, and `new Prompts(menus,
  ...)` registers the prompt listener the same way before it links to the menus. Before, a plugin that forgot
  `registerEvents(menus.listener(), plugin)` opened menus whose icons players could take. Build the menus while
  the plugin is enabled, such as in `onEnable`: for a plugin that is not, the server throws an
  `IllegalPluginAccessException` and no menus exist. The server, the logger and the preview key are no longer
  arguments: they default to `plugin.getServer()`, `plugin.getLogger()` and `new NamespacedKey(plugin,
  "preview")`, and `Parts.server(Server)`, `Parts.logger(Logger)` and `Parts.preview(NamespacedKey)` replace them.
  Pass the plugin to `Menus.Parts.of`, add `.preview(key)` when the plugin's preview key is not `new
  NamespacedKey(plugin, "preview")`, and delete both `registerEvents` calls.
- Breaking: `Menus.open`, `Menus.rerender`, `Menus.load` and `Prompts.ask` throw an `IllegalStateException` on
  a thread that does not own the viewer, such as the thread that completed a storage future, before they change
  anything. The message names the method and what to call instead, such as "Menus.open must run on the viewer's
  entity thread; use Menus.openLater". Before, the call went on off the viewer's thread, where it changed the
  session and the inventory unguarded on Paper and failed part way inside the server on Folia. `rerender` still
  does nothing for a closed session, and `open` and `ask` still do nothing once `Menus.shutdown` ran. From a
  callback on another thread call `Menus.openLater`, `Menus.rerenderLater` or `Prompts.askLater`, which schedule
  the work on the viewer's entity thread, and call `Menus.load` from `Menu.opened` or a `ClickHandler`, or hop to
  the viewer's thread first through `EntityThread.call`.
- Breaking: `Menus.listener()` and `Prompts.listener()` are deprecated, a later release removes them, and each now
  returns a listener with no event handler, so a `registerEvents` call left in a plugin registers nothing and
  cannot register the menus or the prompts twice. Delete the calls that pass them to `registerEvents`.

### Fixed
- A chat line that answers a `Prompts` ask is judged once. Paper posts the legacy `AsyncPlayerChatEvent` and then
  `AsyncChatEvent` for one line, and when the answer was refused, or its function started another ask, the ask
  waiting by the time the second event arrived took the same line again, so a refusal was told twice or the next
  question was answered with the previous text. The line the legacy event took is now left alone by the second
  event, which stays cancelled.
- A chat line that answers a `Prompts` ask loses its audience: the prompt listener empties
  `AsyncChatEvent.viewers()` and `AsyncPlayerChatEvent.getRecipients()` of the line it takes, where the set can
  be changed, and still cancels the event and leaves the message as typed. A chat plugin that delivers cancelled
  lines no longer shows the answer to other players or the console. The `PaperPromptListener` Javadoc now states
  that the event still reaches every handler that does not ignore cancelled events, at any priority, so such a
  handler can read the answer.
- The click debounce, the open arming of a menu and the 30 second deadline of a `Prompts` ask measure elapsed
  time with `System.nanoTime()`. They read the wall clock before, so a clock stepped back by a time
  synchronisation or by hand dropped every menu click until the clock passed the old time again, and a clock
  stepped forward let a click through early and timed a refused ask out at once.
- `Prompts.ask` throws a `NullPointerException` named `request` for a `null` request before it does anything, as
  `Prompts.askLater` does. Before, it closed the player's menu and replaced a pending ask first, then failed
  while sending the question. The `ItemLayout.permission` Javadoc now states that the permission decides only
  whether the item is drawn and that a click is not checked against it, so a handler whose action needs a
  permission checks it itself.
- `Menus.shutdown` empties the inventory of each open menu and prompt anvil whose viewer the disabling thread
  does not own, which happens only on Folia. Such a menu still stays open with no listener to cancel its clicks,
  but it now shows nothing, so its viewer has no menu copy to take. Before, it stayed fully stocked and a copy
  taken from it was deleted only when a click met it after a later enable. While the plugin stays enabled after
  `Menus.shutdown`, as in a reload command, the listener still cancels the clicks in such a menu and no longer
  runs the handler of the button the clicked slot held.
- `Frame.Spec.exclusively()`, `Frame.Entry.exclusively()` and the footer's refresh guard their handler with no
  `session.busy(true)` in it. When the handler of an exclusive button returns with a load it started through
  `Menus.load` still in flight, the session stays busy until that load applies, fails or is cancelled, and every
  click on an exclusive button is dropped meanwhile, whatever load another button starts or ends in that time.
  Before, `exclusively()` did nothing unless the handler called `MenuSession.busy(true)` itself, so a buy button
  without that call ran a second purchase once the 250 ms debounce passed. A handler that calls `busy(true)` keeps
  working: its hold lasts past the handler's return until `busy(false)`, the end of a load the same handler
  started, or that handler throwing, which is how a handler whose work runs outside `Menus.load`, such as a claim
  that calls back on the viewer's thread, holds the session and releases it in the callback. `MenuSession.busy()`
  still reads `false` inside the handler of the first click.
- The menu listener sends a player's inventory again, through `Player.updateInventory()` on the next tick on the
  player's entity thread, after it cancelled the player's shift-click in a menu view and after the viewer's menu
  closed, and `Menus.shutdown` sends it to each viewer whose menu it closes. The client moves a stack on its own
  screen before the server answers, so it could keep showing a stack the server never moved until the next
  inventory update. A menu that another inventory replaces sends nothing more, since the server sends the new
  inventory in full.
- The `Menu.closed` and `Menus` Javadoc state what `left = false` covers: these menus opened another menu in
  place of the session, a prompt took over through `Prompts.ask`, or the menus are stopping. They also state
  that a session never opens again once `closed` ran, since the menu a prompt opens afterwards is a new session,
  so what a session holds outside its state is released or moved out in `closed` whatever `left` is.
- A plugin with two `Menus`, such as one for player menus and one for admin menus, runs a button's handler once
  per click. Each `Menus` registers its own listener, and before, both listeners dispatched every click in any
  menu, so the handler ran twice, the click sound played twice, and the listener registered first ended every
  session with its own `Menus`, whichever had opened it. A session now belongs to the `Menus` that opened it: every
  listener still cancels the clicks and drags in a menu of any `Menus`, and only the listener of the owning
  `Menus` runs the handler, plays `Cue.CLICK` and `Cue.FAIL` and ends the session. The anvil of a `Prompts` ask
  is left alone by the listener of every `Menus`: before, the listener of a second `Menus` with the same preview
  key took the anvil's paper for a leaked menu copy, deleted it on a click, which stopped the name field from
  working, and logged a `preview.leak` warning.

### Removed
- Breaking: `Menus.Parts.messages()` and `MenuText.messages()` are gone, since the menus hold a `TextSource` and
  no longer the `Messages`. `Menus.Parts.text()` and `MenuText.source()` give the `TextSource`, whose `render`,
  `has` and `lines` take the same arguments as those of `Messages`. Call `menus.text().source().render(locale,
  key)` where a menu called `menus.text().messages().render(locale, key)`, and keep the plugin's own `Messages`
  for anything else it read from them.

## [0.2.2] - 2026-11-20

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `YamlFileProvider.Builder.notLoadedMessage(String)` sets the message of the `savedRestartRequired` result that
  `apply` returns when the file was saved and the activation did not put the values in effect, so the editor can
  name the plugin's reload command. A provider built without it keeps the message "The values were saved, but
  the plugin could not load them, so they are not in effect."
- `YamlFileProvider.Builder.backups(BackupPolicy)` sets the folder that receives the backups `apply` takes and
  the number kept. A provider built without it keeps the 10 newest in the `backups` folder beside the file, as
  before. Each backup is a full copy of the file as it was before the write, secrets included, which the
  `YamlFileProvider` Javadoc now states.

### Fixed
- `ComfiConfRegistration.register` compares the bridge's `ComfiConfApi.apiVersion()` with the
  `ComfiConfApi.API_VERSION` Taffy is compiled against, before it registers anything. A bridge with a lower
  version makes it throw an `IllegalStateException` that names both versions, and `ComfiConfConnection.connect`
  then gives `API_MISSING`, logs the one warning that asks the owner to update ComfiConf, with both versions in
  it, and reports nothing. Before, such a bridge was noticed only when a call reached a class or method it lacks.
  A bridge with the same or a higher version registers as before.
- `ComfiConfConnection.connect` reads the link's `resourceId()` once, with the registration, and
  `ComfiConfConnection.resourceId()` returns that id without asking the link again. A `ComfiConfLink` of the
  plugin's own whose `resourceId()` throws no longer lets the error leave `connect` or `resourceId()`: `connect`
  closes the link, which unregisters the resource, and treats it as a registration that failed, so it logs one
  warning with the stack, reports it under `hook.comficonf` and gives `FAILED`. The link
  `ComfiConfRegistration.register` returns never throws there, so nothing changes for it.
- `YamlFileProvider.read`, the first read of `YamlFileProvider.apply` and the read of
  `YamlFileProvider.Builder.build` hold the lock of `SafeFileWriter.lockFor(file)`, the lock
  `ComfiConfConnection.lockFor(file)` gives while a resource is registered. A write of the file by the plugin
  under that lock is no longer seen in part by a read or an apply. `apply` holds the lock from its read of the
  file to the end of its write and releases it before the activation runs.
- `YamlFileProvider.apply` compares the whole proposal with the values the file held when the provider was built,
  so an apply that changes only live fields still returns `savedRestartRequired` naming every `restartRequired()`
  field whose value differs from its startup value. Before, such an apply returned `applied` while a restart-only
  change of an earlier apply, or of an edit of the file, still waited. Once the file holds the startup value
  again, the result is `applied`. An item of an object list is compared with the startup item of the same item
  key, or of the same index where the list declares no item key. A list that no longer holds an item it held at
  startup is named under its own path, as in `servers`, while that item held a `restartRequired()` value. A
  change of the apply itself inside an object list is still reported as waiting even when its new value equals
  the startup value, as before. A provider built while the file could not be read reports, as before, only the
  restart-only changes of the apply itself.

## [0.2.1] - 2026-11-19

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `ErrorReports.reportUnhandled(String, Throwable)` reports an error the caller does not deal with and rethrows
  or lets escape, and `ErrorReports.install(BiConsumer, BiConsumer)` installs one handler for the reports made
  through `report` and one for those made through `reportUnhandled`. A target installed with
  `install(BiConsumer)` receives both, as before.
- `Metrics.reportUnhandled(String, Throwable)` sends a report to FastStats error tracking with `handled` set to
  `false`.
- `MetricsBuilder.usageChart(boolean)` turns the Taffy usage chart (`taffy_version`) off or on for the plugin
  alone. With `usageChart(false)`, `start()` starts no second bStats instance and no second FastStats context and
  leaves the system properties `taffy.usage.bstats` and `taffy.usage.faststats` alone, while the plugin's own
  metrics start as before. A plugin that does not call it starts the chart, as before.

### Changed
- `MetricsBuilder.start()` starts the Taffy usage chart (`taffy_version`) in one plugin per server for each
  provider. The first plugin to start it names itself in the system property `taffy.usage.bstats` or
  `taffy.usage.faststats`, which every relocated copy of Taffy reads. A plugin that finds another plugin's name
  there starts no second bStats instance or FastStats context for that provider and logs nothing; its own metrics
  start as before. `Metrics.shutdown()` of the plugin named there unsets the property, and no other plugin takes
  the chart over until it next starts its own metrics. A copy of Taffy older than 0.2.1 neither reads nor sets
  the properties, so it still starts the chart.
- The FastStats error trackers of `taffy-metrics` hide more of a message before it leaves the server:
  - the user name and password of a URI of any scheme: `redis://default:hunter2@cache:6379` is sent as
    `redis://[credentials hidden]@cache:6379`;
  - a UUID in upper case or without dashes, as `[uuid hidden]`;
  - the quoted user and host of a MySQL or MariaDB account: `'mc_prod'@'db.example.net'` is sent as
    `[user hidden]@[host hidden]`;
  - the double-quoted name after `user` or `role`, as in PostgreSQL's
    `password authentication failed for user "mc_prod"`, as `user [user hidden]`;
  - the value after `=` or `:` of a key that holds `password`, `passwd`, `pwd`, `secret`, `token`, `api_key`,
    `access_key` or `private_key`, as in `password=[redacted]`. `(using password: YES)` stays as written;
  - an absolute Unix path of one segment and a further slash or more, such as `/etc/hosts`, as `[path hidden]`.
    `/home` alone stays;
  - a host name of two or more labels that ends in `com`, `net`, `org`, `io`, `gg`, `dev`, `me`, `co`, `xyz`,
    `eu`, `de`, `uk`, `us`, `fr`, `nl`, `ca`, `au`, `ru`, `br`, `pl`, `internal`, `local` or `lan`, as
    `[host hidden]`. A class name such as `org.apache.commons.io.FileUtils` and a host name of one label stay.
    Any dotted name whose last label is one of these endings is hidden, a class or file name included, such as
    `com.example.Me` or `messages.de`.
    The id and token of a Discord webhook URL stay hidden beside it:
    `https://discord.com/api/webhooks/<id>/<token>` is sent as
    `https://[host hidden]/api/webhooks/[id hidden]/[token hidden]`.

  Reports whose messages differ only in such a value now merge into one kind.
- `taffy-metrics` counts an IPv4 address, an IPv6 address and a run of digits in an error message as the same
  whatever their value when it decides whether two reports are of one kind. `timeout after 5003ms` and
  `timeout after 71ms`, or `Connection refused: 10.0.0.5:3306` for two addresses, now merge into one error with a
  count, where each took one of the 32 kinds its source may send. The error is sent with the message of the
  first report, and what is hidden in a sent message is unchanged. Messages that differ in a name or other text
  still count as separate kinds.
- The first time a source of error reports holds 32 kinds, `taffy-metrics` logs one warning on the plugin's
  logger that names the source and states that a report of any further kind is not sent until metrics start
  again. The sources past the first 64, which share one limit, log one such warning together. Such reports were
  dropped with no log line.
- A task that throws under `Scheduler` reaches FastStats with `handled` set to `false`, as an exception FastStats
  catches itself does. It was sent with `handled` set to `true`. `Scheduler` now makes the report through
  `ErrorReports.reportUnhandled`, and `MetricsBuilder.start()` installs `Metrics.report` and
  `Metrics.reportUnhandled` into the sink. FastStats does not merge unhandled errors into one with a count, so
  the first unhandled report of a kind from a source is sent and a later one of that kind is not sent until
  metrics start again: a repeating task that throws the same exception on every run is sent once, where it was
  sent as one error with a count. A target a plugin installed with `ErrorReports.install(BiConsumer)` still
  receives each of these reports.

### Fixed
- A jdbc URL with a user name before an `@`, such as `jdbc:mysql://root@db.example.com/game?password=x`, is
  redacted whole in a FastStats error report. The email rule ran before the jdbc rule and left
  `/game?password=x` in the message.
- The email rule of the FastStats error trackers took quadratic time on a long run of letters and overflowed
  the stack on a long dotted name after an `@`, when a report's message was compared with the kinds its source
  already holds. Every rule now runs in linear time.
- The SQL rule of the FastStats error trackers hides a statement only where it starts as one: `SELECT ... FROM`,
  `INSERT INTO` (also `INSERT IGNORE INTO` and `INSERT OR REPLACE INTO`), `UPDATE ... SET`, `DELETE FROM`,
  `REPLACE INTO`, `MERGE INTO`, `CREATE TABLE`, `ALTER TABLE` or `DROP TABLE`, in either letter case. It matched
  the bare words `select`, `insert`, `update` and `delete`, so `Failed to update balance for shop 12: timeout
  after 5003ms` was sent as `Failed to [sql hidden]`; such a message is now sent as written. A sentence that
  holds one of these starts, such as `Please select a kit from the list`, is still hidden from that word on. A
  statement of another shape, such as `SELECT 1`, is no longer hidden.
- `Metrics.shutdown()` waits at most 5 seconds for the final FastStats send. The send runs on a daemon thread
  named `taffy-metrics-final-send`, and when it has not finished after 5 seconds `shutdown()` returns while the
  send goes on, on that thread. It ran on the disabling thread with no limit, so a stalled name lookup or a
  response that never finished delayed the server stop. A chart supplier read during the final send now runs on
  that thread, and an interrupt on the calling thread ends the wait at once and stays set.

## [0.2.0] - 2026-11-19

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `so.alaz.taffy.paper-plugin`: the `compileAgainstPaperFloor` task compiles a plugin's main Java sources against
  paper-api 26.2.build.135-stable, the oldest Paper build Taffy supports, so a call that build lacks fails the
  task. Every other dependency resolves as on `compileClasspath`, and the compiler is the one of the project's
  Java toolchain. No task depends on it: run it by name, or add
  `tasks.check { dependsOn("compileAgainstPaperFloor") }` to make it part of `check`. It compiles only, so a
  method that exists on that build with other behaviour is not caught.
- `Text.withPlaceholderFormatting()` returns a `Text` that shares the integrations and the log of the one it is
  called on and parses colour tags, decoration tags, `<gradient>`, `<rainbow>` and `<reset>` in what a
  PlaceholderAPI placeholder returns. The instance it is called on is left as it is, so a plugin can keep one
  `Text` for chat and one for owner-written item text.
- `DiscordWebhook.post(String, String, PostOptions, Consumer<String>)` posts a plain message with the mention
  types of its options allowed to ping. `DiscordWebhook.PostOptions` is a record of a `Set<DiscordWebhook.Mention>`
  (`EVERYONE` for `@everyone` and `@here`, `ROLES`, `USERS`), built with `PostOptions.allowing(Mention...)` or
  `PostOptions.noMentions()`. The body carries the named types as the `allowed_mentions.parse` list.
- `Sounds.isKnown(String)` tells whether a sound spec names a sound the server knows, so a plugin can warn while
  it loads config. `Sounds.isValid` accepts any key that parses, including a Bukkit enum name such as
  `ENTITY_PLAYER_LEVELUP`, which then plays nothing. `isKnown` looks a `minecraft` key up in the server's sound
  registry and returns `true` for a `null` or blank spec and for a key of another namespace, which only a
  resource pack defines. It needs a running server.
- `DiscordWebhook.close(Duration)` closes the client and waits at most the given time for the posts still in
  flight. The posts unfinished when the wait ends are aborted and fail through their callbacks. An interrupt
  ends the wait the same way and stays set.

### Changed
- Breaking: `Scheduler.close()` completes every future `Scheduler.dispatch` returned whose task has not started,
  as a refused call does: with `RejectedExecutionException` for `ThreadTag.GLOBAL` and `ThreadTag.ASYNC`, and with
  `EntityUnavailableException` for an entity tag, which covers `EntityThread.call` over a `Scheduler`. In 0.1 such
  a future stayed incomplete for good when Paper accepted the task and dropped it as the plugin disabled, so a
  caller waiting on it never returned. A task refused this way never runs, also when Paper still runs what it
  accepted, and a task that has started completes its future with its own result. A dispatch Paper accepts as the
  plugin stops being enabled is refused the same way. Migration: call `Scheduler.close()` in `onDisable`, handle
  the refusal where a dispatched future is awaited, and drop an `orTimeout` that was there only for this case.
- Breaking: `Shutdown.closeQuietly` logs a failed step with its stack trace. The warning reads
  `Failed to <what> during shutdown` and carries the failure as the record's thrown; in 0.1 the failure's
  `toString()` was appended to the message and no trace was logged. Migration: a log filter or test that matched
  the failure text in the message matches `Failed to <what> during shutdown` and reads the failure from
  `LogRecord.getThrown()`.
- Breaking: Taffy compiles against paper-api 26.3 and supports Paper 26.2 build 135 or newer and Folia 26.3 build 8
  or newer; Paper 26.1.2 is no longer supported. The `paper` version of the `alaz` catalog moves from
  `26.1.2.build.66-stable` to `26.3.build.256-stable`, so a plugin that declares `alaz.paper.api` compiles against
  that build, which brings Adventure 5.2.0 in place of 4.26.1. Migration: run the plugin on Paper 26.2 build 135
  or newer or Folia 26.3 build 8 or newer, and recompile it against paper-api 26.3. Adventure 5 makes
  `ClickEvent` generic, so a raw `ClickEvent` compiles with a rawtypes warning until it is written
  `ClickEvent<?>`.
- Breaking: `Text` and `Messages` insert what a PlaceholderAPI placeholder returns as text and no longer parse it
  as MiniMessage. In 0.1 a player-set value such as a nickname could carry
  `<click:run_command:'/pay Mallory 100000'>` or the `<name>` of a supplied value into a message. Now every tag in
  placeholder output is shown as written, on `Text.render`, `Text.send`, `Text.plain` and on the template and the
  prefix of `Messages.render(Text, ...)` and `Messages.renderTemplate`. Section-sign colour and format codes in the
  output still apply, to that output only: a colour a placeholder returns no longer colours the template after it.
  Each run between two `%` signs is given to PlaceholderAPI on its own, so a placeholder inside a tag argument
  that MiniMessage does not parse, such as a click value or an insertion, is no longer filled in and stays as
  written: `<click:run_command:'/msg %player_name%'>` runs `/msg %player_name%`. A placeholder written as an
  unquoted tag argument, such as `<color:%team_color%>`, or inside the URL of `<click:open_url:...>`, no longer
  forms the tag, which is then shown as text. A placeholder inside hover text is still filled in. Tag names that
  start with `taffy_papi_` are reserved for the render. `Text.resolve` is unchanged and returns what
  PlaceholderAPI gave, tags included. Migration: where an expansion returns MiniMessage formatting
  on purpose, such as a rank prefix with a gradient, render through `text.withPlaceholderFormatting()`, a `Text`
  that parses colour tags, decoration tags, `<gradient>`, `<rainbow>` and `<reset>` in placeholder output and
  shows every other tag as written. No option brings back click, hover or any other tag from placeholder output:
  write those in the template, around the placeholder. Where a click value or a tag argument needs a
  placeholder, build that part in code and supply it as a value: resolve the placeholder with `Text.resolve`
  where it comes from PlaceholderAPI, then pass
  `Placeholder.styling("msg", ClickEvent.runCommand("/msg " + name))` with `<msg>` in the template, or
  `Placeholder.styling("team", color)` with `<team>`.
- Breaking: `DiscordWebhook.post(String, String, Consumer<String>)` sends `"allowed_mentions": {"parse": []}`
  with the content, and `WebhookPayload.render` adds the same key to the top level of a template that has no
  `allowed_mentions` key of its own or sets it to null. In 0.1 neither sent the key, so Discord's default applied and player text
  in a post, such as an item renamed to `@everyone free nitro` or a reason holding `<@&roleId>`, pinged the
  guild. Now `@everyone`, `@here`, role mentions and user mentions in such a post are shown as text and ping
  no one. A template with an `allowed_mentions` key keeps its value unchanged, and `DiscordWebhook.postBody`
  still sends its body as given and adds nothing. Migration: where a post is meant to ping, call
  `post(url, content, PostOptions.allowing(Mention.ROLES), onError)` with the mention types to allow, or give
  the payload template its own `allowed_mentions` object, such as `allowed_mentions: {parse: [roles]}` in the
  server owner's config.
- Breaking: `DiscordWebhook.close()` waits at most 5 seconds for the posts still in flight and then aborts them,
  so each fails through its callback. In 0.1 it had no upper bound, and a Discord that sent its response headers
  and then stalled kept the disabling thread blocked. Migration: call `close(Duration)` with a longer wait where
  a plugin must give its last posts more than 5 seconds.
- Breaking: `WebhookPayload.render` throws `IllegalArgumentException` for a template value that is not a
  `String`, `Number`, `Boolean`, `null`, `Map`, `List` or Bukkit `ConfigurationSection`. The message names the
  value by its key path, such as `embeds[0].timestamp`. It throws the same way for a `Double` or `Float` that is
  `NaN` or infinite. In 0.1 such a value was handed to Gson, which wrote whatever its reflection found.
  Migration: turn the value into a `String`, `Number` or `Boolean` before the
  template is given to `render`.
- Breaking: `SkullBuilder.fromName` and `SkullBuilder.fromUuid` remember a failed lookup for 2 minutes and reuse a
  resolved texture for 60 minutes, and one `SkullBuilder` caches at most 1000 names and uuids. In 0.1 a failed
  lookup was not cached, so the next call for the same name or uuid looked it up again at once, and a page of
  unresolvable names blocked one lookup per head on every menu open. Now a lookup that found no texture or threw
  is kept for 2 minutes from the moment it completed: calls in that time start no lookup and complete with a
  blank head, or exceptionally when the lookup threw an `Error`. A lookup the executor rejected is still not
  remembered. In 0.1 a resolved texture stayed for the life of the instance, so a skin change was never seen
  and the cache grew with every player shown. Now it is looked up again once 60 minutes have passed since its
  lookup completed, and past 1000 entries the one whose lookup started longest ago is dropped. Migration: a
  plugin that called `fromName` or `fromUuid` again right after a blank head to retry gets the same blank head
  for 2 minutes; show the blank head and ask again later, or use a new `SkullBuilder`, which starts with an
  empty cache.
- Breaking: `so.alaz.taffy.repositories` asks each repository it declares, Maven Central apart, only for the
  groups the `alaz` catalog takes from it, declares `alazso` and `alazsoSnapshots` through `exclusiveContent`,
  and moves `jitpack` to the end of the list. In 0.1 only the two `repo.alaz.so` repositories and the three
  hologram repositories had a filter, and `jitpack` stood ahead of eight other hosts, so a module an earlier
  repository lacked was looked up on JitPack and on every other host: `net.thenextlvl:holograms` came from the
  `faststats` repository. Now `papermc` serves `io.papermc` and its subgroups, `com.mojang` and `net.md-5`;
  `placeholderapi` serves `me.clip`; `faststats` serves `dev.faststats.metrics`; `vaultunlocked` serves
  `net.milkbowl.vault`; `playerpoints` serves `org.black_ixx`; `oraxen` serves `io.th0rgal` and
  `md.thomas.hopper`; `nexo` serves `com.nexomc`, `com.tcoded`, `me.gabytm.util` and `team.unnamed`; `enginehub`
  serves `com.sk89q`, `org.enginehub` and their subgroups; and `jitpack` serves `com.github.MilkBowl`,
  `com.github.Arcaniax-Development`, `com.github.decentsoftware-eu`, `com.github.HologramLib` and
  `com.github.maximjsx.EntityLib`. `fancyinnovations`, `thenextlvl` and `packetevents` keep their filters.
  `so.alaz` and its subgroups resolve from `alazso` and `alazsoSnapshots` and from no other repository, Maven
  Central and a repository the build adds included. Every entry of the `alaz` catalog resolves to the same
  modules as before. Migration: a build that took a module of another group from one of these repositories
  declares that repository itself in `dependencyResolutionManagement.repositories` of `settings.gradle.kts`. A
  build that added `mavenLocal()` there to use a locally published Taffy sets the URL of `alazsoSnapshots` to the
  local repository instead: `repositories.named<MavenArtifactRepository>("alazsoSnapshots") { url =
  uri(File(System.getProperty("user.home"), ".m2/repository")) }`.
- `taffy-gui`: the package-private `CancelLink.link` returns `ClickEvent<?>`, the generic type Adventure 5 gives
  `ClickEvent`. Its erasure is the same, and no public signature changes.

### Fixed
- `WebhookPayload.render` reads a Bukkit `ConfigurationSection` in the template as the map of its own keys, at
  any depth. A payload read with `getConfigurationSection("payload").getValues(false)` holds a section for each
  nested object, such as `thumbnail` or `author`, and in 0.1 such a section was handed to Gson as an arbitrary
  object, so the body did not carry it as the config wrote it.
- `LegacyText.toMiniMessage`, `LegacyText.sectionToMiniMessage` and every `Text` and `Messages` render: a legacy
  colour code ends the format codes before it, as it does in legacy text. `&c&lADMIN &7Steve` through
  `toMiniMessage`, or the same text written with section signs in a `Text` or `Messages` render, becomes
  `<red><bold>ADMIN <gray><!bold>Steve`, so `Steve` is gray and not bold; before, `<bold>` stayed open and the
  rest of the line was bold. The colour tag is followed by the negation of each decoration that a format code
  earlier in the same string opened. A decoration opened by a tag, or by a format code in a separately converted
  string such as a `Placeholder.parsed` value, stays open after a colour code, unless a format code earlier in
  the same string opened the same decoration.
- `Messages.load` and `Messages.reload` warn when two files in `lang` read as one locale, such as `pt-BR.yml` and
  `pt_br.yml`. In 0.1 one of them replaced the other without a warning. The warning names both files and the one
  used, which is the one the folder listing gives last.

## [0.1.2] - 2026-11-19

Built against paper-api 26.1.2.build.66-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `taffy-hooks`: `HologramProvider` and `HologramPlugin` place text holograms through DecentHolograms 2.10 or
  newer, FancyHolograms 2, Holograms by TheNextLvl or HologramLib. A hologram is named by an id of 1 to 48 of
  `a-z`, `0-9`, `_` and `-`, stored under a prefix made from the owner's name, and never saved by the hologram
  plugin. The location given to `create` and `move` is the bottom centre of the text, with the lines stacked
  upward from it. Lines are MiniMessage (converted to `&` colour codes for DecentHolograms). Taffy keeps
  DecentHolograms and FancyHolograms from reading a `&` in a line as a colour code, and DecentHolograms from
  reading a `#` as a hex colour. A PlaceholderAPI placeholder in a line is filled in for each viewer by every
  supported hologram plugin. `HologramProvider.literal` writes text a player supplied so that a line shows it as
  written, with no MiniMessage tag, colour code or placeholder in it taking effect. Every call but `create`
  reaches only a hologram the provider placed, and `create` refuses a name another hologram holds. A hologram is
  removed when its world unloads, and the provider removes every hologram it placed when its owner's
  `PluginDisableEvent` fires, before `onDisable`. A hologram the hologram plugin drops, such as on its reload,
  makes `update` answer `false`. `close()` removes every hologram the provider placed and stops the provider. The
  owner calls it in `onDisable`, after `PlaceholderRegistration.unregister` and `Menus.shutdown` and before
  `Scheduler.close`, and before detecting again. `create`, `update`, `move`, `remove`, `exists`, `ids` and
  `removeAll` answer `false` or nothing found while the provider is closed or the hologram plugin is disabled or
  fails, and failures are reported under `hook.<plugin key>`, such as `hook.fancyholograms`.
  `HologramProvider.plugin()` is `null` for a provider bound to no hologram plugin, and a later version may add
  `HologramPlugin` constants.
- Catalog entries `alaz.decentholograms`, `alaz.fancyholograms`, `alaz.holograms`, `alaz.hologramlib` and
  `alaz.packetevents.api`. `so.alaz.taffy.repositories` adds the FancyInnovations repository for `de.oliver` only,
  the TheNextLvl repository for `net.thenextlvl` only, and CodeMC for `com.github.retrooper` only.

## [0.1.1] - 2026-11-19

Built against paper-api 26.1.2.build.66-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Changed
- Releases are built and tested in a job that holds no credentials, and only the publish job holds
  them.

### Fixed
- `Items.giveOrDrop` leaves the given `ItemStack` unchanged: it adds a copy, so the same stack can be given
  to several players without being reduced to what was left over.
- `Text` handles a `LinkageError`, such as `NoSuchMethodError`, thrown by a MiniPlaceholders tag while the
  text is parsed: the text renders without MiniPlaceholders and one warning is logged under
  `miniplaceholders`, instead of the error reaching the caller of `render`, `plain` or `send`.
- A manual Release run refuses a `-SNAPSHOT` version, except as a dry run, and a tag that does not exist
  or is not at the commit being built, so it no longer publishes a snapshot or creates a tag.
- A release publish that failed part way can be run again and uploads only the modules that are
  missing.

## [0.1.0] - 2026-11-18

### Added
- `taffy-platform` module:
  - Scheduling: `Scheduler` (Paper and Folia, created with `new Scheduler(plugin, errors)`, every method reports
    refusal while the plugin is disabling), `Dispatcher`, `ThreadTag`, `EntityThread`,
    `EntityUnavailableException`.
  - Shutdown: `TaskGate`, `PendingSettlements`, `Shutdown.closeQuietly`, `ErrorReports`.
  - Text: `Text` (MiniMessage with PlaceholderAPI and MiniPlaceholders when present), `Messages` (language files,
    per-player locale, reload, a YAML list read as the lines of one message, `has` and `lines`), `LegacyText`.
  - Items: `ItemBuilder` with the `serialized:` item reference, `SkullBuilder`, `Items.giveOrDrop`,
    `InventoryFit`, `PdcKey`, `Colors`, `Sounds`.
  - Time: `Durations`, `Expiry`, `CooldownManager`.
  - Logging: `ThrowLog` (rate-limited warnings), `Classes.present`.
  - Discord: `DiscordWebhook`, `WebhookPayload`.
  - `PublicApiRulesTest` fails the build when a public signature names a type outside the JDK, Paper, Adventure
    and `org.jetbrains.annotations`, when a static field holds a plugin, logger, executor or thread, or when the
    jar holds a resource file.
- `Scheduler` throws `NullPointerException` for a `null` plugin or `ErrorReports`, and for a `null` task or
  `retired` callback passed to `entity`, before anything is scheduled. A task Paper runs for `global`, `async`
  (also through `asyncExecutor` and `asyncOrRun`), `delayedAsync`, `repeatingAsync` or `entity`, or a `retired`
  callback Paper runs, that throws anything (an undeclared checked exception included) is reported to the
  `ErrorReports` once under the source `scheduler.global`, `scheduler.async` or `scheduler.entity`, then rethrown
  unchanged, so Paper logs it as well. Paper's region, async and entity schedulers fire no `ServerExceptionEvent`,
  so such a throw reaches FastStats error tracking only through this report. A task or `retired` callback run on
  the calling thread is not reported and its throw reaches the caller; `dispatch` reports nothing, and its future
  carries what the task threw.
- `ErrorReports` holds the reports made before its first install, the first per source and at most 16, and
  delivers them on that install.
- `Text` logs a PlaceholderAPI or MiniPlaceholders pass that throws, a `LinkageError` such as `NoSuchMethodError`
  included, through `ThrowLog` under the sources `placeholderapi` and `miniplaceholders` (one stack trace, then at
  most one line per source per minute), and renders the text without that pass.
- A `Placeholder.parsed` value that holds a section-sign code renders the code as its tag in `Text` and
  `Messages`, without throwing `ParsingException`.
- `Durations`, `Colors` and `Expiry` expose a public `EXPECTED` text and fail with
  `invalid <thing> '<value>': expected <EXPECTED>`.
- `DiscordWebhook` calls its failure callback at most once per post.
- `taffy-config` module: `ConfigLoader` (writes and upgrades `config.yml`), `StrictYaml`, `ConfigReader` (typed
  reading that collects every problem, with range, choice, decimal, long and `parsed` readers), `ConfigHolder`
  (reload guard), `RestartKeys`, `YamlFolder` (folder of files) and `ConfigLoadException`.
- `StrictYaml.read` and `YamlFileProvider` refuse a config file larger than 16 MiB with a `ConfigLoadException`
  ("<file> is larger than 16 MiB").
- Parse errors from `StrictYaml`, `ConfigLoader` and `YamlFileProvider` name the line and column where parsing
  failed ("<file> is not valid YAML: <problem> at line N, column M"), and the `ConfigLoadException` carries no
  cause.
- `ConfigReader.text`, `strings`, `requiredText` and `strictText` read an unquoted date such as `2026-12-31` as
  the text `2026-12-31`. An unquoted date with a time warns and uses the fallback (`text`), is skipped (`strings`)
  or is an error (`requiredText`, `strictText`), the same as with `parsed`. `ConfigReader.quoted` adds its error
  for any unquoted date, and its error reads "... it reads as a number, a date or true/false."
- `ConfigReader.describe`, and so every `ConfigReader` warning and error, writes a list longer than 100 characters
  as "a list of <n> entries" ("a list of 1 entry" for one entry), a group of keys inside a list longer than 100
  characters as "a group of <n> keys" ("a group of 1 key"), and cuts a text or any other value longer than 100
  characters to its first 100 followed by "...".
- `ConfigLoader` saves through a temporary file named `config.yml.tmp` (for a symbolic link, the linked file's
  name followed by `.tmp`, in that file's folder), and the next `load()` removes one left over from a save that
  was cut off.
- `taffy-storage` module: `Storage` (SQLite, MySQL, MariaDB and PostgreSQL over HikariCP, transactions on its own
  database threads, opt-in rollback retry), `StorageSettings`, `Dialect`, `Migration` with an internal migrator,
  `Tx`, `TxWork`, `Params`, `Row`, `MoneyColumn`, and the exceptions `StorageException`, `CommitUnknownException`
  and `MoneyOutOfRangeException`. HikariCP and the drivers are compile-only and appear in no published dependency.
- `Storage.start(Plugin, ErrorReports, StorageSettings, List<Migration>, Clock)` and
  `Storage.over(Plugin, ErrorReports, Dialect, DataSource, String prefix, Clock)` take the name, version, data
  folder, logger and migration scripts from the `Plugin`.
- `taffy-storage`: `Baseline` and
  `Storage.start(Plugin, ErrorReports, StorageSettings, List<Migration>, Baseline, Clock)` take over the version
  table of a plugin's own migration runner on a database with no version table of storage's. The takeover records
  the migrations up to the old table's highest version as applied, with the checksum of their current scripts,
  renames the old table to `<table>_baseline` (or the first free `<table>_baselineN` when that name is taken,
  never dropping it), and then runs the later migrations. On SQLite the old table is found whatever the case its
  name was declared in. An interrupted takeover leaves either no version table of storage's, so the next start
  takes the database over again, or a complete one. Once storage's own version table exists the baseline is
  ignored.
- On SQLite storage from `Storage.start`, `Storage` runs writes on one thread of their own, `<name>-db-writer`,
  one at a time in the order they were submitted; reads keep running on the other database threads. The futures of
  `Storage.async`, `asyncRetrying` and `start` complete on a read thread (`<name>-db-N`), so a stage chained onto
  a write without an executor never holds up the writer thread. While `close` drains they complete on the thread
  that ran the write.
- `Storage.close` waits with no limit for a drain of `Long.MAX_VALUE` nanoseconds or more, such as
  `ChronoUnit.FOREVER.getDuration()`, and waits no time for a negative drain.
- `Storage` documents that work, a task from `executor()` and a stage on a database thread that wait for other
  database work need another database thread free; with `asyncThreads` 1 such a wait never ends.
- The migration lock is keyed by the database and the version table (on PostgreSQL by the schema and the version
  table), so starts on different databases or schemas of one server do not wait for each other. A start whose
  connection has no current database or schema fails with a `StorageException`.
- A `<prefix>schema_version` table that storage did not create stops the start with a `StorageException` before
  any backup or migration, and is left unchanged. Only a missing column counts as another layout; a version table
  that cannot be read for another reason, such as a denied read, a damaged file or a lost connection, fails the
  start with the database's own error.
- `taffy-hooks` module: `CustomItems`, `CustomItemPlugin` and `CustomItemRef` for ItemsAdder, Nexo, Oraxen and
  HeadDatabase; `EconomyProvider`, `EconomyResult` and `EconomyWatcher` for a Vault economy; `PermissionProvider`
  for LuckPerms; `RegionProvider` for WorldGuard; `PlaceholderExpansionBuilder` and `PlaceholderRegistration` for
  PlaceholderAPI and MiniPlaceholders; and `HookState`, `HookStatus` and `Detected` for what detection found.
  Every entry point takes the owner plugin, the `ErrorReports` and the owner's switch, and every integration gives
  a `HookStatus` with a reason for the owner where there is something to do. The integration APIs are compile-only
  and appear in no published dependency.
- `PlaceholderExpansionBuilder.player`, `offline` and `global` accept only names made of the lower case letters a
  to z, the digits, `_` and `-` (`[a-z0-9_-]+`), so every player and global placeholder resolves in both
  PlaceholderAPI and MiniPlaceholders. Any other name throws `IllegalArgumentException`.
- `new CustomItemRef(hint, id)` refuses a blank hint with `IllegalArgumentException`; `null` means no hint.
  `CustomItemRef.parse` maps a blank hint to `null`.
- `taffy-gui` module: menu layouts (`MenuLayout`, `ItemLayout`, `PagerLayout`, `MenuKind`), drawing (`Icon`,
  `MenuText`, `Frame`, `Pager`), the runtime (`Menus`, `Menu`, `MenuSession`, `MenuSettings`, `Route`,
  `ViewMemory`, `Cue`), buttons (`ClickHandler`, `Click`, `ClickGuard`) with click safety from `Menus.listener()`,
  and text prompts in chat or an anvil (`Prompts`, `PromptRequest`, `PromptMode`, `Answer`, `BedrockPlayers`) with
  their listener from `Prompts.listener()`.
- `ItemLayout.of(id, slots, material)` and the withers `action`, `permission`, `name` and `lore`, each returning a
  copy with only that value replaced.
- `Pager.Page` has four components, `index`, `pages`, `from` and `to`, and its constructor refuses numbers that do
  not describe a page with `IllegalArgumentException`. `previous()` and `next()` are methods computed from them.
- `taffy-metrics` module: `MetricChart` (string, number, bool, string list and counter charts for bStats and
  FastStats from one definition), `MetricsBuilder` and `Metrics` (diagnostics readers, `report`, `shutdown`).
  Handled errors reported to `ErrorReports` reach FastStats error tracking under their source. A `taffy_version`
  usage chart reports to Taffy's own bStats and FastStats entries, beside each provider the plugin itself starts.
  bStats and FastStats are runtime dependencies, relocated by the plugin.
- `MetricsBuilder.start()` gives FastStats a context-unaware global error tracker and leaves the JVM default
  uncaught exception handler alone, and `Metrics.shutdown()` restores no handler. An uncaught exception on a
  thread the plugin starts itself is printed by the JVM and not taken by FastStats, and shutting down several
  plugins that shade Taffy, in any order, leaves no disabled plugin's handler installed. FastStats error tracking
  receives the `ErrorReports` reports and the plugin's own `ServerExceptionEvent` exceptions; a plugin that wants
  an error from its own thread tracked catches it and reports it through `ErrorReports`.
- A jar without `META-INF/faststats.properties` is refused at `MetricsBuilder.start()` with one warning, before
  FastStats writes `plugins/faststats/config.properties`, so the server keeps its first run.
- `taffy-comficonf` module: `ComfiConfConnection` (the bridge guard, status, `notifyChanged`, `lockFor`, `close`),
  `ComfiConfState` and `ComfiConfStatus`, `ComfiConfLink`, `ComfiConfRegistration` and `YamlFileProvider` (one
  YAML file read, validated, written with the revision check and activated through the plugin's own reload). It
  builds on `taffy-config`; `comficonf-api` stays compile-only.
- `ComfiConfConnection.status()` gives `NOT_REGISTERED`, with a reason, while the bridge is enabled but does not
  hold the plugin's resource, and `ComfiConfStatus.reason()` is documented for `NOT_REGISTERED`.
- A plugin jar that bundles its own copy of `com.comficonf.api` reads `NOT_VISIBLE` with a reason naming the copy;
  nothing is registered or reported.
- The `taffy-comficonf` package description documents connecting again (close, then connect with the same
  supplier) and builds the `YamlFileProvider` once behind an API-free `Supplier<ComfiConfLink>`.
- `ApiRules` allows `com.comficonf.api` types in the public signatures of `so.alaz.taffy.comficonf` only.
- `taffy-test` module: `ApiRules`, `CapturedLog`, `FakeItems`, `FakeFolders`, `FaultyDataSource`, `JvmDefaults`,
  `RaceRunner` and `SimulatedClasspath`. `SimulatedClasspath.hiding` copies a module's own classes onto a loader
  that hides chosen packages, with their code source kept so coverage counts them.
- `CapturedLog`'s Javadoc states that the wording of Taffy's own log lines is not part of its API and can change
  in any release.
- Catalog entries for Taffy's own modules: `alaz.taffy.platform`, `alaz.taffy.test` and `alaz.taffy.config`,
  `alaz.taffy.storage`, `alaz.taffy.hooks`, `alaz.taffy.gui`, `alaz.taffy.metrics` and `alaz.taffy.comficonf`
  resolve at the catalog's version. The module list is the Gradle property `taffy.modules`.
- `so.alaz.taffy.java` passes the Mockito jar on each test task's classpath as a Java agent, runs tests under the
  `tr-TR` locale and the `Pacific/Auckland` zone, and runs javadoc with `-Xdoclint:all,-missing` and `--no-fonts`.
- The root `check` fails when a module listed in `taffy.modules` has no `PublicApiRulesTest`.
- `so.alaz.taffy.settings` settings plugin: applies `so.alaz.taffy.repositories` and exposes the shared version
  catalog as `alaz`.
- `so.alaz.taffy.repositories` settings plugin: applies the Java toolchain resolver and declares every repository
  the so.alaz plugins resolve from.
- `so.alaz.taffy.paper-plugin` convention plugin: one relocated shadow jar, `paper-plugin.yml` version expansion,
  the coverage gate, and the `paperPlugin` extension, whose `runtimeLibraries` list (filled with
  `runtimeLibrary(<catalog entry>)`) generates the `RuntimeLibraries` class in the package named by the project
  group when it is not empty.
- `so.alaz.taffy.paper-api` convention plugin: sources and javadoc jars and the `library` publication to
  repo.alaz.so under the project group.
- `so.alaz.taffy.library` convention plugin: `so.alaz.taffy.paper-api` with tests and the coverage gate, for a
  published library module, with `coverage.classMinimum` set to 0.50.
- `so.alaz.taffy.coverage` and `so.alaz.taffy.java` convention plugins: the coverage gate (`coverage.minimum`, the
  per-class floor `coverage.classMinimum` and `coverage.exclusions`) and the Java 25 toolchain, compiler flags and
  JUnit Platform on their own.
- `so.alaz.taffy:taffy-catalog` version catalog with the dependency versions of the so.alaz stack.
