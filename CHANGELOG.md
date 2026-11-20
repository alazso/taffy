# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

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
  A plugin's own `switch` over the sealed `Answer` needs a case for `Later` to compile.
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

### Changed
- Breaking: `Menus.register` and `Menus.reload` throw an `IllegalArgumentException` for a material that is not an
  item a slot can show, air included: the material of an `ItemLayout` (shown or not), the `filler` of a
  `MenuLayout`, the `emptyMaterial` of a `PagerLayout`, and, in `register`, the `filler` and `loadingItem` of the
  `MenuSettings`. The message names the menu and the item, such as "item buy of the shop menu has the material
  WALL_TORCH, which is not an item". Before, such a layout registered and every open of the menu failed with a
  stack trace. A refused `reload` leaves the layouts as they were. Give each of these an item material, and
  catch the exception where the plugin reads layouts or settings from a file, to show the owner the message.

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
  taken from it was deleted only when a click met it after a later enable.

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
