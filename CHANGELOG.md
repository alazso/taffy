# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [0.4.14] - 2026-11-24

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.33.0 and bStats 3.2.1.

### Added
- `taffy-config`: `ConfigReader.item(String path)` and `item(String path, ItemKeys keys)` read an item definition
  out of a group of keys into the new class `ItemSpec`: the material, an item reference kept as text
  (`serialized:<base64>` or `<plugin>:<id>`), the name and the lore as MiniMessage text, the glow, the item model,
  the custom model data as floats, the enchantment levels and the armour trim. The call needs no running server:
  the material is resolved through `Material.getMaterial`, in any letter case and with or without `minecraft:`,
  and the enchantment and trim keys stay text. No value throws. An absent group gives `null` with nothing
  reported. A value that is not a group of keys, a material that does not exist and a reference that is not a
  single value (a list, a group of keys or an unquoted date with a time) are errors and give `null`. A bad
  optional part (a level that is not a whole number of 1 or more, an item model that is not a key, a custom model
  data entry that is not a number, a trim without both keys) is a warning that leaves out only that part. A key
  of the group the reader was not told to read stays in `unreadKeys()`.
- `ItemKeys` names the keys `item` reads, so a plugin keeps the keys its files already have and no owner file
  changes. `ItemKeys.defaults()` reads `material`, `reference`, `name`, `lore`, `glow`, `item-model`,
  `custom-model-data`, `enchantments` and `trim`; each method returns a copy with one key renamed, and `null`
  turns a key off. `referenceParts(providerKey, idKey)` reads a reference written as two keys and joins them as
  `<provider>:<id>`, and `textBesideSection(true)` reads the name and the lore beside the group instead of
  inside it.
- `ItemSpec.overMaterial(Material fallback, Function<String, Component> renderer, Logger, Consumer<ConfigProblem>)`
  and `overStack(ItemStack base, ...)` give an `ItemBuilder` with the spec applied, over the material of the file
  or over a stack the plugin built, such as the item a reference stands for. The plugin's `renderer` turns the
  name and the lore into components. Enchantment and trim keys are looked up in the registries of the server
  here: one the server does not have, and a material that is not an item, each reach the consumer as a
  `ConfigProblem` warning with the file and the path of the item, and the item is built without it. Both need a
  running server and may be called on any thread, on Folia too.
- `taffy-hooks`: `ConditionRegistry` builds the conditions an owner lists in a config file and tests them for a
  player. Each entry of the list is a group of keys with a `type`, the keys of that type and an optional `deny`
  text. `ConditionRegistry.create(Logger, ErrorReports, Text, Messages, Supplier<RegionProvider>)` holds four
  types: `permission` (key `permission`), `world` (list `worlds`), `papi` (keys `placeholder`, `operator` and
  `value`, with the operators `equals`, `!=`, `contains`, `>`, `>=`, `<` and `<=`, numbers read as
  `Double.parseDouble` reads them, and a `value` YAML reads as a boolean or a number, such as `yes` or `010`,
  compared as `true` or `8` unless it is in quotes) and `region` (list `regions`, through `RegionProvider`). A
  plugin adds its own with `register(String name, ConditionType type)`. There is no economy type: a plugin
  registers its own and runs it on the thread `EconomyProvider.thread` names.
- `ConditionRegistry.build(Object value, String path, ConditionProblems problems)` turns the list into one
  `Condition` when the file loads and throws for no value in it. A `type` that is not registered, a missing or
  unusable key, a `deny` that is not a text and a type that throws are each passed to `problems` as an error with
  its path, such as `conditions[1].type`, and that entry denies every player at its place in the list, with its
  `deny` text when that is usable and with `condition.requirement-not-met` otherwise. A value that is not a list
  gives a condition that denies every player. A plugin that only logs the errors therefore keeps the gate closed,
  and one that refuses the file never tests such a condition. `ConditionProblems` takes the arguments of
  `ConfigReader.error(String, String, String)`, so a plugin passes `reader::error`.
- `Condition.test(Player)` tests the entries in file order on the player's thread, stops at the first one that
  denies and gives a `ConditionResult` with the reason as a component. The reason is the entry's `deny` text
  rendered for the player, or the message of the type's key from the plugin's lang files: `condition.permission`,
  `condition.world`, `condition.region`, `condition.region-unavailable` while no region provider is available,
  and `condition.requirement-not-met` for `papi`. While PlaceholderAPI is not installed a `papi` placeholder is
  compared as written. A condition that throws denies, and is logged at most once a minute and reported under
  `condition.<type>`.
- `taffy-platform`: a lang entry can name where `Messages.send(Text, CommandSender, String key, TagResolver...)`
  shows it, so an owner moves a message from chat to the action bar or a title in the lang file and the plugin
  changes no code. The entry is written as a section named `delivery` under the key of the message, with any of
  `chat`, `actionbar`, `title`, `subtitle`, `times` and `sound`:
  `pay.sent: { delivery: { actionbar: "<green>Sent <amount>", sound: "entity.experience_orb.pickup 1 1.4" } }`.
  The four texts are MiniMessage templates rendered as any message is, `times` is the fade in, stay and fade out
  of the title as three whole numbers of ticks (`"10 70 20"`), and `sound` is in the form `Sounds` reads. A player
  gets each entry the section has, and only the chat message gets the prefix of `withChatPrefix`. A sender that is
  not a player gets one chat message: `chat`, or without it `actionbar`, or without that `title` and `subtitle`.
  A message written as a plain value is sent as chat, as before, and the first file of the lookup order that has
  the message in either form decides.
- Only `send` reads a delivery section. `render`, `lines`, `has`, `template`, `sendActionBar` and `showTitle`
  read every file as 0.4.13 does: the entries stay the keys `<key>.delivery.chat` and so on, and the key itself
  is taken from the next file that has it as a value. A `delivery` section with any other entry, with an entry
  that has no value or with a section inside it (such as a menu item named `delivery` with a `name`), a key named
  `delivery` at the top of a file and a `delivery` key that holds a value are read as before.
- The load and a reload log nothing about a delivery section, so a lang file of 0.4.13 gets no new warning, and
  the warning about the keys a file lacks still names each bundled entry the file does not have, such as
  `mail.delivery.subtitle` for a title a plugin shows with `showTitle(text, player, "mail.delivery")`. The
  warnings about a delivery section are logged by the first `send` that reads it after the load or a reload,
  each with the file and the key: a `times` that is not three whole numbers of ticks (the title gets the default
  times), a `sound` that is a list or whose key is not a valid sound key (no sound is played), and a `delivery`
  section with another entry or an entry with no value in a file the send passes (the message is taken from the
  next file). No exception is thrown at a send for a bad entry.
- `Messages.addMissingKeys` adds each entry of a bundled delivery section that the file lacks, as it adds any
  key, and returns it as `pay.sent.delivery.actionbar`. The entries are written as one nested section under the
  dotted key (`pay.sent.delivery:` with its entries indented below) when the bundled section has a `chat`,
  `actionbar`, `times` or `sound` entry and the file writes no key at or under that section, and as dotted keys
  as before otherwise, so a section a file has in part is completed key by key. Two shapes add nothing and are
  left out of the warning about the keys a file lacks: the entries of a bundled delivery section whose message
  the file has as a plain value, and a message the bundled file has as a value and the file has as a delivery
  section. On 0.4.13 a file of either shape got that warning at each load and made `addMissingKeys` change
  nothing, with the warning that the keys cannot be added at its end. An owner who wants an entry gone leaves it
  empty (`sound: ""`) instead of removing the line.

## [0.4.13] - 2026-11-24

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.33.0 and bStats 3.2.1.

### Added
- `taffy-hooks`: `EconomyProvider` has seven more methods, all default methods, so a plugin's or a test's own
  `EconomyProvider` compiles unchanged and answers that it cannot: `EconomyResult setBalance(UUID, BigDecimal)`
  gives `UNSUPPORTED`, `String currencyNameSingular()`, `currencyNamePlural()`, `currencySymbol()` and
  `format(BigDecimal)` give `null`, and `EconomyReceipt withdrawReceipt(UUID, BigDecimal, BooleanSupplier)` and
  `depositReceipt(UUID, BigDecimal)` give the result of `withdraw` and `deposit` with no transaction id. No
  `EconomyResult.Status` constant is added. The one implementation that stops compiling is one that already
  declares a method of one of these names and parameters with another return type, such as
  `void setBalance(UUID, BigDecimal)` or `Component format(BigDecimal)`: that method is renamed or takes the
  return type above. A plugin jar built against 0.4.12 keeps running.
- `setBalance` sets a balance to an amount of zero or more, rounded half-even to `scale()`, on the thread
  `thread(player)` names. `OK` tells that the economy set it, `UNSUPPORTED` that this economy cannot set a
  balance, and `FAILED` that it can and refused; in the last two the balance is unchanged. The Vault provider
  gives `UNSUPPORTED` for every amount, once the thread is checked, since the Vault 1.7.1 API has no call that
  sets a balance. The PlayerPoints provider sets whole points through `PlayerPointsAPI.set(UUID, int)`, in turn
  with its withdraws for that player: an amount with a fraction of a point, a negative one or one above
  `Integer.MAX_VALUE` throws `IllegalArgumentException`, and a set that PlayerPoints refuses, or one made while
  PlayerPoints is disabled, gives `FAILED`.
- The Vault provider answers `currencyNameSingular()` and `currencyNamePlural()` from the economy, and
  `format(BigDecimal)` from `Economy.format(double)` with the amount rounded half-even to `scale()` and sent as
  the nearest `double`; an amount beyond the range of a `double` throws `IllegalArgumentException`. The
  PlayerPoints provider answers the two names from `PlayerPointsAPI.getCurrencyNameSingular()` and
  `getCurrencyNamePlural()` and formats no amount. A name or text that is `null` or blank reads as `null`, and
  so does a call that throws an exception or a `LinkageError`, which is logged with a warning. Neither API has a
  currency symbol, so `currencySymbol()` gives `null` for both. These four calls name no player and are not
  checked against a thread.
- A name, a symbol and a formatted amount are the text the economy gave, unchanged: Taffy parses nothing and takes
  nothing out. The text can hold legacy colour codes, which start with a section sign, or the tags of the economy
  plugin's own format, which are not MiniMessage, so a plugin shows it as literal text, with `Component.text`.
- `EconomyReceipt` is a record of an `EconomyResult` and a `transactionId`, which is `null` when the economy
  gave none or no money moved, and `EconomyReceipt.of(EconomyResult)` gives one with no id. Neither Vault nor
  PlayerPoints gives a transaction an id, so every receipt of both providers has none.
- A PlayerPoints that lacks `set(UUID, int)` or the two name methods is still detected: `setBalance` then gives
  `UNSUPPORTED` and the names `null`.
- `taffy-hooks`: `EconomyProvider.vaultUnlocked(Plugin owner, ErrorReports errors, boolean switchedOn, boolean
  folia, String currency, Integer scale)` gives a `Detected<EconomyProvider>` for one currency of the economy
  registered with the VaultUnlocked economy API (`net.milkbowl.vault2.economy.Economy`), built against
  VaultUnlockedAPI 2.20. `EconomyProvider.vault` is unchanged: it keeps reading the Vault API
  (`net.milkbowl.vault.economy.Economy`) and never the VaultUnlocked one. The plugin lists `Vault`, the name of
  the VaultUnlocked plugin, in `paper-plugin.yml`. The status's plugin is `VaultUnlocked`, a failed lookup is
  reported under `hook.vaultunlocked`, and a currency the economy does not have gives `NOT_REGISTERED` with a
  reason that names the currencies it has. `HookStatus.describe()` has no text of its own for that case, so
  `line()` reads `VaultUnlocked 2.20.3 has nothing registered with it. TNE has no currency named tokens. It has
  dollars, gems.` The lookup asks the economy for the currency and reads no balance. A VaultUnlocked whose
  `Economy` lacks `set(String, UUID, String, String, BigDecimal)` or `fractionalDigits(String, String)`, both
  added in 2025, is still read: `setBalance` then gives `UNSUPPORTED`, and the decimal places come from
  `fractionalDigits(String)`.
- The VaultUnlocked provider sends amounts as `BigDecimal`, rounded half-even to `scale()`, so no amount is
  refused for its size. `scale` is the currency's `fractionalDigits(String, String)` capped at 4, 2 when it
  reports none, or the `scale` argument. Every call names the owner's plugin name, the currency and the name of
  the server's first world. `thread(player)` is `ThreadTag.GLOBAL`, and a call on another thread throws
  `IllegalStateException`. A withdraw checks the balance itself and can give `INSUFFICIENT` and `OVERDRAWN`. A
  withdraw, a deposit and a balance set give `FAILED` when the economy no longer has the currency, while no world
  is loaded, and while the plugin that registered the economy is disabled. `setBalance` calls `Economy.set`,
  which withdraws or deposits the difference unless the economy overrides it, and reads the balance again: a
  balance that is not the amount after a set the economy answered as a success throws `IllegalStateException`.
  The two currency names are those of the default currency and `null` for every other currency,
  `currencySymbol()` is `null`, `format` calls `Economy.format(String, BigDecimal, String)`, and a receipt has no
  transaction id.
- `EconomyWatcher.vaultUnlocked(Runnable resolve, BooleanSupplier stopping)` gives a watcher that runs `resolve`
  when an economy registers with or leaves the VaultUnlocked economy API. A watcher made by the `EconomyWatcher`
  constructor hears only the Vault API economy, as before, so it does not cover a late VaultUnlocked registration.
- `EconomyProvider.coinsEngine(Plugin owner, ErrorReports errors, boolean switchedOn, String currency)` gives a
  `Detected<EconomyProvider>` for one currency of ExcellentEconomy, the plugin CoinsEngine was renamed to, built
  against ExcellentEconomy 2.8.0 and nightcore 2.16.7. The plugin lists `ExcellentEconomy` and `nightcore` in
  `paper-plugin.yml`. The status's plugin is `ExcellentEconomy`, a failed detection is reported under
  `hook.excellenteconomy`, a currency ExcellentEconomy does not have gives `NOT_REGISTERED`, whose `line()`
  reads `ExcellentEconomy 2.8.0 has nothing registered with it. ExcellentEconomy has no currency named gems. It
  has coins.`, and a plugin named `CoinsEngine`, the name before the rename, is not read. The result is bound to
  the plugin, as the PlayerPoints one is, and needs no watcher.
- The ExcellentEconomy provider calls the `ExcellentEconomyAPI` methods that name a `UUID`, which also reach a
  player who is not online. `thread(player)` is `ThreadTag.ASYNC`, and a call on a thread
  `Server.isPrimaryThread()` is `true` on throws `IllegalStateException`. `scale()` is 2 for a currency that holds
  decimals and 0 for one that holds whole units. ExcellentEconomy holds a `double`, so an amount that does not
  convert to a `double` and back unchanged throws `IllegalArgumentException`: whole amounts are exact up to 2^53
  (9007199254740992), and any amount of up to 15 significant digits is. ExcellentEconomy answers a change a
  listener cancelled as a success and stops a removal at zero, so the provider registers a `MONITOR` listener of
  `ChangeBalanceEvent` for the owner with its first move and reads what a withdraw, a deposit and a balance set
  did from the change heard while it ran: a cancelled change gives `FAILED`, and a withdraw that took less than
  the amount, because another plugin lowered the balance after the check, gives back what it took and gives
  `INSUFFICIENT`. A change another plugin makes at the same time does not change the result. A move throws
  `IllegalStateException` when no change of its size was heard or two were heard with different outcomes, and
  gives `FAILED` before anything moves while the owner is not enabled and no listener is registered yet. The
  moves of the provider for one player run one at a time. `setBalance` sets the balance, both currency names are
  `ExcellentCurrency.getName()`, `currencySymbol()` is `getSymbol()`, `format` calls
  `ExcellentCurrency.format(double)`, and a receipt has no transaction id.
- `EconomyCurrency` is a record of a currency's `id`, `name` and `symbol`, the last two `null` when the economy
  gives none. `EconomyProvider.vaultUnlockedCurrencies(Plugin, ErrorReports)` and
  `EconomyProvider.coinsEngineCurrencies(Plugin, ErrorReports)` list a backend's currencies, and give an empty
  list when the backend is absent or throws, which is logged and reported. VaultUnlocked names only its default
  currency and has no symbols.
- The catalog gains `alaz.excellenteconomy` (`su.nightexpress.excellenteconomy:ExcellentEconomy` 2.8.0),
  `alaz.nightcore.main` and `alaz.nightcore.bridge` (`su.nightexpress.nightcore` 2.16.7), and
  `so.alaz.taffy.repositories` adds `https://repo.nightexpressdev.com/releases` for those two groups only. The
  artifact `su.nightexpress.coinsengine:CoinsEngine` is no longer in that repository.

## [0.4.12] - 2026-11-23

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.33.0 and bStats 3.2.1.

### Added
- `taffy-hooks`: `CustomItemPlugin.CRAFTENGINE` (plugin `CraftEngine`, key `craftengine`) and
  `CustomItemPlugin.MMOITEMS` (plugin `MMOItems`, key `mmoitems`), after `HEADDATABASE` in resolution order and
  with no alias. `CustomItems` builds a CraftEngine item from `craftengine:<namespace>:<id>` through
  `CraftEngineItems.byId` and reads it back through `CraftEngineItems.getCustomItemId`, and builds an MMOItems
  item from `mmoitems:<type>:<id>` through `MMOItems.getItem(String, String)` and reads it back through
  `MMOItems.getTypeName` and `MMOItems.getID`, with the type and the id in the case MMOItems holds them in, as in
  `mmoitems:SWORD:CUTLASS`. Built against CraftEngine 26.10 and MMOItems-API 6.10.1-SNAPSHOT. A `switch` over
  `CustomItemPlugin` needs a `default` branch, as its Javadoc states from 0.4.0.
- A plugin that passes `EnumSet.allOf(CustomItemPlugin.class)` to `CustomItems.detect` detects both, and an
  installed CraftEngine or MMOItems that the plugin's `paper-plugin.yml` does not list under
  `dependencies.server` is `NOT_VISIBLE` and warned about.
- For both plugins, an id without a colon or with nothing before or after its first colon, an id the plugin does
  not know and an MMOItems type that does not exist give `Built.Reason.UNKNOWN_ID`. CraftEngine finishes its
  first load after the server has started: it is `LOADING` and `build` gives `NOT_READY` until its
  `CraftEngineReloadEvent`, or it is on at once when `CraftEngine.isFullyLoaded()` is `true` or an earlier
  `detect` in this server run heard that event, and `resolve` reads its ids while it loads. MMOItems has loaded
  its items by the time it is enabled, so it is on at once, and its items are built and read on the server
  thread. A failure is reported under `hook.craftengine` or `hook.mmoitems`.
- The `onItemsLoaded` callback of `CustomItems.detect` runs for CraftEngine inside `CraftEngineReloadEvent`, at
  the end of its first load and of every reload. MMOItems has no event for the end of a load, so the callback runs
  inside `MMOItemsReloadEvent`, which MMOItems fires on the server thread when its reload command starts, before
  it reads its files again: work the callback hands on to a later tick finds the reloaded items.
- Catalog entries `alaz.craftengine.bukkit` and `alaz.craftengine.core` (`net.momirealms:craft-engine-bukkit` and
  `craft-engine-core` 26.10), `alaz.mmoitems.api` (`net.Indyuce:MMOItems-API` 6.10.1-SNAPSHOT) and
  `alaz.mythiclib` (`io.lumine:MythicLib-dist` 1.7.1-SNAPSHOT, the supertypes of the MMOItems classes). MMOItems
  and MythicLib publish snapshots only. `so.alaz.taffy.repositories` declares two more repositories before
  `jitpack`: `momirealms` (`https://repo.momirealms.net/releases/`) for the group `net.momirealms`, and `phoenix`
  (`https://nexus.phoenixdevt.fr/repository/maven-public/`) for the group `net.Indyuce` and the module
  `io.lumine:MythicLib-dist`.
- `taffy-hooks`: `RegionProvider.canBuild(Player player, Location location)`, a default method that answers
  `true`, so a `RegionProvider` a plugin or a test implements itself compiles and behaves as before. The provider
  of `RegionProvider.worldGuard` overrides it: it answers `true` for a player WorldGuard's own listeners let
  through (`SessionManager.hasBypass`: the permission `worldguard.region.bypass.<world>` for the location's world
  with the bypass not turned off, or a player marked as an NPC while WorldGuard's `fake-player-build-override` is
  on), and otherwise what `RegionQuery.testBuild` gives for the `build` flag at the location, which counts region
  members and owners. It reads no other flag, such as `block-break` or `block-place`. It answers `true` when
  WorldGuard is not enabled, when the location has no world or its world has unloaded, and when the check fails;
  a failed check is logged as a warning at most once a minute and reported under `hook.worldguard`. A world
  WorldGuard's regions are turned off in answers `true`, and a world whose region data WorldGuard could not load
  answers `false`, as WorldGuard denies building there. Call it on the thread that owns the player, the main
  thread on Paper and the player's region thread on Folia; the provider asks WorldGuard on the calling thread. A
  `null` player or location throws `NullPointerException`.
  Built against WorldGuard 7.0.20 and WorldEdit 7.4.4, and `taffy-hooks` now also compiles against
  `com.sk89q.worldguard:worldguard-bukkit` (`alaz.worldguard.bukkit`, already in the catalog), which holds
  `WorldGuardPlugin.wrapPlayer`. Detection does not look for the methods of the build check, so `worldGuard` gives
  the same status as in 0.4.11.
- `taffy-hooks`: `NpcProvider` and `NpcPlugin`, which read the NPCs of Citizens, FancyNpcs or ZNPCsPlus with no type
  of the NPC plugin in a signature. `NpcProvider.detect(Plugin owner, ErrorReports errors, boolean switchedOn,
  NpcPlugin plugin)` returns a `Detected<NpcProvider>` with the states of every other integration, and reports a
  failure under `hook.citizens`, `hook.fancynpcs` or `hook.znpcsplus`. `NpcPlugin` has the constants `CITIZENS`,
  `FANCYNPCS` and `ZNPCSPLUS` with `plugin()`, `key()` and `byKey(String)`, and its Javadoc states that constants
  may be added, so a `switch` over it needs a `default` branch. A plugin lists `Citizens`, `FancyNpcs` or
  `ZNPCsPlus` in its `paper-plugin.yml` under `dependencies.server` with `load: BEFORE` and `required: false`.
- `NpcProvider.location(String id)` gives a copy of where an NPC stands, or `null` when the provider is not
  available, the id names no NPC, the NPC is not spawned (a despawned Citizens NPC, a FancyNpcs NPC whose
  `spawnEntity` setting is off, a ZNPCsPlus NPC that is not enabled), its world is not loaded or the lookup fails.
  An NPC id is text in one form for each plugin: for Citizens the number of the NPC in Citizens' default registry
  in decimal, as in `12`; for FancyNpcs the name of the NPC, found in any case; for ZNPCsPlus the id of the NPC,
  which ZNPCsPlus keeps in lower case. FancyNpcs loads its NPCs five seconds after it is enabled, so they are not
  found from `onEnable`.
- `NpcProvider.onClick(BiConsumer<Player, String> listener)` sets the one listener that receives the player and the
  NPC id of every left and right click on an NPC of the NPC plugin, not told apart. Detection registers one Bukkit
  listener for the owner, which `NpcProvider.close()` unregisters and the server unregisters when the owner
  disables. One right click is passed once: Citizens and FancyNpcs fire for the main hand only, and ZNPCsPlus lets
  one interaction through for a player in 100 milliseconds. Citizens fires a left click only for a hit that did no
  damage, as on a protected NPC, and a click on a Citizens NPC outside the default registry is not passed. A click
  cancelled in the NPC plugin's event is not passed. The listener runs on the thread that owns the player: inside
  the event for Citizens and FancyNpcs, which fire on that thread, and on the player's next tick for ZNPCsPlus,
  which fires on a network thread and whose click is handed to the player's scheduler for the owner. A listener
  that throws is logged at most once a minute, reported, and goes on receiving clicks.
- Catalog entries `alaz.citizens.api` (`net.citizensnpcs:citizensapi` 2.0.44-SNAPSHOT, which Citizens publishes as
  snapshots only), `alaz.fancynpcs` (`de.oliver:FancyNpcs` 2.12.1) and `alaz.znpcsplus.api`
  (`com.github.Pyrbu:ZNPCsPlus` 2.0.0, the JitPack build of ZNPCsPlus' tag `2.0.0`, which holds the classes of
  `lol.pyr:znpcsplus-api`). `so.alaz.taffy.repositories` declares one more repository before `jitpack`, `citizens`
  (`https://maven.citizensnpcs.co/repo`) for the group `net.citizensnpcs`, and `jitpack` also serves the group
  `com.github.Pyrbu`.

### Changed
- `taffy-hooks`: `CustomItems.statuses()` holds six entries where it held four, and the detection log line names
  six plugins, for every plugin, whatever set it gives to `CustomItems.detect`: CraftEngine and MMOItems come
  after HeadDatabase and are `SWITCHED_OFF` unless the set holds them. Code that expects four entries, or reads
  HeadDatabase's status by position from the end, as `statuses().getLast()` does, reads it at index 3 or finds it
  by `HookStatus.plugin()`.
- `taffy-hooks`: a reference that starts with `craftengine:` or `mmoitems:` names that plugin and goes only to it,
  whatever set `CustomItems.detect` was given, and `CustomItemRef.hasKnownProvider()` is `true` for it. In 0.4.11
  such a hint named no plugin and the whole reference was offered to each plugin in turn, so `mmoitems:ruby`
  built an ItemsAdder item of the namespace `mmoitems`. For such a reference `create` now returns `null` and
  `build` gives `PLUGIN_OFF` while the named plugin has no integration on, where 0.4.11 built the other plugin's
  item or gave `UNKNOWN_PLUGIN`. An id of another plugin that itself starts with `craftengine:` or `mmoitems:` is
  written with that plugin's key, as in `itemsadder:mmoitems:ruby` or `ia:mmoitems:ruby`.

## [0.4.11] - 2026-11-23

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.33.0 and bStats 3.2.1.

### Added
- `taffy-hooks`: `HologramProvider.displays(Plugin owner, Scheduler scheduler, ErrorReports errors)` returns a
  `DisplayHolograms`, a `HologramProvider` that needs no hologram plugin: a hologram is one `TextDisplay` for
  each line and an optional `ItemDisplay`. Its `plugin()` is `null`, and `HologramPlugin` still names only
  detected hologram plugins. The location is the bottom centre of the text, the lines stack upward 0.28 blocks
  apart (`DisplayHolograms.LINE_SPACING`) with the last line lowest, and the item is centred 0.45 blocks
  (`ITEM_RISE`) above the bottom of the top line at 0.75 of its size (`ITEM_SCALE`). Lines are centred,
  shadowed, not see-through, never wrap, and face the viewer, at Paper's default view range, for every player
  in range.
- `DisplayHolograms.create(String, Location, List<Component>, ItemStack)` and
  `update(String, List<Component>, ItemStack)` take Adventure components and a nullable item. The
  `HologramProvider` methods take MiniMessage lines: `create` places no item and `update` keeps the item. An id
  is not prefixed, `create` answers `false` for an id in use, and `update`, `move` and `remove` answer `false`
  for an id that is not in use. A line MiniMessage refuses, such as one holding a section sign colour code,
  makes `create` and `update` answer `false` and is reported under `hook.displays`, unless the call answers
  `false` for another reason. `available()` is `false` once the provider is closed and while the owner is not
  enabled. PlaceholderAPI placeholders are not filled in.
- Every `DisplayHolograms` method may be called from any thread on Paper and on Folia. Each spawn, text change,
  move and removal runs on the region thread of the hologram's location through the owner's `Scheduler`, so a
  `true` means the change was accepted. A hologram in a chunk that is not loaded is kept and spawned when the
  chunk loads, and its entities are removed when the chunk unloads. A world unload removes the world's
  holograms from the world and from the provider.
- Display hologram entities are non-persistent, so none is saved to a world file, and each carries the owner's
  persistent data key `<owner namespace>:taffy_hologram`. An entity with that key that the provider does not
  hold is removed when the provider is built and when the entities of a chunk load. The provider removes every
  hologram at the owner's `PluginDisableEvent` and in `close()`, the entities of a move or removal that still
  waits for its region thread included; on Folia, where a disabling plugin can schedule nothing, entities in
  regions the disabling thread does not own stay until their chunk unloads or the server stops. A failed spawn
  or text change removes the hologram's entities, forgets the hologram and is reported under `hook.displays`,
  and so is an entity that refuses its removal, after which the others are still removed.

## [0.4.10] - 2026-11-23

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.33.0 and bStats 3.2.1.

### Added
- `HookStatus.line()` and `ComfiConfStatus.line()` return the owner's line of a status: `describe()` alone when
  there is no reason, and otherwise `describe()`, a full stop, one space and `reason()`, as in
  `Vault 1.7.3 has nothing registered with it. Install an economy plugin.`
- `DiagnosticReport` in `taffy-platform` builds a plugin's diagnostics report for the server owner.
  `DiagnosticReport.builder(Plugin, Scheduler.Mode)` starts it, `section(String, Supplier)` and
  `section(String, Collection)` add titled sections of `DiagnosticReport.Line(label, value)`, and `build()` reads
  every section on the calling thread. A report starts with the section `Server`: the plugin's name and version,
  the server's name and Minecraft version, the Java version and `Paper` or `Folia`. `plainText()` gives it as
  lines of text for the console and `components()` as one Adventure component for each line, with every value
  as plain text. A section whose supplier throws holds the one line `could not be read: <exception class>` and
  the failure is logged on the plugin's logger, so the rest of the report is kept. A report is bounded: 32
  sections after the standard one, 64 lines in a section, 64 characters in a label and 512 in a value, with
  what is left out counted in a line of its own, and no line holds a line break or a section sign, so no part
  reads as a legacy colour code.
- `DiagnosticReport.Line.of(String)` is a line without a label, `Line.redacted(String, String)` passes a text
  the plugin did not write through `Redaction.hide`, and `Line.pendingRestart(Collection<String>)` is the line
  `Waiting for a restart` for the paths `RestartKeys.changed` returns: it names up to 32 paths and never a
  value, and reads `none` for no path. A `HookStatus.line()` goes into a report as
  `Line.redacted("", status.line())`, since the reason of a `FAILED` status holds the class and message of an
  exception another plugin threw.
- `Storage.diagnostics()` gives the lines of a storage section without taking a connection: `Database` with
  `SQLite`, `MySQL`, `MariaDB` or `PostgreSQL`, `State` with `open` or `closed`, and for each connection pool
  (`Write pool` and `Read pool` on SQLite, `Pool` on a server) its connections in use, idle connections, waiting
  threads and maximum size. `Storage.diagnostics(CompletableFuture<Storage>)` reads the future of
  `Storage.start` without waiting for it: `State: starting` before it completes, `State: start cancelled`,
  `State: failed to start: <message>` with the failure's message passed through `Redaction.hide`, or the lines of
  the started storage. The lines of a started storage hold no host, user, password, connection URL or file
  path; the message of a failed start is the one text Taffy did not write.
- `Metrics.diagnostics()` gives the lines of a metrics section from the readers of the handle: `bStats` with
  `on` or `not running`, `FastStats` with `not running` or the SDK version and what it submits, such as
  `bukkit 0.33.0, submitting metrics and the plugin's charts`, and `FastStats error tracking` with `on` or
  `off`.
- `Redaction` in `taffy-platform` hides secrets and personal data in free text such as an exception message.
  `Redaction.hide(String)` applies the 19 rules `taffy-metrics` gives every FastStats error tracker (connection
  URLs, credentials, tokens, email addresses, UUIDs, database users and names, SQL statements, file paths and
  host names), then hides what is left of a Unix path behind a character the path rule does not read, as in
  `/home/john+smith/server/data.db`, and replaces each IPv4 and IPv6 address with `[address hidden]`.
  `Redaction.rules()` returns the rules as `Redaction.Rule(pattern, replacement)`. The rules moved from
  `taffy-metrics` unchanged, so what FastStats error tracking receives is as in 0.4.9.
- `CommandHelpers` in `taffy-platform` holds what a plugin's commands repeat.
  `CommandHelpers.permission(String)` returns the `Predicate<CommandSourceStack>` for `requires` of a Brigadier
  node: whether the sender of the source holds the node. `CommandHelpers.matching(String, Collection<String>)`
  returns the candidates that start with the typed text, in their order, ignoring letter case under
  `Locale.ROOT`, for a Brigadier or a Bukkit tab completion, and
  `CommandHelpers.suggest(SuggestionsBuilder, Collection<String>)` suggests them for the builder's remaining
  input and returns its `CompletableFuture<Suggestions>`. `CommandHelpers.target(Server, String)` returns the
  `OfflinePlayer` a typed text stands for, or null: a UUID in its 36 character form, then the exact name of an
  online player, then `Server.getOfflinePlayerIfCached`. It never asks Mojang for a name, so it does not wait
  on the network. It finds an online player whether or not the sender can see them.
- `UpdateCheck` in `taffy-platform` asks once at enable whether a newer release of the plugin is published.
  `UpdateCheck.start(Plugin, ErrorReports, boolean enabled, UpdateSource, Executor)` sends one `GET` on the
  plugin's executor to `UpdateSource.github(owner, repository)` (the releases of a GitHub repository) or
  `UpdateSource.modrinth(project)` (the versions of a Modrinth project), compares the answer with
  `getPluginMeta().getVersion()` as Semantic Versioning, and logs
  `A newer release is available: <newest> (running <running>). <releases page>` when the newest one is above
  the running version. `latest()` returns that version, or null, for a notice the plugin sends itself. A
  running version without a pre-release part is compared with releases only; one with a pre-release part, such
  as `1.5.0-rc.1` or `1.5.0-SNAPSHOT`, also with what the source marks as a pre-release. A running version that
  is not a semantic version logs one warning and sends nothing. With `enabled` false nothing is sent or logged.
  The check owns no thread and downloads nothing. The request goes over HTTPS to `api.github.com` or
  `api.modrinth.com`, follows no redirect, names the plugin and its version in `User-Agent` and holds nothing
  about players or the server, waits at most 10 seconds to connect and for each read, ends the read of the
  status line and headers 30 seconds after the request began and starts no read of the body after that, and
  reads at most 262144 bytes and 100 entries. A failed check logs one warning and throws nothing into enable.
- `ApiRules.signatureViolations` in `taffy-test` also accepts the types of `com.mojang.brigadier`, the command
  library Paper's command API is built on, in a public signature of a Taffy module. It reported them in 0.4.9.
  `CommandHelpers` names `Suggestions` and `SuggestionsBuilder`.

### Changed
- `taffy-metrics` is built for and tested with FastStats 0.33.0, up from 0.32.2, and a plugin jar must shade
  FastStats 0.33.0 or newer with this release. FastStats 0.33.0 removed `BukkitContext`, which `taffy-metrics`
  0.4.9 names, and added `FastStatsBukkit`, which this release names, so FastStats starts neither for 0.4.9
  beside FastStats 0.33.0 nor for this release beside 0.32.2. A build that applies
  `so.alaz.taffy.paper-plugin`, or that takes FastStats only as the runtime dependency of `taffy-metrics`,
  shades 0.33.0 without a change; a build that declares `dev.faststats.metrics:bukkit` itself raises it to
  0.33.0. `Metrics.fastStatsSdk()` reads `bukkit 0.33.0`. The first run that sends nothing, the keys of
  `plugins/faststats/config.properties`, the final send at `Metrics.shutdown()` and the `Metrics` readers
  behave as in 0.4.9. FastStats 0.33.0 tracks the errors Paper reports for the plugin whatever the server owner
  allows, so on a first run and with `enabled` or `submitErrors` off `taffy-metrics` makes the tracker ignore
  every error, and none is held, as in 0.4.9.
- `MetricsBuilder.start()` called before the plugin is enabled refuses the FastStats start before FastStats
  reads or writes a file, with the one warning
  `FastStats metrics failed to start: the plugin is not enabled, start metrics in or after onEnable`. In 0.4.9
  the warning was `FastStats metrics failed to start: <cause>`, and on a server without
  `plugins/faststats/config.properties` FastStats wrote the file and printed its first-run notice before the
  start failed; now the next start that succeeds is that server's first run.
- A FastStats context that cannot be made ready for another reason, as when the server refuses its listener,
  logs two lines on the owner's logger: FastStats' own error
  `Failed to make the FastStats context for <plugin> ready: <cause>` with its stack trace, then the warning
  `FastStats metrics failed to start: FastStats could not make its context ready`. With FastStats 0.32.2 it
  was the one warning `FastStats metrics failed to start: <cause>`. The context is shut down without a send
  and enable goes on, as before.

### Fixed
- `MetricsBuilder.start()` logs the one warning `FastStats metrics failed to start: <class>` and goes on when
  the FastStats classes cannot be loaded, as in a jar that shades no FastStats. In 0.4.9 it threw
  `NoClassDefFoundError` into the plugin's enable.

## [0.4.9] - 2026-11-23

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `Tx.batch(String sql, List<Tx.Binder> binders)` runs one `INSERT`, `UPDATE` or `DELETE` once for each binder
  as a JDBC batch inside the transaction, and returns one count for each binder, in order. An empty list runs
  nothing and returns an empty array. A binder that binds another number of values than the first one makes it
  throw `SQLException` before any run is sent, where a JDBC driver would run the statement with the value the
  binder before it bound. When a run fails, it throws what the driver threw, usually a `BatchUpdateException`,
  and which runs of the batch the open transaction then holds is not defined: SQLite holds the runs before the
  failing one, MySQL and MariaDB hold no run of an `INSERT` batch and also run the `UPDATE` and `DELETE` runs
  after the failing one, and PostgreSQL ends the transaction. Work lets the exception through, which rolls
  every run back, or reads a row back before it relies on it. MySQL connections are now opened with
  `rewriteBatchedStatements=true`, so the driver sends a batch in one packet and reports
  `Statement.SUCCESS_NO_INFO` in place of the count of a rewritten row. No other statement is affected by that
  property, and a plugin replaces it through `StorageSettings.Server.properties`, which accepts the name: with
  `false` the MySQL driver sends and counts each run by itself.
- `Params.intValue(int)`, `Params.intValue(Integer)` and `Row.intValue(String)` bind and read a whole number
  that fits an `int`. A `null` `Integer` binds `NULL` typed `INTEGER`, `Row.intValue` returns `null` for `NULL`,
  and it throws `SQLException` for a number outside the range of an `int` on every database, in place of the
  cut or the driver's own failure a narrowing read gives. On SQLite it also throws `SQLException` for a column
  that holds text, a number with a fraction or bytes, which the driver reads as 0 or cuts.
- `Params.bool(boolean)`, `Params.bool(Boolean)` and `Row.bool(String)` bind and read a boolean: the whole
  number 1 or 0 on SQLite, MySQL and MariaDB, and a native `boolean` on PostgreSQL. A column declared `BOOLEAN`
  fits all four. A `null` `Boolean` binds `NULL` typed `BOOLEAN`, and `Row.bool` returns `null` for `NULL`. On
  SQLite, MySQL and MariaDB it throws `SQLException` for a number other than 0 and 1, and for text such as
  `'true'`, which the SQLite driver reads as 0. PostgreSQL refuses a boolean for a whole-number column, so such
  a column there is bound with `intValue`.
- `Storage.backup(String label, int keep)` copies a running SQLite database to
  `backups/manual-<label>-<UTC yyyyMMdd-HHmmss>.db` and completes its future with that file. The copy is taken
  with `VACUUM INTO` on the writer thread, behind the writes submitted before it, so it holds each of them in
  full and nothing of a later one, and reads keep running. It is written as `<name>.tmp` and moved into place
  in one step. The label is 1 to 64 ASCII letters, digits, dots, underscores and hyphens, and `keep` is 1 or
  more: the newest `keep` files named `manual-<label>-<time>.db` with that label are kept. Copies under another
  label, the copies a start takes before a migration and `StorageSettings.backupKeep()` are untouched. On MySQL, MariaDB and
  PostgreSQL, and on storage from `Storage.over`, the future fails with a `StorageException`. The package
  documentation gains the restore sequence: close storage, replace the database file and remove its `-wal` and
  `-shm` files, start storage again.
- `Baseline.takeOverSameName()` returns a baseline that also takes over an old version table that has the name
  of storage's own version table, such as a hand-rolled `schema_version` on SQLite or
  `provouchers_schema_version` on a server. The old table is renamed to `<table>_baseline` first and storage's
  table is created under the freed name: in one transaction on SQLite and PostgreSQL, and with one
  `RENAME TABLE` on MySQL and MariaDB. A takeover that fails leaves the old table under its name with its rows.
  `Baseline.versionTable(String)` alone still stops the start for such a table.
- `Baseline.checkSchema()` returns a baseline that, before it records scripts as applied, compares the database
  with the tables and columns those scripts create, read from their `CREATE TABLE`, `ALTER TABLE`,
  `RENAME TABLE` and `DROP TABLE` statements. A missing table or column stops the start with a
  `StorageException` that names each one, before the SQLite backup and with the old version table left as it
  was. Names are compared ignoring case; types, constraints, indexes and extra tables and columns are not
  compared. A table element the reader does not know is read as a column, which then stops the start; it knows
  the constraints, `EXCLUDE` on PostgreSQL, the index words and `PARTITION` of MySQL and MariaDB, and
  `PERIOD FOR` on MariaDB.
- `AtomicWriter` in `taffy-config` writes a text file a plugin edits, as UTF-8, through `<name>.tmp` beside it:
  the temporary file is flushed to the device and put in place in one step, a symbolic link is followed and the
  POSIX permissions of the replaced file are kept. `create(Path, String)` writes a new file and throws
  `FileAlreadyExistsException` for one that exists, `write(Path, String)` replaces a file, and
  `replace(Path, String, String baseRevision, Path backups, int keep)` replaces it only while it has the
  `revision(byte[])` the edit started from: it returns `AtomicWriter.Written` with the new revision and the copy
  of the replaced content, or `AtomicWriter.Conflict` with the current revision and nothing written. The newest
  `keep` copies of a file (1 to 1000) stay in `backups`, counted for the path without its `.` and `..` parts
  in the letter case given. A write that fails leaves the file as it was and removes its temporary file and the
  copy it took. `write` and `replace` throw `NoSuchFileException` for a symbolic link that leads to no file.
  Every call blocks the calling thread, and calls for one file run one after another.
- `ConfigUpgrade` holds steps that rename, move and remove keys, each registered with `from(int version, step)`
  under the version it upgrades from. A step gets `ConfigUpgrade.Keys`: `move(String from, String to)` keeps the
  owner's value and its comments, a section with every key beneath it, and what the file already holds at `to`
  wins; `remove(String path)` deletes a key; `yaml()` gives the parsed file.
  `ConfigLoader.upgrades(ConfigUpgrade)` returns a loader that runs the steps of an older `config.yml` before it
  adds the shipped keys, so a renamed key keeps the owner's value where it got the shipped default before and
  the old key stayed in the file. The `.bak` copy holds the file as it was before the steps. A step that throws
  makes `load()` throw `ConfigLoadException` and nothing is written.
- `ConfigLoader.refuseNewer()` returns a loader whose `load()` throws `ConfigLoadException` for a `config.yml`
  with a `config-version` above the shipped one, with the message
  `config.yml is version 3, newer than the version 2 this build knows, so it is not read`. Without it such a
  file is still read as it is with a warning.
- `new YamlFolder(Path folder, Logger, String versionKey, int currentVersion, ConfigUpgrade, boolean recursive)`
  reads a layout version from each file under `versionKey`. An older file is upgraded with the steps before
  the parser sees it and saved, with the previous file kept byte for byte as `<file name>.v<N>.bak` beside it.
  A file newer than `currentVersion` is not handed to the parser, upgraded or written and adds the line
  `<name>: <versionKey> is 3, newer than the version 2 this build knows, so the file is not read`. A step that
  throws leaves its file unchanged and adds a line, and an upgrade that cannot be saved is logged and tried
  again on the next load, as is one whose dotted `versionKey` lies beneath a key that holds a value, which
  stays as the owner wrote it. With `recursive`, files in subfolders are read too, with ids such as `rare/gold`,
  which `loadOne` takes as well. The other constructors still never write to the folder.

## [0.4.8] - 2026-11-23

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `Messages.load(Path dataFolder, String folder, Supplier<InputStream> bundled, Logger, String defaultLocale,
  boolean perPlayer)` reads the language files from `dataFolder/<folder>` in place of `dataFolder/lang`: the
  bundled English file is written to `<folder>/en.yml`, and every warning names `<folder>`. The folder is one
  path segment, and a blank one, `.`, or one that holds `/`, `\`, `:` or `..` throws `IllegalArgumentException`.
  The other loads keep `lang`, and `Messages.FOLDER` is still `lang`.
- `Messages.load(Path dataFolder, String folder, Collection<String> bundledLocales,
  Function<String, InputStream> bundled, Logger, String defaultLocale, boolean perPlayer)` loads the messages of
  a plugin that bundles more languages than English. `bundled` is asked for `en` and for each code as
  `bundledLocales` writes it. The load and each `reload()` write every bundled file whose language has no file
  in the folder, as `<folder>/<code>.yml`; a file whose name reads as the same locale, such as an owner's
  `pt-BR.yml` for the code `pt_BR`, counts as that file, so no second file is written beside it. At each step
  of the lookup, a key the owner's file lacks is taken from the bundled file of the same code before the next
  step, so a `de.yml` kept from an older release serves a new key in German. A bundled
  language is served when its file is gone as well, and `loadedLanguages()` lists it. The warning about the keys
  a file lacks lists only the keys shown in another language, as `<folder>/<name> lacks <keys>; those messages
  are shown in another language.`, and is logged once for each file and list of keys while the server runs. A
  bundled file that is missing or unreadable throws `IllegalStateException`, and two codes that read as one
  locale throw `IllegalArgumentException`. The other loads warn as before, on the load and on each reload.
- `Messages.addMissingKeys()` appends each key of the bundled English file that the owner's `en.yml` lacks, with
  its bundled text, at the end of that file, and returns the keys it added. `addMissingKeys(String locale)` does
  the same for the file of a language from that language's own bundled file, and adds nothing for a language
  the plugin bundles no file of, so a file is never given English text. The owner's bytes stay as they are:
  lines, comments, order, encoding and byte order mark are kept, and the added lines end as the file's lines do.
  A key is added in its dotted form, such as `shop.sold: "..."`. The new content is written to `<name>.tmp`
  beside the file and moved over it in one step, and the file is then read again. A file that is not valid YAML
  or is not saved as UTF-8 is left as it is, with the warning `<folder>/<name> is not valid YAML saved as UTF-8,
  so no key was added to it.` A key the file writes with no value, as `key:` or `key: ~`, counts as one it has
  and is not added. A file whose end does not take the keys (it ends inside a block scalar with no final line
  break, ends with the document end marker `...`, writes its keys as a flow mapping, or holds text under a key
  the bundled file uses as a section) is left as it is, with the warning `<folder>/<name> was not changed,
  because <keys> cannot be added at its end.` Nothing calls it for the plugin: a plugin calls it at enable,
  behind its own setting.
- `Messages` sends by lang key: `send(Text, CommandSender, String key, TagResolver...)` as one chat message,
  `sendActionBar(Text, Player, String key, TagResolver...)` and
  `showTitle(Text, Player, String titleKey, String subtitleKey, Title.Times, TagResolver...)`. Each renders as
  `render(Text, Player, String, TagResolver...)` does and belongs on the thread that owns the player. An empty
  message, one whose lines joined by a newline are an empty text, is not sent, and a title is not shown when
  its title and subtitle are both empty. `showTitle(Text, Player, String key, TagResolver...)` shows a title
  written as one lang entry: `<key>.title`, `<key>.subtitle`, and `<key>.fadeIn`, `<key>.stay` and
  `<key>.fadeOut` as whole numbers of ticks, each `Title.DEFAULT_TIMES` when it is absent, not a whole number of
  at least 0, or too long to count in milliseconds.
- `Messages.withChatPrefix(Supplier<String> prefix)` returns a `Messages` over the same files that puts a prefix
  from the plugin, such as a `config.yml` setting, in front of each message `send` sends. The prefix is a
  MiniMessage template read on each send and rendered with the message, so a tag it leaves open applies to the
  message. A message of several lines gets it once, in front of its first line, and an empty message, which is
  not sent, gets none. A render, an action bar and a title do not get it, and the `prefix` key of the lang files
  still appears only where a template writes `<prefix>`. Without it, `send` adds no prefix.
- `Text.withBracedNamesIgnoringCase()` and `Messages.withBracedNamesIgnoringCase()` return an instance that also
  reads a `{name}` written with upper-case letters: the name is matched against the supplied values in lower
  case, so `{crateName}` is filled by a value named `cratename`. Without it, only names of lower-case letters,
  digits, `_` and `-` are rewritten, as before. A supplied value is inserted as it is either way: a `{name}` or
  a tag inside `Placeholder.unparsed` text is not read again.
- `ConfigReader.sections(String path)` returns one `ConfigReader` for each group of keys in the list at `path`,
  in file order, so the entries of a list of rewards, costs or conditions are read with the typed readers. Each
  reader takes paths relative to its entry and shares the warning consumer, `errors()`, `problems()` and
  `readPaths()` of the reader it came from. It writes a key as `<path>[<index>].<key>` in every line and read
  path, with the index of the entry in the file counted from 0, such as `rewards[1].amount`, and gives the
  readers of a list inside its entry the same way. An entry that is not a group of keys is skipped with the
  warning `<path>[<index>] is <entry>, which is not a group of keys, so it is skipped.`, where
  `ConfigurationSection.getMapList` drops it without a word. An absent key, a blank text and an empty list give
  an empty list, and a value that is not a list gives an empty list and the warning
  `<path> is <value>; expected a list of groups of keys, so it is ignored.`
- `ConfigReader.choiceWithHyphens(String path, E fallback)` reads as `choice` does and also reads a hyphen as an
  underscore, so `complete-on-close` names `COMPLETE_ON_CLOSE`. `choice` still matches a name only as the
  constant is written.
- `ConfigProblem` in taffy-config is a record of one warning or error: `file`, `path`, `severity` (`WARNING` or
  `ERROR`), `message` and `fix`, where `file`, `path` and `fix` can be `null`. `ConfigReader.problems()` returns
  one record for each warning and each error in the order they were found. The message is the line the warning
  consumer or `errors()` received, and no line changes. `new ConfigReader(ConfigurationSection root, String
  source, Consumer<String> warnings)` builds a reader whose records name `source` as their file. The fix is the
  one given to `requiredText`, and `null` for the other lines of the reader.
- `ConfigReader.warn(String path, String message, @Nullable String fix)` and
  `error(String path, String message, @Nullable String fix)` report a check of the plugin's own on one key. The
  line is `<path> <message>.`, or `<path> <message>; <fix>.` with a fix, with the path written as the reader
  writes it, and the record carries that path and fix. `warn(String)` and `error(String)` add a record with no
  path and no fix.
- `YamlFolder.loadReport(ReportingParser<T> parser)` reads the folder as `load` does and returns a
  `YamlFolder.Report<T>` of `entries`, `errors` and `problems`. A `ReportingParser` returns a
  `YamlFolder.Parsed<T>` of the entry and the problems of its file, so a file that loads with warnings keeps its
  entry and its warnings are listed beside it. A file that fails gives one `ERROR` record with the file name and
  its line of `errors`, followed by the records of the `ConfigLoadException` its parser threw. The record of a
  `.yaml` file has the fix `rename it to <name>.yml`. `YamlFolder.Loaded`, `load` and `loadOne` are unchanged.
- `ConfigLoadException.withProblems(String message, List<ConfigProblem> problems)` builds a failure that carries
  the problems found before it, and `problems()` returns them. It is empty for a failure built with a
  constructor, and both constructors are as on `0.4.7`.
- A Kotlin plugin is a supported build of `so.alaz.taffy.paper-plugin`: applied beside `org.jetbrains.kotlin.jvm`,
  it compiles the Kotlin sources as Java 25 class files, runs the Kotlin tests under the test defaults and the
  coverage gate, and shades and relocates Taffy in the Kotlin classes. No convention changes and a Java build is
  built as on `0.4.7`: Taffy's tests now build such a plugin with the Kotlin Gradle plugin `2.4.21`, with `kotlin`
  and `kotlinx` relocated by the plugin's own build. A `when` over `Answer`, `Icon` and `EconomyResult.Status`
  needs no `else` branch. Three limits hold in such a build. Shadow prints a warning for each
  `META-INF/*.kotlin_module` file on every `shadowJar` run, because the convention sets
  `DuplicatesStrategy.EXCLUDE`; no module file is dropped. The `org.jetbrains.annotations` and
  `org.intellij.lang.annotations` classes that `kotlin-stdlib` brings, and the `_COROUTINE` classes and
  `DebugProbesKt.bin` of `kotlinx-coroutines-core`, stay at the root of the jar. `compileAgainstPaperFloor`
  compiles the Java sources without the Kotlin output, so it fails with `cannot find symbol` in a plugin whose
  Java sources name a Kotlin class of the same project. Taffy ships no Kotlin and no coroutine type: a
  `CoroutineDispatcher` over `Scheduler` is a few lines in the plugin.

## [0.4.7] - 2026-11-22

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `PlaceholderExpansionBuilder` has the prefix placeholders
  `playerPrefix(String prefix, BiFunction<Player, String, @Nullable String> handler)`,
  `offlinePrefix(String prefix, BiFunction<OfflinePlayer, String, @Nullable String> handler)` and
  `globalPrefix(String prefix, Function<String, @Nullable String> handler)`. A prefix answers every name that
  starts with it and a `_`, and its handler takes the rest of the name as written: with the prefix `keys`,
  `%shop_keys_vote%` gives the handler `vote`. The prefix is written without that `_`, and one that ends with
  `_`, such as `keys_`, throws `IllegalArgumentException`. In PlaceholderAPI a placeholder registered by its
  whole name answers first, then the longest prefix that fits, and a global prefix wins over an offline or
  player one of the same prefix. In MiniPlaceholders the rest is the argument of the tag, `<shop_keys:vote>`,
  with several arguments joined by `:`, and the tag without an argument goes to the placeholder of that whole
  name. An offline prefix is registered with PlaceholderAPI only. A handler that throws gives no value and is
  logged as `The prefix placeholder shop_keys_ failed`, at most once a minute for each prefix and apart from a
  placeholder of the whole name `keys`.
- `PlaceholderCache<K>` in taffy-hooks keeps the last known value of a placeholder whose source cannot be read on
  the thread that asks. `new PlaceholderCache<>(Duration timeToLive, int maximumEntries, Logger)` builds one, and
  `get(K key, Supplier<CompletableFuture<@Nullable String>> refresh)` returns the last known value without
  waiting and starts a refresh when the key has no value yet or its value is at least `timeToLive` old, with at
  most one refresh per key running. It returns `null` until the first refresh of a key completes. The cache owns
  no thread: `refresh` returns the future of a `Scheduler.dispatch` or a storage call. A refresh that fails
  keeps the last known value, is tried again no sooner than one `timeToLive` later and is logged at most once a
  minute for the whole cache. A new key beyond `maximumEntries` drops the key asked for longest ago among the
  keys with no refresh running, and while every kept key has a refresh running, `get` returns `null` for a new
  key and starts no refresh for it. `forget(K)` drops one key, as for a player who quits, and `clear()` drops
  all of them, as in `onDisable`.
- `Text` resolves PlaceholderAPI for a player who may be offline through `render(String, OfflinePlayer)`,
  `render(String, OfflinePlayer, TagResolver...)`, `resolve(String, OfflinePlayer)` and
  `plain(String, OfflinePlayer)`, and for no player through `renderGlobal(String)`,
  `renderGlobal(String, TagResolver...)`, `resolveGlobal(String)` and `plainGlobal(String)`, which ask each
  expansion with a `null` player. Both insert what a placeholder returns as text, as a render with a viewer
  does. MiniPlaceholders' audience tags are filled in only for a `Player`. A `null` `OfflinePlayer` skips
  PlaceholderAPI, as a `null` `Player` does, and `render(String)` still asks PlaceholderAPI for nothing. A call
  that passes a literal `null` as the player still compiles and still picks the `Player` method. `resolve` and
  `plain` are overloaded now, so a method reference such as `text::resolve` compiles only where its target type
  names the player type, as `BiFunction<String, Player, String>` does.
- `EconomyProvider.vault(VaultOptions options, @Nullable Detected<EconomyProvider> previous)` looks the Vault
  economy up as `vault(VaultOptions)` does and logs its warning, and reports a `FAILED` lookup, only when the
  status differs from `previous.status()` in plugin, state, version or reason, or when `previous` is `null`. A
  plugin passes the result it holds to each later lookup, so a Vault that stays `NOT_VISIBLE`, `API_MISSING` or
  `FAILED` is warned about once and not once per lookup. The entry points without `previous` still warn and
  report on every lookup.
- `EconomyProvider.watchVault(VaultOptions options, Scheduler scheduler)` returns a `VaultWatch`, which does the
  wiring a plugin did by hand: it looks the economy up at once, registers an `EconomyWatcher` for the owner,
  looks the economy up once more on the first tick through `Scheduler.global`, and gives each lookup the result
  before it, so a status is warned about once. `VaultWatch.current()` gives the latest `Detected` from any
  thread, and `close()` unregisters the listener and stops later lookups. Options that are switched off register
  and schedule nothing. With options that are switched on, a call while the owner is not enabled, before
  `onEnable` or once it is disabled, throws `IllegalStateException` before anything is looked up or registered.
- `EconomyProvider.playerPoints(Plugin owner, ErrorReports errors, boolean switchedOn)` detects PlayerPoints and
  gives an `EconomyProvider` on its points, built against PlayerPoints 3.3.5. The `Detected` is bound to the
  PlayerPoints plugin, so its status reads `NOT_ENABLED` while PlayerPoints is disabled, and the plugin lists
  `PlayerPoints` under `dependencies.server`. `scale()` is 0. A withdraw or deposit is never rounded: an amount
  with a fraction of a point, such as `2.5`, or above `Integer.MAX_VALUE` throws `IllegalArgumentException`
  before anything in PlayerPoints is called, and `2.00` is whole. `thread(player)` answers `ThreadTag.ASYNC`,
  since PlayerPoints loads an uncached balance from its database on the calling thread, and `balance`, `has`,
  `withdraw` and `deposit` throw `IllegalStateException` on a server tick thread, which is every region thread on
  Folia. The withdraws of the provider for one player run one at a time from the balance read through `start`
  to the take. A take or give PlayerPoints refuses, as when a listener cancels its `PlayerPointsChangeEvent`,
  gives `FAILED` with `PlayerPoints refused it` and moves no points.
- `CustomItems.detect(Plugin owner, ErrorReports errors, Set<CustomItemPlugin> switchedOn,
  Consumer<CustomItemPlugin> onItemsLoaded)` detects as the three-argument `detect` does and runs
  `onItemsLoaded` each time a plugin whose integration is on finishes loading its items, at its first load after
  the server starts and at every reload: on `ItemsAdderLoadDataEvent`, `NexoItemsLoadedEvent`,
  `OraxenItemsLoadedEvent` and HeadDatabase's `DatabaseLoadEvent`. The callback runs inside that event, on the
  thread the plugin fires it on, after the plugin counts as loaded in `ready()`, so it hands its rebuild to
  `Scheduler.global`. A plugin that had loaded its items before `detect` is announced from its next reload on.
  A callback that throws is logged at most once a minute per plugin and reported under `hook.<key>`, and the
  event goes on to its other listeners. `close()` ends the callbacks with the listeners. `CustomItems` itself
  stays immutable, and `ready()` stays `true` while a plugin reloads its items.

## [0.4.6] - 2026-11-22

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `new ItemBuilder(ItemStack base)` starts from an existing stack. `build()` copies the stack with
  `ItemStack.clone()` as it is at that call and never changes `base`, and the copy keeps the amount, name, lore,
  glint and every other property that no setter was called for. An empty lore and `glow(false)` count as not
  called. `new ItemBuilder(Material)` builds what it built before.
- `ItemBuilder` has the setters `itemModel(NamespacedKey)`, `enchant(Enchantment, int level)`, which takes a
  level above the maximum of the enchantment, `unbreakable(boolean)`, `damage(int)`, `trim(ArmorTrim)`,
  `hideTooltip(boolean)`, `color(Color)` for leather armour, a potion and a map, and `tag(PdcKey<P, C>, C)`,
  each applied in `build()` through `ItemMeta`. An item that cannot be damaged, is not armour or has no colour
  ignores `damage`, `trim` or `color`. From Paper 26.3 a map has no colour: `MapMeta.setColor` does nothing
  there.
- `ItemBuilder.damage(int)` is set only on an item whose type has a max durability or that carries a max damage
  component, so any other item gets no damage component and still stacks with an ordinary one. For an item that
  can be damaged, `build()` throws the server's `IllegalArgumentException` for a damage below zero or above the
  max damage component of the item. The server stores an `enchant` level above 255 as 255 and stores no
  enchantment for a level below 1.
- `ItemBuilder.customModelData(List<Float> values, Logger logger)` sets the floats through the
  `CUSTOM_MODEL_DATA` data component. When the data component API of the server cannot be linked, a list of one
  whole number is set through `ItemMeta.setCustomModelData` and any other list is left out, with one warning to
  `logger` for the first such build.
- `ItemBuilder.glowStyle(GlowStyle)` chooses how `glow(true)` makes the item glint: `GlowStyle.GLINT_OVERRIDE`,
  the default and what `glow` did before, or `GlowStyle.HIDDEN_ENCHANTMENT`, which adds `UNBREAKING` 1 with
  `ItemFlag.HIDE_ENCHANTS` and leaves an item that already has an enchantment as it is, for a plugin whose
  glowing items in players' hands were built that way. `ItemBuilder.applyGlow(ItemMeta, GlowStyle)` does the
  same for an item meta, and throws `NullPointerException` for a `null` style.
- `ItemBuilder.deserializeAnyOrThrow(String reference)` reads a `serialized:<base64>` reference as
  `deserializeOrThrow` does, and also the bare base64 of `ItemStack.serializeAsBytes()` with no prefix. Without
  the prefix, a reference that `Material.matchMaterial` finds is never decoded, so a material name that is also
  valid base64, such as `DIRT`, throws `IllegalArgumentException` with no cause, and a reference that is not
  base64 throws it with the decoder's exception as the cause. `deserialize` and `deserializeOrThrow` read what
  they read before.
- `AmountFormat` in `taffy-platform` writes an amount of money for a player. It is a record of a symbol, an
  optional `DecimalFormat` pattern and a scale from 0 to `AmountFormat.MAX_SCALE` (18).
  `number(BigDecimal, Locale)` writes the number with the digits and separators of the locale, and
  `format(BigDecimal, Locale)` writes the symbol directly before it. Without a pattern the number is grouped
  with exactly `scale` decimal places. With one it has the decimal places of the pattern, or `scale` when that
  is more. An amount with more decimal places is rounded half-even, and one that rounds to zero has no minus
  sign. The amount is never read as a `double`, and one `AmountFormat` can be used from any thread. The text is
  for display: `Amounts.parse` does not read its grouping separators, nor the digits and the minus sign of a
  locale that has its own, such as `ar-SA`.
- `AmountFormat.checkPattern(String)` checks a pattern an owner wrote, such as `#,##0.00`. It refuses a blank
  pattern, one `DecimalFormat` refuses and one that holds a percent sign, a per mille sign, the currency sign or
  `E`, with `IllegalArgumentException` and the message `invalid number pattern '<value>': expected ` followed by
  `AmountFormat.EXPECTED`. The constructor of `AmountFormat` refuses the same patterns.
- `Amounts.parse(String input, Locale, BigDecimal maximum)` in `taffy-platform` reads an amount a player typed:
  `1500`, `2.5`, `1k`, `2.5m` or `1b`, with the suffix in either letter case, the decimal separator of the
  locale and the whitespace around it ignored. It returns an `Amounts.Amount` with the exact `BigDecimal`, not
  rounded, or an `Amounts.Refused` with an `Amounts.Reason`: `EMPTY`, `TOO_LONG` (over `Amounts.MAX_LENGTH`, 64
  characters), `NOT_A_NUMBER`, `GROUPING_SEPARATOR`, `NEGATIVE` or `OVER_MAXIMUM`. A plus sign, an exponent such
  as `1e9`, a second suffix, a space before the suffix and digits other than `0` to `9` are `NOT_A_NUMBER`.
  Where the locale groups with a space, a no-break space or a narrow no-break space, such as `fr-FR` and `sv-SE`,
  each of the three between two digits is `GROUPING_SEPARATOR`, so `1 000` typed with an ordinary space is. An
  amount above zero can round to zero at the scale of the currency, such as `0.004` at two places, and
  `EconomyProvider.withdraw` and `deposit` throw for it, so check `signum()` after rounding.
  `new Amounts.Amount(BigDecimal)` throws `IllegalArgumentException` for a value below zero.

## [0.4.5] - 2026-11-22

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `FakeDispatcher` is a `Dispatcher` for plugin tests that needs no server, published as the test fixtures of
  `taffy-platform`: `testImplementation(testFixtures(alaz.taffy.platform))`, or
  `testImplementation(testFixtures("so.alaz.taffy:taffy-platform:<version>"))`. It is in the package
  `so.alaz.taffy.platform` of `taffy-platform-<version>-test-fixtures.jar`, not in the module's own jar, so no
  plugin jar carries it, and `taffy-test` still depends on no Taffy module. `tags()` lists the `ThreadTag` of
  every dispatch in call order. A new instance runs each task on the calling thread and returns a completed
  future, which carries whatever the task threw.
- `FakeDispatcher.hold()` queues the tasks dispatched from then on, `held()` counts them, and `runHeld()` runs
  them on the calling thread in dispatch order and ends the hold, also a hold with no task held. `refuse()` fails every later dispatch without
  running its task, with `RejectedExecutionException` for `ThreadTag.GLOBAL` and `ThreadTag.ASYNC` and with
  `EntityUnavailableException` for an entity tag, and refuses the held tasks the same way, as `Scheduler.close()`
  refuses a dispatch that has not started. `drop()` accepts every later dispatch, never runs its task and never
  completes its future, and forgets the held tasks the same way. The last of `hold()`, `refuse()` and `drop()`
  called decides what a dispatch does.
- `new FaultyDataSource(DataSource source)` in `taffy-test` wraps the connections of an existing data source, so
  the faults sit above a pool passed to it or below a pool built on it. `getConnection(String user, String
  password)` of such a data source calls the same method of the wrapped one, and `unwrap` and `isWrapperFor`
  also answer for the wrapped data source and for what it wraps: a type neither is an instance of is passed to
  the `unwrap` and `isWrapperFor` of the wrapped data source, so a pool still unwraps to its driver data source.
  No other method calls the wrapped data source. A kill closes neither the wrapped data source nor its
  connections. `new FaultyDataSource(String url, Properties properties)` behaves as before.
- `MenuTesting` in `taffy-gui` builds a `Menus` for a plugin's menu tests, which run with no server:
  `new MenuTesting(parts).menus()` over the plugin's own `Menus.Parts`. Its menu inventories are fakes that
  record what each slot shows: `icon(Inventory inventory, int slot)` gives the `Icon`, `title(Inventory
  inventory)` the title and `inventories()` every inventory created. Its time source stands still until
  `advance(Duration step)` moves it, so a test steps over the click debounce window, and `owned(Predicate<Player>
  owned)` sets which players the calling thread owns. `deliver` passes an `InventoryClickEvent`, an
  `InventoryDragEvent`, an `InventoryCloseEvent` or a `PlayerQuitEvent` to the listeners of the menus and their
  prompts by priority, as a server does. `prompts(BiConsumer<Runnable, Duration> timer)` builds chat `Prompts`
  over the menus on the same time source, and `chat(Player player, String line)` passes a chat line to them and
  tells whether a prompt took it. The test mocks the plugin and the players itself. The class uses no Mockito and
  adds nothing to `Menus.Parts`.
- `PlayerCache<T>` in `taffy-platform` keeps one value per online player: `new PlayerCache<>(Plugin owner,
  ErrorReports errors, Dispatcher dispatcher, BiConsumer<Runnable, Duration> timer, Function<UUID,
  CompletableFuture<T>> loader)`, such as `new PlayerCache<>(this, errors, scheduler, scheduler::delayedAsync,
  loader)`. It registers its own join and quit listener on the owner when it is built and loads every player
  already online then. A join starts a load, which calls the loader through the dispatcher with
  `ThreadTag.ASYNC`, and a quit forgets the player at `EventPriority.MONITOR`, after the quit handlers of a
  lower priority and after a `MONITOR` handler registered before the cache was built. `get(UUID player)` gives
  an `Optional` of the value and `ready(UUID player)` tells whether there is one, from any thread: they stay
  empty and `false` until a load succeeds. A failed load is reported to the `ErrorReports` as `PlayerCache.SOURCE` (`player-cache.load`),
  logged through a `ThrowLog` of the owner's logger and tried again after `PlayerCache.FIRST_RETRY_DELAY`
  (2 seconds), then after twice the delay before it, up to `PlayerCache.LONGEST_RETRY_DELAY` (5 minutes), while
  the player stays online. The result of a load that ends after the player quit, or after a newer load of that
  player started, is dropped. `refresh(UUID player)` loads an online player again and keeps the value held until
  the new load succeeds. `close()` unregisters the listener and forgets every player. The cache loads nothing at
  `AsyncPlayerPreLoginEvent`, denies no login and saves nothing at a quit or at `close()`. On Folia, a player
  whose quit is handled on another thread while the constructor runs may keep an entry until they join or quit
  again or the cache is closed.
- `paperPlugin.generateLoader` in `so.alaz.taffy.paper-plugin`, off unless a build sets it to `true`. With runtime
  libraries listed, `generateRuntimeLibraries` then also writes `RuntimeLibrariesLoader` beside
  `RuntimeLibraries`, in the package named by the project group: a package-private Paper `PluginLoader` that hands
  each entry of `RuntimeLibraries.COORDINATES` to one `MavenLibraryResolver` over the repository
  `MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR`, so the plugin writes no loader class. The plugin still
  writes `loader: <group>.RuntimeLibrariesLoader` in `paper-plugin.yml` and needs paper-api on its compile
  classpath. Paper makes the constructor of the class under `loader` accessible before it calls it, from the
  oldest supported build on. While the setting is on, `**/RuntimeLibrariesLoader.class` is in
  `coverage.conventionExclusions`, and the `verifyPluginLoader` failure for a missing `loader` key names the
  generated class. The check does not read the class, so a `loader` key that names another class passes. With no
  runtime library listed, no class is generated, and `verifyPluginLoader` fails when the `loader` key still names
  `<group>.RuntimeLibrariesLoader`, with a message that says to remove the key or list a library. A build that
  leaves the setting off has the generated source and the jar it had on 0.4.4.

## [0.4.4] - 2026-11-22

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `Scheduler.repeatingAsyncTask(Runnable task, Duration initialDelay, Duration period)` in `taffy-platform` starts
  the task `repeatingAsync` starts and returns Paper's own `ScheduledTask`, or `null` when the plugin is not
  enabled. `ScheduledTask.cancel()` stops that one task, so a plugin replaces a task whose period changed on a
  reload without `Scheduler.close()`, which stops every repeating task of the instance. `repeatingAsync` keeps
  its `boolean`.
- `Scheduler` schedules delayed and repeating tasks on the global region thread and on an entity's thread:
  `delayedGlobal(Runnable task, Duration delay)`, `repeatingGlobal(Runnable task, Duration initialDelay, Duration
  period)`, `delayedEntity(Entity entity, Runnable task, Runnable retired, Duration delay)` and
  `repeatingEntity(Entity entity, Runnable task, Runnable retired, Duration initialDelay, Duration period)`. Each
  returns Paper's `ScheduledTask`, or `null` when the task was refused: the plugin is not enabled, or the entity
  is already removed. The two entity methods have then run `retired` on the calling thread. Paper runs `retired`
  when the entity retires before a delayed task runs or while a repeating task repeats, and `retired` runs at
  most once. A repeating task whose own run removes the entity (`Entity.remove()`, a kick) stops without
  `retired`, since Paper runs no callback then.
- `Scheduler` schedules on the region thread that owns a block: `region(Location location, Runnable task)`,
  `delayedRegion(Location location, Runnable task, Duration delay)` and `repeatingRegion(Location location,
  Runnable task, Duration initialDelay, Duration period)`, each returning Paper's `ScheduledTask`, or `null` when
  the plugin is not enabled. The region is taken from the world and the block coordinates of the `Location` at
  the call. A `null` task and a `Location` with no world throw `NullPointerException`, and a `Location` whose
  world is unloaded throws the `IllegalArgumentException` of `Location.getWorld()`. On Paper the task runs on the
  main thread. No `ThreadTag` names a region.
- The global, entity and region methods above count in server ticks: a `Duration` is rounded up to whole 50 ms
  ticks, and anything below one tick, zero and negative values included, is one tick. A task they start that
  throws is reported once under `scheduler.global`, `scheduler.entity` or the new source `scheduler.region` and
  rethrown, as the tasks of the other methods are.
- A task is not passed its `ScheduledTask`: one that stops itself cancels the returned handle, kept in a
  `volatile` field or an `AtomicReference`. A first run can start before the scheduling call returns when that
  call is made on another thread, at once for `repeatingAsyncTask` with a zero initial delay.
- `Scheduler.close()` also cancels the tasks `repeatingAsyncTask`, `repeatingGlobal`, `repeatingEntity` and
  `repeatingRegion` started. Each new repeating task drops the cancelled ones from the tasks `close()` tracks, so
  a plugin that cancels and restarts a task on every reload holds no more than its live tasks.
- `Scheduler.global`, `async`, `delayedAsync` and `repeatingAsync` carry
  `org.jetbrains.annotations.CheckReturnValue`, so IntelliJ IDEA flags a call that drops the `boolean` that
  reports a refusal. Their signatures and behaviour are unchanged. A plugin compiles as before, with or without
  `org.jetbrains:annotations` on its classpath.
- `CooldownManager.tryStart(Object key, Duration length)` in `taffy-platform` starts a cooldown only when the key
  has none running and returns whether it did, as one atomic step: of the threads that call it for one free key
  at once, exactly one gets `true`. A running cooldown is left as it is. A length of zero or less starts nothing
  and returns whether the key was free. `isOnCooldown` followed by `set` stays two calls that two threads can
  both pass.
- `CooldownManager.purgeExpired()` removes every expired cooldown and returns how many it removed. An expired
  cooldown otherwise stays in memory until its key is read or started again, so a plugin whose keys are not read
  again, such as those of players who left, calls it from a repeating task.
- `PdcKey.remove(PersistentDataHolder holder)` in `taffy-platform` removes the key from the holder's container,
  whatever the type of its value, and leaves a holder without the key unchanged. A removal from an `ItemMeta`
  reaches its item only through `ItemStack.setItemMeta`, as a `set` does.
- `PdcKey.getOrDefault(PersistentDataHolder holder, C fallback)` returns the stored value, or `fallback` when the
  key is absent, and throws `IllegalArgumentException` for a value of another type as `get` does. `PdcKey.key()`
  returns the `NamespacedKey` the instance was built with.

## [0.4.3] - 2026-11-22

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `BedrockProvider` in `taffy-hooks` tells which players joined from Minecraft Bedrock Edition.
  `BedrockProvider.detect(Plugin owner, ErrorReports errors, boolean switchedOn)` returns a
  `Detected<BedrockProvider>` on Floodgate when Floodgate can be read, and otherwise on Geyser, which is looked at
  only when Floodgate did not turn on. With neither on, the result has no provider and the status of Floodgate, or
  of Geyser when Floodgate is not installed and Geyser is. `boolean bedrock(UUID player)` answers `false` for a
  player who is not online, while the Bedrock plugin is disabled, and when the check throws; a failed check is
  logged at most once a minute and reported under `hook.floodgate` or `hook.geyser-spigot`. A plugin lists
  `floodgate` and `Geyser-Spigot` in its `paper-plugin.yml` under `dependencies.server` with `load: BEFORE` and
  `required: false`. `taffy-hooks` does not depend on `taffy-gui`, so a plugin passes `provider::bedrock` to
  `new Prompts(...)` as its `BedrockPlayers`. `taffy-hooks` reads `FloodgateApi.getInstance().isFloodgatePlayer(UUID)`
  and `GeyserApi.api().isBedrockPlayer(UUID)` through method handles and compiles against neither API, so a plugin
  declares no dependency and no repository for them. Floodgate or Geyser whose API class is there and lacks one of
  these methods is `HookState.FAILED`.
- `Prompts` in `taffy-gui` asks for text that opens no menu, for a command or a plugin's own screen.
  `Prompts.ask(Player, TextRequest)` and `Prompts.askLater(Player, TextRequest)` show the question in the chat,
  anvil or dialog of the prompts, with the cancel word, the cancel link, `Prompts.TIMEOUT` and chat for Bedrock
  players. `new TextRequest(Component question, String initial, Function<String, @Nullable Component> refusal,
  Consumer<String> accepted, Consumer<PromptEnd> ended)` holds the question: `refusal` returns `null` to take the
  answer or the reason that refuses it, `accepted` gets the answer once, and `ended` gets `CANCELLED`, `TIMED_OUT`,
  `LEFT` or `REPLACED` once when no answer was taken. `TextRequest.later(...)` takes a refusal check that returns
  a `CompletableFuture<@Nullable Component>`, for a check that reads storage or an economy, and waits for it as
  for an `Answer.Later`. A check that throws, or whose future fails, is reported under `gui.prompt`, tells the
  player `Menus.ERROR` and ends the ask as `CANCELLED`.
- `new Prompts(Menus.Parts parts, PromptMode mode, BedrockPlayers bedrock, BiConsumer<Runnable, Duration> timer)`
  builds prompts over no `Menus`. It reads the plugin, text source, locale function, server, preview key, next-tick
  tasks, entity thread, logger and error reports of the parts, and registers the prompt listener as a listener of
  the plugin, which also ends the ask of a player who quits as `LEFT`. Such prompts ask with a `TextRequest` only:
  `ask(Player, PromptRequest)` and `askLater(Player, PromptRequest)` throw `IllegalStateException` at the call.
  They share a player's ask with the other `Prompts` of the plugin. `Prompts.shutdown()` is public: a plugin calls
  it at disable for prompts built this way, and `Menus.shutdown()` still calls it for prompts built over menus.
- `PromptMode.DIALOG`, marked `@ApiStatus.Experimental` as Paper's Dialog API is, asks in a Paper dialog with one
  text field, a submit button and a cancel button, for a `PromptRequest` and a `TextRequest` alike. The title is
  the question and the field starts with `initial`, cut to `Prompts.MAX_LENGTH` characters. The cancel button and
  the escape key cancel the ask. A refused answer shows the dialog again on the next tick with the
  `gui.prompt.refused` text above the field and the refused text in it, and sends nothing to chat. The buttons
  carry the client's own text for `gui.done` and `gui.cancel`, so `Prompts.KEYS` is unchanged. Bedrock players
  answer in chat. On a server without the Dialog API, building the prompts logs one warning and every ask is asked
  in chat, as it is from the first ask on when the API cannot be linked. A `RuntimeException` of the server that
  fails to show the dialog is thrown on: from `ask` with no ask left behind, and from the next tick's task after a
  refusal, where the ask first ends as `REPLACED`. Paper has no event for a dialog that the client takes away
  without a button, as when another plugin opens an inventory or the player dies: such an ask takes no chat line
  and ends at its deadline as `TIMED_OUT`.
- With the two `ask` and `askLater` overloads and the two public `Prompts` constructors, a call that passes the
  literal `null` as the request, or as the first constructor argument, needs a cast to compile. Each threw
  `NullPointerException` before and still does.

### Fixed
- The error reports of `taffy-metrics` hide more of a message. Each of these was sent before:
  - the name of an email address before a `+`: `john+tag@sub.example.co.uk` was sent as `john+[email hidden]` and
    is now `[email hidden]`;
  - a Unix path with a space in a folder name: `/home/john smith/server/x` was sent as
    `[path hidden] smith/server/x` and is now `[path hidden]`. Each folder name after the first holds up to
    three single spaces between words of letters, digits, `_`, `.` and `-`, and a space ends the path as it ends
    a Windows path, so `/home/mc/x.yml is not valid/readable` is sent as `[path hidden] is not valid/readable`,
    as before. A path without a file extension takes up to three following words with it when a slash comes
    after them: `Saved /srv/data and logs/x now` was sent as `Saved [path hidden] and logs/x now` and is now
    `Saved [path hidden] now`. The lower case words `to`, `at`, `in`, `is`, `on`, `from`, `after`, `with`,
    `uses`, `has`, `was` and `not` are no part of a folder name, so
    `Failed to save /srv/world after 3 attempts/retries` is sent as
    `Failed to save [path hidden] after 3 attempts/retries`, as before;
  - a UNC path, such as `\\fileserver\share\John Smith\x.yml`, whose server name holds letters, digits, `_`,
    `.`, `$` and `-`, and a relative Windows path from its `Users` folder on: `..\Users\John\Desktop\x.yml`
    was sent whole and is now `..\[path hidden]`. Any other relative path is still sent;
  - the list items on the lines below a secret key: `tokens:`, a line break and `- abc123` was sent as
    `tokens:`, the line break and `[redacted] abc123`. The items, each on a line that starts with `-` and a
    space, are now sent as one `[redacted]`, also with blank lines or lines of a `#` comment between them;
  - the words after the first of a password that starts its line: `password: my pass word` was sent as
    `password: [redacted] pass word` and is now `password: [redacted]`. This holds for a key that ends in
    `password`, `passwd`, `pwd` or `passphrase` and starts its line with up to 64 characters before that word,
    as in `spring.datasource.hikari.password`, also after the `-` of a list item, when the value starts with no
    quote. Inside a sentence, as in `Login failed with password: my pass word`, the value is still one word;
  - a GitHub token (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_` or `github_pat_` and 8 or more characters), a Slack
    token (`xoxb-` and the other `xox` prefixes and 8 or more characters), a Stripe live key (`sk_live_` or
    `rk_live_` and 16 or more characters) and a JSON Web Token, each now sent as `[token hidden]`, and the
    credentials after `Authorization: Basic`, now sent as `Authorization: Basic [token hidden]`. The key is read
    in either letter case, also as Java prints a header: `{Authorization=[Basic dXNlcjpwYXNz]}` and
    `setHeader("Authorization", "Basic dXNlcjpwYXNz")` lose the credentials too. One word of letters with no
    upper case letter after its first stays, as in `Authorization: Basic authentication is not supported`;
  - the value of a `passphrase` or `license-key` key (also `license_key`, `licenseKey` and the spelling
    `licence`) before a `=` or `:`, and of a key that ends in `pass` with no letter before it, such as `pass=`
    or `db_pass=`, or in `Pass` after a lower case letter, such as `dbPass=`, before a `=`. Such a key in
    quotes counts before a `:` too, as in `"db_pass": "x"`. Each is now sent as `[redacted]`. `bypass=true`,
    `Battle pass: 3 of 5` and a count of 1 to 3 digits, as in `pass=3 fail=2`, are sent as written;
  - the value after a `=>`: `'password' => 'hunter2'` was sent as `'password' =[redacted] 'hunter2'`
    and is now `'password' => [redacted]`;
  - the id and token of a Discord webhook URL on `http://`, now sent as
    `http://[host hidden]/api/webhooks/[id hidden]/[token hidden]`;
  - the single-quoted database name of MySQL and MariaDB: `Unknown database 'survival_prod'` is now
    `Unknown database [database hidden]`.

  There are 19 rules: the bare tokens are the new rule 19, and the others are changes to rules 3, 4, 10, 12, 13,
  14 and 16. Reports whose messages differ only in a value these rules hide now merge into one kind.
- The SQL rule of the `taffy-metrics` error reports leaves an ordinary sentence as written.
  `Please select a kit from the list` was sent as `Please [sql hidden]`, and `Could not delete from the cache`
  and `Failed to insert into the database` were cut the same way; each is now sent whole. A `FROM` or `INTO`
  starts no statement when `the`, `an`, `this`, `that`, `these`, `those`, `your`, `my`, `our` or `their` follows
  it with spaces and a letter after that word, unless one of these stands in the next 200 characters, on that
  line or a later one and before the next SQL verb of the rule: `WHERE`, `VALUES (`, `DEFAULT VALUES`,
  `RETURNING`, `ORDER BY`, `GROUP BY`, `HAVING`, `LIMIT` and a digit, `SET` with a name and a `=`, `JOIN` or
  `USING` with `ON` at most three words later, `--` or `/*`. After `SELECT` this holds only when 1 to 8 words of
  letters stand between it and `FROM`, so `SELECT * FROM the list` is still hidden, and so is
  `SELECT name FROM the t WHERE pin = 1234`. A statement whose table is named by one of the ten words and that
  holds none of those, such as `SELECT name FROM the t`, is now sent. Every other statement is hidden as before,
  and so is a sentence without such a word, such as `Could not delete from disk cache`, sent as
  `Could not [sql hidden]`.
- `verifyPluginLoader` of `so.alaz.taffy.paper-plugin` fails when the `loader` key of `paper-plugin.yml` has no
  value while `paperPlugin.runtimeLibraries` lists a library, with the message of a missing key. Before, `loader:`
  followed by nothing, by spaces or by a comment only passed the check, so the jar shipped `RuntimeLibraries` and
  no loader, and the plugin failed at its first use of a library. A value on the same line counts, and so does an
  indented one on the next line that is neither blank nor a comment. The values `""`, `''`, `~`, `null`, `Null`
  and `NULL` fail the build as well, and so does `|` or `>` with no indented line below it.

## [0.4.2] - 2026-11-21

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Fixed
- The error reports of `taffy-metrics` hide more of a message. Each of these was sent before:
  - a Windows path with a space in a folder name: `Cannot read C:\Users\John Smith\Desktop\x.yml` was sent as
    `Cannot read [path hidden] Smith\Desktop\x.yml` and is now `Cannot read [path hidden]`. Each of the first 64
    folder names holds up to three single spaces. A space still ends the path when no `\` or `/` follows within
    three more words, and when the word before it ends in a file extension (a dot, a letter and up to four more
    letters or digits), so `C:\srv\x.yml is not valid/readable` is sent as `[path hidden] is not valid/readable`,
    as before. A path without such an extension takes the words up to that slash with it:
    `Opened C:\srv\data and logs/x now` was sent as `Opened [path hidden] and logs/x now` and is now
    `Opened [path hidden] now`;
  - a Windows path written with forward slashes, such as `C:/Users/John/Desktop/x.yml`, and a `file:` URL, such
    as `file:///C:/Users/John/x.yml`, `file:///home/john/x.yml` or `jar:file:/C:/srv/Shop.jar!/plugin.yml`, which
    were sent whole and are now `[path hidden]` (`jar:[path hidden]` for the last). A `file:` that ends a longer
    word, as in `profile:/home/x`, starts no URL;
  - a jdbc URL of an absolute path with a space, the shape `Storage` builds for SQLite:
    `jdbc:sqlite:C:\Users\John Smith\server\plugins\Shop\data.db` was sent as
    `jdbc:[redacted] Smith\server\plugins\Shop\data.db` and `jdbc:sqlite:/home/john/My Server/data.db` as
    `jdbc:[redacted] Server/data.db`, and both are now `jdbc:[redacted]`. A jdbc URL whose third part is an
    absolute path, also behind `file:`, is read with the folder names of the Windows path rule;
  - an email address whose last label is longer than 4 characters: `john.smith@example.online` was sent as
    `[email hidden]ne` and is now `[email hidden]`;
  - a URI password that holds a `/`: `redis://default:hun/ter2@cache:6379` was sent whole and is now
    `redis://[credentials hidden]@[host hidden]:6379`. The password runs to the last `@` before the next
    whitespace when that `@` is at most 128 characters in and no `://` comes before it, and otherwise to the
    last `@` before the next `/`, as before. What follows the `:` is read as a port when it is 1 to 5 digits that
    end the text or that whitespace or one of `/ ? # [ ] { } "` follows, as in `http://localhost:8080/users/a@b`,
    so a password of that shape is still sent. A port of a URI without credentials is therefore no longer read
    as a password up to a later `@`: `{"url":"redis://cache:6379","note":"x@y"}` was sent as
    `{"url":"redis://[credentials hidden]@y"}` and is now `{"url":"redis://[host hidden]:6379","note":"x@y"}`;
  - the database name in PostgreSQL's `no pg_hba.conf entry for host "10.0.0.5", user "mc_prod", database
    "survival_prod"`, now `database [database hidden]`. The rule hides the double-quoted name of 1 to 64
    characters after the word `database`;
  - the host of a URI, with any ending and also of one label: `redis://cache:6379` is now
    `redis://[host hidden]:6379` and `https://db.myserver.host/v1` is `https://[host hidden]/v1`, together with
    a user name before an `@`. `localhost` stays, so `http://localhost:8080/users` is sent as written;
  - a host name whose ending is not on the list of the host name rule, when it follows
    `UnknownHostException:`, `connect to`, `connecting to`, `connection to`, `connected to`, `route to`, `host`
    or `host is known` and one space, also behind an opening quote or parenthesis:
    `Connection to db.myserver.host refused` is now `Connection to [host hidden] refused`. The same holds after
    a key that ends in `host`, `hostname` or `address` and a `=` or `:`, after `refused:`, `timed out:` or
    `timed out after 30000 ms:`, and for the host the MariaDB driver repeats after its `address=(...)`:
    `Socket fail to connect to address=(host=db.myserver.host)(port=3306)(type=primary). db.myserver.host` is
    now `Socket fail to connect to address=(host=[host hidden])(port=3306)(type=primary). [host hidden]`,
    `No route to host: db.myserver.host` is `No route to host: [host hidden]` and
    `Connection refused: db.myserver.host/10.0.0.5:6379` is `Connection refused: [host hidden]/...`. Such a name
    anywhere else in a message is still sent. Any dotted name after these words is hidden, which changes an
    ordinary message such as `No value for host shop.balance`, sent before as written and now as
    `No value for host [host hidden]`. Four shapes stay as written: a name that starts with digits, a dot and a
    digit (`running on host 1.21.4`), a name whose last label starts with an upper case letter
    (`Lost connection to so.alaz.shop.Sync`), a name a `(` follows (`The host player.getName() returned null`),
    and a name whose last label is `yml`, `yaml`, `json`, `properties`, `toml`, `conf`, `cfg`, `xml`, `txt`,
    `log`, `csv`, `sql`, `db`, `sqlite`, `dat`, `jar`, `class`, `java`, `lock`, `tmp` or `bak`
    (`Could not find host config.yml`).

  Reports whose messages differ only in a value these rules hide now merge into one kind. The SQL rule is
  unchanged, so `Please select a kit from the list` is still sent as `Please [sql hidden]`.
- The secret key rule of the `taffy-metrics` error reports leaves the plain word `token`, `tokens`, `secret` or
  `secrets` before a `:` as written when a letter, a digit or a `_` and whitespace come before it, the shape of
  a sentence: `Invalid token: expected ;` was sent as `Invalid token: [redacted]` and
  `Not enough tokens: 5 required` as `Not enough tokens: [redacted] required`, and both are now sent as written.
  Such a word is still a key, with its value sent as `[redacted]`, before a `=`, when it is quoted as in
  `"token": "abc"`, when it starts its line, also after the `-` of a list item, as in a YAML line `token: abc`,
  and after any other character and up to 8 spaces or tabs, as in `Loaded {token: abc}`,
  `Bad settings (token: abc)`, `login failed, token: abc`, `discord: token: abc` and `[INFO]: token: abc`. A
  longer name around it is a key before either sign, such as `bot-token:`, `discord.token:`, `tokenValue:` and
  `secretKey=`, and so is every `password`, `passwd`, `pwd`, `api_key`, `access_key` and `private_key` key, as
  before. After another word and a space, the value is still sent as `[redacted]` when it has 6 or more
  characters with a digit among the first 65, as in `Bot token: abc123`. Only a shorter value or one without a
  digit there, as in `the token: abc`, is therefore sent, where it was redacted.
- The host name rule of the `taffy-metrics` error reports leaves package names and two-letter pairs as written:
  `Caused by java.io` was sent as `Caused by [host hidden]`, `Unknown language pt.br in config` as
  `Unknown language [host hidden] in config` and `Could not load class com.example.Me` as
  `Could not load class [host hidden]`, and all three are now sent as written. A dotted name stays when its
  first label is `java`, `javax`, `jdk`, `sun`, `kotlin`, `com`, `org` or `net`, or when it has two labels of two
  letters each. A host name of that shape, such as `mc.gg`, is therefore sent outside a URI and the words above,
  where it was hidden. Any other dotted name whose last label is on the rule's list is still hidden, a file name
  such as `messages.de`, a class name such as `me.john.Me` and a configuration key such as `shop.me` included.
- The `Prompts` of one plugin in `taffy-gui` share the ask of a player. A plugin with two `Menus` can build a
  `Prompts` over each; before, `Prompts.ask` ended only a pending ask of the same `Prompts`, so a player could
  hold one ask in each, and one chat line was taken by both listeners and answered both asks. Now `Prompts.ask`
  and `Prompts.askLater` also end the ask another `Prompts` of the plugin holds for the player, pending or
  waiting for its `Answer.Later`, whose `PromptRequest.abandoned()` gets `PromptEnd.REPLACED`, and a chat line
  answers one ask, judged by the `Prompts` that asked it. A refused answer and an `Answer.Later` no longer keep
  an ask waiting once another `Prompts` of the plugin asked the player. Opening a menu still ends only an ask
  of the `Prompts` built over its `Menus`, and `Menus.shutdown()` only the asks of that `Prompts`. The plugin is
  `Menus.Parts.plugin()`.
- `shadowJar` of `so.alaz.taffy.paper-plugin` fails when the project group is `so.alaz.taffy` or a package inside
  it, such as `so.alaz.taffy.shop`, with `so.alaz.taffy.paper-plugin relocates Taffy under <group>.libs, and the
  project group "so.alaz.taffy.shop" is so.alaz.taffy or inside it, so the relocated copy would still be inside
  so.alaz.taffy and Taffy refuses it when the plugin enables. Set group in gradle.properties to a package outside
  so.alaz.taffy, for example group=com.example.myplugin.` Before, such a build passed and its jar held Taffy
  under `so/alaz/taffy/shop/libs/taffy/`, which `Relocation.check()` counts as not relocated, so the plugin
  threw `IllegalStateException` when it enabled. A group that only starts with the name, such as
  `so.alaz.taffyshop`, builds as before.

## [0.4.1] - 2026-11-21

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

### Added
- `Relocation.check()` in `taffy-platform` throws `IllegalStateException` when a plugin's jar holds Taffy in its
  original package, with `Demo ships Taffy in its original package so.alaz.taffy, so its Taffy classes mix with
  those of every other plugin that does the same. Its build needs relocate("so.alaz.taffy",
  "<group>.libs.taffy") in the shadowJar task, or the so.alaz.taffy.paper-plugin Gradle plugin, which relocates
  Taffy.` for a plugin named `Demo`. `new ErrorReports()`, `new Text(Logger)`, `Messages.load`,
  `Messages.loadLenient`, `new ConfigLoader(...)` and `new YamlFolder(...)` call it, and every other module takes
  an `ErrorReports`, so a plugin with a hand-written `shadowJar` that lacks the `so.alaz.taffy` line fails when
  it creates one of these or uses another module. A plugin that uses only the other classes of `taffy-platform`
  and `taffy-config`, such as `DiscordWebhook` or `ItemBuilder`, does not run the check. Before, such a jar ran,
  and a class missing from its Taffy copy loaded from another plugin's copy through Paper's lookup across
  plugins. The check fails only when the classes are in `so.alaz.taffy` or a package inside it and their class
  loader implements Paper's `ConfiguredPluginClassLoader`, which it finds by name, so it does nothing in a unit
  test, on any other class loader, or in a relocated jar such as the one `so.alaz.taffy.paper-plugin` builds. It
  inspects the class loader until it first passes and returns at once after that.

### Fixed
- `so.alaz.taffy.settings` checks the Gradle version before it applies anything and fails on a Gradle older than
  9.2.0, the oldest release the plugins build a plugin on, with `so.alaz.taffy.settings needs Gradle 9.2.0 or
  newer, and this build runs on Gradle 9.1.0. Set a newer version in distributionUrl in
  gradle/wrapper/gradle-wrapper.properties.` On Gradle 9.1.0, the first release that runs on Java 25, a build that
  applied `so.alaz.taffy.paper-plugin` failed before with a `NoSuchMethodError` for
  `AdhocComponentWithVariants.addVariantsFromConfiguration` from the Shadow plugin, and a build that applied only
  the other plugins ran. The README and the docs state the minimum, and a TestKit case builds a plugin on 9.2.0.
- The `shadowJar` of `so.alaz.taffy.paper-plugin` merges the `META-INF/services` files of the plugin and of every
  shaded jar into one file per service, with each provider name relocated like its class. Before, the first file
  of a name won under `DuplicatesStrategy.EXCLUDE`, so a jar that shaded two JDBC drivers or two other
  `ServiceLoader` providers of one service kept one of them. The convention calls Shadow's `mergeServiceFiles()`
  and sets `DuplicatesStrategy.INCLUDE` on `META-INF/services/**`. Every other path, and the Groovy
  `META-INF/services/org.codehaus.groovy.runtime.ExtensionModule` descriptor, still keeps its first copy.
- The `shadowJar` of `so.alaz.taffy.paper-plugin` ships the root project's own `LICENSE` as `META-INF/LICENSE`,
  and no `META-INF/LICENSE` when the root project has none. Before, the copy from a dependency jar won, so a
  plugin that shaded a Taffy module shipped Taffy's MIT license in that place, with or without a license of its
  own. The license files of each shaded jar (the files directly in `META-INF` whose name starts with `LICENSE`, in
  any case) now ship as `META-INF/licenses/<jar file name without .jar>/<file name>`, for example
  `META-INF/licenses/taffy-platform-0.4.1/LICENSE`. Two shaded jars with one file name, compared without case,
  get `META-INF/licenses/<name>/` and `META-INF/licenses/<name>-2/`, in runtime classpath order, and a second
  license file of one jar whose name differs only in case gets the same suffix. The new task
  `collectDependencyLicenses` gathers them, and a `META-INF/LICENSE` in the plugin's own main resources still
  ships and comes before the root project's file. A `META-INF/LICENSE*` file the build adds to `shadowJar` by
  hand still ships: only the copies from the shaded jars move. `so.alaz.taffy.java` and every other jar are
  unchanged.
- The three relocations of `so.alaz.taffy.paper-plugin` stop at the package boundary: each carries an include
  pattern (`org.bstats.**`, `dev.faststats.**`, `so.alaz.taffy.**`). Before, Shadow matched the raw path prefix,
  so `so.alaz.taffyshop`, `org.bstatsx` and `dev.faststatsx` were relocated too, and a plugin with
  `group=so.alaz.taffyshop` shipped its main class as `so.alaz.taffyshop.libs.taffyshop.TaffyShop` while
  `paper-plugin.yml` named `so.alaz.taffyshop.TaffyShop`, which Paper could not load. Taffy, bStats and FastStats
  relocate as before.
- `shadowJar` and `generateRuntimeLibraries` fail on a project group that is blank or has an empty segment, with
  `so.alaz.taffy.paper-plugin relocates Taffy under <group>.libs, and the project group "" is blank or has an
  empty segment. Set group in gradle.properties, for example group=com.example.myplugin.` Before, a project
  without a group built a jar whose Taffy classes sat under `/libs/taffy/`, which no class loader can load. The
  group is read when the task is configured or run, so one the build script sets after it applies the plugin
  counts. A group that is not a Java package name, such as `com.example.my-plugin`, still builds while
  `paperPlugin.runtimeLibraries` is empty. With a library listed, `generateRuntimeLibraries` fails before javac
  does, with `paperPlugin.runtimeLibraries generates RuntimeLibraries in the package named by the project group,
  and "com.example.my-plugin" is not a Java package name. Set group in gradle.properties to one, for example
  group=com.example.myplugin.`
- A build that lists a runtime library fails when a `paper-plugin.yml` in a main resources directory, one that
  a task generates included, has no top-level `loader` key, with `paperPlugin.runtimeLibraries lists 2 libraries
  and src/main/resources/paper-plugin.yml has no top-level loader key, so Paper downloads none and the plugin
  fails at its first use of one. Add loader: with the name of the PluginLoader class that reads
  RuntimeLibraries.COORDINATES:
  https://alaz.so/taffy/docs/gradle-plugins#runtime-libraries`. Before, such a build passed and the plugin failed
  on the server with a `NoClassDefFoundError` for the first library class it used. The new task
  `verifyPluginLoader` makes the check and `processResources` depends on it. It reads no value of the key and
  checks nothing when no library is listed or the resources hold no `paper-plugin.yml`.
- `generateRuntimeLibraries` fails on an entry of `paperPlugin.runtimeLibraries` that is not `group:name:version`,
  with `The paperPlugin.runtimeLibraries entry "org.junit.platform:junit-platform-launcher:" is not
  group:name:version, with an extension or an extension and a classifier allowed before the version, in segments
  that are not blank and hold no whitespace, quote or backslash. A version catalog entry passed to runtimeLibrary
  needs a version.` Before, `runtimeLibrary` of a catalog entry without a
  version generated a coordinate that ended in a colon, which Paper's `DefaultArtifact` rejected at server start,
  and a quote or backslash in an entry went into the generated Java source as it was. An entry with an extension
  or a classifier, `group:name:extension:version` or `group:name:extension:classifier:version`, is written as
  before.
- `processResources` leaves every backslash in `paper-plugin.yml` as it is. Before, Gradle's `expand` read `\n`,
  `\t`, `\\` and the other backslash sequences as the characters they name, so `description: "First\nSecond"`
  came out with a line break in it and `Reads C:\data\shop` lost its backslashes. `${version}` is expanded, `\$`
  gives a literal dollar sign and any other `${...}` expression fails the task, as before.
- The mocks of `FakeItems` and `FakeFolders` in `taffy-test` are lenient, so a test under Mockito's strict stubs
  (`@ExtendWith(MockitoExtension.class)`, or a `MockitoSession` with `Strictness.STRICT_STUBS`) that makes
  `FakeItems.item("stone", 3)` or `FakeFolders.unlistable(null)` and calls one method passes. Before, such a test
  failed with `UnnecessaryStubbingException` for every stubbed method it left uncalled, pointing into `FakeItems`
  or `FakeFolders`.
- A `FakeItems` item answers `add()`, `add(int)`, `subtract()` and `subtract(int)` like Paper's `ItemStack`:
  `add` raises the amount to the maximum stack size at most, `subtract` lowers it to 0 at least, and each
  returns the item. Before, the four gave Mockito's default answer, so `item(Material.DIAMOND, 5).subtract(2)`
  returned `null` and left the amount at 5. The four record no `getAmount`, `setAmount` or `getMaxStackSize`
  call on the mock. `isEmpty()` is `true` at any amount for an item made from `Material.AIR`,
  `Material.CAVE_AIR` or `Material.VOID_AIR`, where it was `false` for `item(Material.AIR, 1)`. Every other
  method still gives Mockito's default answer.
- `ApiRules.signatureViolations` checks the module interfaces an exported class inherits members from, whatever
  their visibility: those the class and its module superclasses implement, and the module interfaces those
  extend. Before, only the superclasses were walked, so a public class that implemented a package-private
  interface with a default method returning a third-party type, or with a third-party superinterface, passed.
  The static methods of such an interface are left out when it is not public, because a class does not inherit
  them.
  `ApiRules.staticHolderViolations` follows a static field that names a module class into the instance fields
  of that class, its module superclasses and the module classes those fields name, and gives a line such as
  `so.alaz.taffy.demo.Registry.keeper is static and holds java.util.logging.Logger in
  so.alaz.taffy.demo.Keeper.logger`. A module class named only as a type argument, as in `Class<Keeper>` or
  `Comparator<Keeper>`, is followed too. Before, only the static field's own type was read. Neither check finds
  anything new in a Taffy module.

## [0.4.0] - 2026-11-20

Built against paper-api 26.3.build.256-stable and tested with FastStats 0.32.2 and bStats 3.2.1.

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
  `sslfactory`, `allowPublicKeyRetrieval`, `useAffectedRows` and `socketTimeout`. So do `autoReconnect` and
  `autoReconnectForPools`: with either, the MySQL driver replaces a dropped connection without an error, and the
  new session no longer holds the migration lock. `Server.toString()` shows the socket timeout and the names of
  the properties, never their values.
- `ConfigLoader.baselineVersion(int)` gives a loader that reads an owner's `config.yml` without a usable
  `config-version` as that version, where it counts as version 0. A plugin whose earlier releases shipped the file
  without the key sets the baseline to the layout those releases wrote: with `baselineVersion(1)` and a shipped
  `config-version: 1` such a file is neither rewritten nor copied, and once the shipped version is 2 it is
  upgraded from 1 and kept as `config.yml.v1.bak`. A file that holds a version is not affected. A baseline above
  the shipped `config-version` makes `load` throw `ConfigLoadException` with `the baseline version 3 is above the
  config-version 2 of the config.yml inside the plugin jar`, on every load.
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
  `<prefix>schema_version`. Two plugins that share a database and a table prefix therefore keep a migration
  history and a migration lock each, where the second to start stopped with `set up by a newer version of the
  plugin` or `changed after it ran on this database`. On the first start under `0.4.0`, a `<prefix>schema_version`
  in storage's layout that records at least one migration with the checksum of one of this plugin's scripts is
  this plugin's earlier version table: its rows are copied into the new table, one line names both tables, and the
  earlier table stays as it was and is not read again, so a server that still runs a build on `0.3.2` against the
  same database keeps starting. A start that finds no version table holds the migration lock of
  `<prefix>schema_version` too until it ends. A `<prefix>schema_version` with none of this plugin's checksums
  whose rows carry the version and name of one of this plugin's migrations stops the start with a
  `StorageException` that names the table and both checksums, as `changed after it ran on this database` did,
  unless another plugin's version table under the prefix already holds its rows. One whose rows carry neither is
  read as another plugin's table: it is left alone, one warning names it and every migration of this plugin runs
  under the new table. The name of the version table, and the plugin name the default is built from, must not
  change once a release has started on a database: under another name storage finds no history and runs every
  migration again, and when the earlier table was already copied into a table that has recorded more since, the
  start stops with a `StorageException` that names that table. Two plugin names that differ only in replaced
  characters (`My-Plugin` and `My_Plugin`), or only after the cut at 54 characters, give the same default name, so
  one of the two sets `versionTable`. A migration applied under `0.4.0` is recorded only in the new table, so a
  build on `0.3.2` or earlier that holds that migration runs it again: update every server that shares the
  database before the plugin ships a new migration. SQLite keeps `schema_version`. The warning about tables under
  another prefix names only a version table that holds this plugin's checksums. A plugin, test or query that reads
  `<prefix>schema_version` reads the new name, or the plugin keeps the earlier name with
  `settings.versionTable(settings.tablePrefix() + "schema_version")`.
- Breaking: `Storage.start` throws `IllegalArgumentException` when `StorageSettings.asyncThreads()` is above
  `StorageSettings.Pool.maxSize()`, where it started and the threads beyond the pool's size failed with a
  connection timeout under load. Each database thread uses one connection at a time, so connections above
  `asyncThreads` are never used. Set `maxSize` to `asyncThreads` or more.
- Breaking: a migration script that is still to run stops the start, before the backup and before any migration,
  when it holds what the statement splitter cannot read: a single-quoted string that does not end on the line it
  starts, a block comment that never closes or holds another block comment, dollar quoting (`$$`) outside a quoted
  string and outside a block comment, and on MySQL and MariaDB a quote directly after a backslash. The
  `StorageException` names the file and the line. Such a script was split wrongly or ran by accident. A block
  comment that closes is kept and sent with its statement, and nothing inside it is read. A script that already
  ran is only compared by its checksum and never stops a start for this. A script that already ran on any database
  must not be edited, since its checksum would no longer match there; put the corrected statement in a new
  migration instead. In a script that has not shipped yet, close each block comment, keep each quoted string on
  one line, write a quote inside a string as two quotes, and create a function or trigger outside the migration
  scripts.
- Breaking: a commit the database refuses because it rolled the transaction back (an
  `SQLTransactionRollbackException`, or an `SQLException` whose SQLState or that of a cause starts with `40`, such
  as the `40001` a PostgreSQL `SERIALIZABLE` transaction gets at its commit) completes the future of `async` and
  `read` with that exception and is run again by `asyncRetrying` and `readRetrying`, where it completed the future
  with a `CommitUnknownException` that was never retried. Nothing was written by such a commit. Any other failed
  commit is a `CommitUnknownException` as before, and so is one with SQLState `40003` (statement completion
  unknown), which does not say whether the commit was written. Code that caught `CommitUnknownException` to retry
  a serialization failure itself calls `asyncRetrying` and drops that branch.
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
- Breaking: the message of `StrictYaml.read` for a file that is not UTF-8 names the line: `<source> is not saved
  as UTF-8: the first byte that is not UTF-8 is on line <n>`, where it ended after `UTF-8`. The same message is
  the error line of `YamlFolder` for such a file and the message of `ConfigLoader.load` for such a `config.yml`.
  `YamlFileProvider` keeps the message without the line for a file an editor read or save refuses. Code that
  compares the whole message compares its start: `message.startsWith(source + " is not saved as UTF-8")`.
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
- Breaking: `CustomItemPlugin` may gain a constant in any release, and its Javadoc now states so. `0.4.0` adds
  none. In the release that adds one, a `switch` expression over the four constants of today with no `default`
  branch no longer compiles, a `switch` statement runs no branch for the new constant, and a plugin that passes
  `EnumSet.allOf(CustomItemPlugin.class)` to `CustomItems.detect` detects the added plugin too. Add a `default`
  branch to each `switch` over `CustomItemPlugin`.
- Breaking: the checksum of a migration script ignores line endings and a leading byte order mark: it is taken
  over the text with every line ending as a line feed and without the mark, where it was the SHA-256 of the
  script's exact bytes and a script that differed even in its line endings stopped the start. A build whose
  scripts were checked out with CRLF (git `autocrlf` without a `.gitattributes` entry) starts on a database that
  ran the LF scripts, where every start stopped with `migration V1__... changed after it ran on this database`. A
  row written by `0.1.0` to `0.3.2` holds the checksum of the script's exact bytes and is accepted when the script
  has the same text, with either line ending and with or without a byte order mark, and new rows hold the new
  checksum. A row recorded from a script with lone carriage returns or mixed line endings is accepted only while
  the script still has those exact bytes. A script with a byte order mark runs: the mark is no longer sent to the
  database with the first statement. A row written under `0.4.0` for a script with CRLF line endings or a byte
  order mark holds a checksum that is not the SHA-256 of the script's bytes, so a build on `0.3.2` or earlier that
  reads the same version table stops with `changed after it ran on this database`. Update every server that shares
  the database before a migration is applied, and in a test that compares the stored checksum with the SHA-256 of
  the script's bytes, compare it with the SHA-256 of the text without the mark and with every line ending as a
  line feed.
- Breaking: on PostgreSQL a start waits at most a day for the migration lock, as on MySQL and MariaDB, and then
  fails with `another start has held the migration lock of <version table> in database <schema> for a day; restart
  once the other server has started`, where it waited with no limit for a holder that stayed connected. A plugin
  that holds the lock longer than a day on purpose, or a test that expects a PostgreSQL start to wait without
  limit, handles the `StorageException` and starts again.
- Breaking: on SQLite a migration is no longer rolled back for rows that broke a foreign key before it ran, where
  every row `PRAGMA foreign_key_check` found stopped the start. `PRAGMA foreign_key_check` runs before the
  migration and again before it commits, the migration fails only for a row the second check finds and the first
  did not, and one warning per start names the tables of the rows that were already broken. The rows of a `WITHOUT
  ROWID` table have no row id in that check, so they are counted per table, parent and foreign key, and a higher
  count in the second check fails the migration. A plugin that relied on the start stopping for such rows runs
  `PRAGMA foreign_key_check` itself after `Storage.start` and stops when it returns a row.
- Breaking: `ConfigReader.parsed` treats a `DateTimeException` of the parser as a rejection, as it treats an
  `IllegalArgumentException`: `LocalTime::parse` for `25:99` and `ZoneId::of` for `Mars/Phobos` give the fallback
  and the warning, where the `DateTimeParseException` or `ZoneRulesException` left `parsed` and failed the load.
  Any other exception of the parser is still thrown on. A plugin that caught the `DateTimeException` around its
  load to report the value drops that branch, and a parser that must stop the load throws another exception type,
  such as `IllegalStateException`.
- The warning of `Messages` for a language file that is not UTF-8 names the line:
  `lang/<name> is not saved as UTF-8 (the first byte that is not UTF-8 is on line <n>), so its messages are not
  used.` A file that is not valid YAML or cannot be read keeps `lang/<name> is not valid YAML saved as UTF-8, so
  its messages are not used.`

### Fixed
- On MySQL and MariaDB each statement of a migration and its progress record commit in one transaction, so a start
  interrupted between the two no longer runs a statement that changes rows, such as
  `UPDATE accounts SET balance = balance * 10000`, a second time. A DDL statement still commits on its own before
  its record and must stay rerunnable.
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
- The save of a `ConfigLoader` upgrade writes every text on one line, and so do `YamlFileProvider.apply` and every
  other save of a document from `StrictYaml.parse`, whose line width is now `Integer.MAX_VALUE`. A text longer
  than 80 columns, such as a MiniMessage line, was folded over several indented lines, by an editor save too when
  another field changed. The `ConfigLoader` class Javadoc lists what the save of an upgrade may still change:
  quotes, the form of a number, a date or `true`/`false`, the place of an added key (after the keys the owner has
  in that section), comments inside a list, the last comment line of a file that holds no key, line endings and a
  byte order mark.
- A `ConfigLoader` upgrade whose text cannot be produced no longer throws out of `load`: a text with half of a
  surrogate pair (`"a\ud83db"`) made the YAML writer throw a `RuntimeException` after the `.bak` copy was written.
  The text is now produced before anything is written, parsed again and compared with the upgraded settings. When
  it cannot be produced, or a value would not be read back as it is (the writer turns a text with a control
  character such as `"\a"` into `!!binary`, which loads as bytes), nothing is written, no copy is made, the warning
  `Could not save the upgraded config.yml: ...` names the reason and `load` returns the upgraded settings.
- The `config.yml.v<N>.bak` copy of a `ConfigLoader` upgrade is written through a temporary file that is flushed to
  the device before the upgraded file replaces the original, so a power loss cannot leave an upgraded file beside
  an empty copy. The copy holds the bytes the upgrade read and gets the POSIX permissions of `config.yml`.
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
