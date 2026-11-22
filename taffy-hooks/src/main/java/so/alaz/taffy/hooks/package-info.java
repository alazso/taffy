/**
 * Integrations with other Paper plugins: custom item plugins, a Vault economy, LuckPerms permissions, WorldGuard
 * regions, Bedrock players through Floodgate and Geyser, placeholder expansions for PlaceholderAPI and
 * MiniPlaceholders, and text holograms through DecentHolograms, FancyHolograms, Holograms and HologramLib.
 *
 * <p>The module compiles against each integration's API but ships none of them, and its published metadata names
 * none of them. Floodgate and Geyser are the exception: they are read through method handles, so the module compiles
 * against neither API and a plugin needs no dependency for them. No public signature names an integration type: each
 * integration sits behind an interface of this package, and the class that calls the integration loads only after
 * the integration's API classes are found visible to the plugin, and, for every integration but Vault, after its
 * plugin is found installed and enabled. A server without an integration plugin therefore never loads a class that
 * names it.
 *
 * <h2>Declaring the integrations</h2>
 *
 * <p>A plugin described by {@code paper-plugin.yml} sees another plugin's classes only when it lists that plugin
 * under {@code dependencies.server}. It lists each integration it uses with {@code load: BEFORE}, so the integration
 * is enabled first, and {@code required: false}, so the plugin still starts without it:
 *
 * <pre>{@code
 * dependencies:
 *   server:
 *     Vault:
 *       load: BEFORE
 *       required: false
 *     LuckPerms:
 *       load: BEFORE
 *       required: false
 * }</pre>
 *
 * <p>The names are {@code ItemsAdder}, {@code Nexo}, {@code Oraxen}, {@code HeadDatabase}, {@code Vault},
 * {@code LuckPerms}, {@code WorldGuard}, {@code floodgate}, {@code Geyser-Spigot}, {@code PlaceholderAPI},
 * {@code MiniPlaceholders}, {@code DecentHolograms}, {@code FancyHolograms}, {@code Holograms} and
 * {@code HologramLib}. A plugin that uses WorldGuard also lists
 * {@code WorldEdit}, which WorldGuard's regions are read through. An integration that is
 * installed but not listed is {@link so.alaz.taffy.hooks.HookState#NOT_VISIBLE}, and its warning names the missing
 * entry.
 *
 * <h2>At enable</h2>
 *
 * <p>Every entry point takes the same arguments in the same order: the owner {@link org.bukkit.plugin.Plugin}, whose
 * server's plugin manager is searched and whose logger receives every warning; the plugin's
 * {@link so.alaz.taffy.platform.ErrorReports}; the owner's switch, a {@code boolean} or, for the custom item plugins,
 * the set of plugins switched on; then that integration's own options. One that is switched off is
 * {@link so.alaz.taffy.hooks.HookState#SWITCHED_OFF} and looks at nothing.
 *
 * <p>In {@code onEnable}, on the server thread, a plugin:
 *
 * <ol>
 *   <li>detects the custom item plugins with {@link so.alaz.taffy.hooks.CustomItems#detect}, which logs one line
 *     naming the state of each;</li>
 *   <li>detects LuckPerms with {@link so.alaz.taffy.hooks.PermissionProvider#luckPerms}, WorldGuard with
 *     {@link so.alaz.taffy.hooks.RegionProvider#worldGuard}, Floodgate or Geyser with
 *     {@link so.alaz.taffy.hooks.BedrockProvider#detect} and a hologram plugin with
 *     {@link so.alaz.taffy.hooks.HologramProvider#detect}, and keeps each {@link so.alaz.taffy.hooks.Detected};</li>
 *   <li>looks up the economy with {@link so.alaz.taffy.hooks.EconomyProvider#vault}, keeps the result in a
 *     {@code volatile} field, and, while the economy is switched on, registers an
 *     {@link so.alaz.taffy.hooks.EconomyWatcher} that looks it up again and looks it up once more on the first tick.
 *     An economy plugin that enables after the plugin registers with Vault only then, so a
 *     {@link so.alaz.taffy.hooks.HookState#NOT_REGISTERED} found in {@code onEnable} may not last;</li>
 *   <li>adds its placeholders to a {@link so.alaz.taffy.hooks.PlaceholderExpansionBuilder} and calls
 *     {@link so.alaz.taffy.hooks.PlaceholderExpansionBuilder#register}, keeping the
 *     {@link so.alaz.taffy.hooks.PlaceholderRegistration}.</li>
 * </ol>
 *
 * <p>PlaceholderAPI may call a handler from any thread, and each {@link so.alaz.taffy.hooks.EconomyProvider} call
 * runs on the thread {@link so.alaz.taffy.hooks.EconomyProvider#thread} names. So {@code balanceText} below returns
 * a cached balance and asks for a fresh one on that thread through
 * {@link so.alaz.taffy.platform.Scheduler#dispatch}, with at most one refresh per player in flight. The first request
 * for a player returns no value.
 *
 * <pre>{@code
 * private final ErrorReports errors = new ErrorReports();
 * private Scheduler scheduler;
 * private CustomItems items;
 * private Detected<PermissionProvider> permissions;
 * private Detected<RegionProvider> regions;
 * private volatile Detected<EconomyProvider> economy;
 * private PlaceholderRegistration placeholders;
 * private final Map<UUID, String> balances = new ConcurrentHashMap<>();
 * private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
 *
 * public void onEnable() {
 *     scheduler = new Scheduler(this, errors);
 *     items = CustomItems.detect(this, errors, settings.itemPlugins());
 *     permissions = PermissionProvider.luckPerms(this, errors, settings.hook("luckperms"));
 *     regions = RegionProvider.worldGuard(this, errors, settings.hook("worldguard"));
 *     economy = lookUpEconomy();
 *     if (settings.hook("vault")) {
 *         getServer().getPluginManager().registerEvents(
 *             new EconomyWatcher(() -> economy = lookUpEconomy(), getServer()::isStopping), this);
 *         scheduler.global(() -> economy = lookUpEconomy());
 *     }
 *     placeholders = new PlaceholderExpansionBuilder()
 *         .identifier("shop")
 *         .player("balance", this::balanceText)
 *         .register(this, errors, settings.hook("placeholders"));
 * }
 *
 * private Detected<EconomyProvider> lookUpEconomy() {
 *     boolean folia = scheduler.mode() == Scheduler.Mode.FOLIA;
 *     return EconomyProvider.vault(this, errors, settings.hook("vault"), folia, settings.scale());
 * }
 *
 * private @Nullable String balanceText(Player player) {
 *     EconomyProvider money = economy.provider();
 *     if (money == null) {
 *         return null;
 *     }
 *     UUID id = player.getUniqueId();
 *     if (refreshing.add(id)) {
 *         scheduler.dispatch(money.thread(id), () -> money.balance(id).toPlainString())
 *             .whenComplete((text, failure) -> {
 *                 if (text != null) {
 *                     balances.put(id, text);
 *                 }
 *                 refreshing.remove(id);
 *             });
 *     }
 *     return balances.get(id);
 * }
 *
 * void showHooks(CommandSender sender) {
 *     List<HookStatus> all = new ArrayList<>(items.statuses());
 *     all.add(economy.status());
 *     all.add(permissions.status());
 *     all.add(regions.status());
 *     all.addAll(placeholders.statuses());
 *     for (HookStatus status : all) {
 *         sender.sendPlainMessage(status.reason() == null ? status.describe()
 *             : status.describe() + ". " + status.reason());
 *     }
 * }
 *
 * public void onDisable() {
 *     if (placeholders != null) {
 *         placeholders.unregister();
 *     }
 *     if (scheduler != null) {
 *         scheduler.close();
 *     }
 * }
 * }</pre>
 *
 * <h2>Showing the state to the server owner</h2>
 *
 * <p>One integration gives its state through {@link so.alaz.taffy.hooks.Detected#status()}; a group gives one state
 * per plugin through {@link so.alaz.taffy.hooks.CustomItems#statuses()} and
 * {@link so.alaz.taffy.hooks.PlaceholderRegistration#statuses()}. Every status is read when asked: an integration
 * that was on reads {@link so.alaz.taffy.hooks.HookState#NOT_ENABLED} while its plugin is disabled, and on again once
 * it is enabled. The economy is the exception: its status changes only through a new lookup.
 * {@link so.alaz.taffy.hooks.HookStatus#describe()} names the state, and
 * {@link so.alaz.taffy.hooks.HookStatus#reason()}, when it is set, says what is missing or what went wrong, and what
 * the owner can do when there is something to do; together they are the owner's line. {@link so.alaz.taffy.hooks.EconomyProvider#probe()} describes an economy that was found.
 *
 * <h2>Failures</h2>
 *
 * <p>Detection follows one rule for every integration. One that is not visible to the plugin, or lacks the API that
 * is read, is logged as one warning and not reported. One that could not be hooked, because it threw or could not be
 * bound, is {@link so.alaz.taffy.hooks.HookState#FAILED}: it is logged as one warning and reported to the plugin's
 * {@link so.alaz.taffy.platform.ErrorReports} under {@code hook.<name>}, the integration plugin's name in lower case,
 * such as {@code hook.luckperms}, {@code hook.vault} or {@code hook.placeholderapi}. The warning and the report come
 * once for each detection, and every economy lookup is a new detection, so each lookup logs its warning again and
 * reports a {@link so.alaz.taffy.hooks.HookState#FAILED} lookup again. Beyond these,
 * {@link so.alaz.taffy.hooks.CustomItems#detect} logs one line naming the state of every custom item plugin, and
 * {@link so.alaz.taffy.hooks.PlaceholderExpansionBuilder#register} warns about a backend that is installed but not
 * enabled; no other state is logged. An {@link java.lang.Error} other than a {@link java.lang.LinkageError}
 * propagates.
 *
 * <p>A failure inside a later call is logged as a warning, at most once a minute for each kind of call with its stack
 * the first time. A failure that loses a write or a lookup is also reported under {@code hook.<name>}:
 * {@code hook.<key>} when a custom item cannot be built, such as {@code hook.nexo}; {@code hook.luckperms} and
 * {@code hook.worldguard} for every failed call; {@code hook.floodgate} or {@code hook.geyser-spigot} for every
 * failed check of a Bedrock player; and {@code hook.decentholograms}, {@code hook.fancyholograms},
 * {@code hook.holograms} or {@code hook.hologramlib} for every failed hologram call. Reading an item's id and
 * resolving a placeholder log a failure and do not report it.
 *
 * <p>A {@code null} argument throws {@link java.lang.NullPointerException}, except where a parameter is marked
 * {@code @Nullable}, and except a reference given to {@link so.alaz.taffy.hooks.CustomItemRef#parse} or
 * {@link so.alaz.taffy.hooks.CustomItems#create}, which is refused with {@link java.lang.IllegalArgumentException}
 * like any other text that names no item.
 *
 * <h2>At disable</h2>
 *
 * <p>At disable, a plugin calls {@link so.alaz.taffy.hooks.PlaceholderRegistration#unregister()} first, before
 * {@code Scheduler.close}, so that no placeholder request reaches the plugin while it shuts down.
 *
 * <p>The provider removes its holograms when the owner's {@code PluginDisableEvent} fires, before {@code onDisable}.
 * In {@code onDisable}, after {@code Menus.shutdown} when the plugin uses {@code taffy-gui} and before
 * {@code Scheduler.close}, the plugin calls {@link so.alaz.taffy.hooks.HologramProvider#close()} on each provider it
 * holds, which removes any hologram placed since and stops the provider. Both run on the thread that disables the
 * owner, which on Folia is the global region thread only when the owner disables itself from there.
 */
package so.alaz.taffy.hooks;
