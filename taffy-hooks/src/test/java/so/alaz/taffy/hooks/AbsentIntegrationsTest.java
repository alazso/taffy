package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.ApiRules;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.FakeItems;
import so.alaz.taffy.test.SimulatedClasspath;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Loads the module's classes on a class loader from which every integration API is absent. An integration plugin
 * whose own class loader lacks the API too stands for a server whose plugin lacks it; one whose class loader has the
 * API stands for a plugin the owner did not list in its {@code paper-plugin.yml}.
 */
final class AbsentIntegrationsTest {

    private static final String OWN = "so.alaz.taffy.hooks.";

    private static final String[] HIDDEN = {
        "dev.lone.itemsadder.", "com.nexomc.", "io.th0rgal.oraxen.", "me.arcaniax.hdb.", "net.milkbowl.vault.",
        "net.luckperms.api.", "com.sk89q.", "me.clip.placeholderapi.", "io.github.miniplaceholders.",
        "eu.decentsoftware.holograms.", "de.oliver.fancyholograms.", "net.thenextlvl.hologram.",
        "com.maximde.hologramlib.", "org.geysermc.", "org.black_ixx.", "net.momirealms.", "net.Indyuce.",
        "io.lumine.", "net.citizensnpcs.", "de.oliver.fancynpcs.", "lol.pyr.znpcsplus.", "net.milkbowl.vault2.",
        "su.nightexpress."};

    private static final String VAULT_ECONOMY = "net.milkbowl.vault.economy.Economy";

    private static final String PLAYERPOINTS = "org.black_ixx.playerpoints.PlayerPointsAPI";

    private static final String VAULTUNLOCKED_ECONOMY = "net.milkbowl.vault2.economy.Economy";

    private static final String EXCELLENTECONOMY = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";

    private static final String LUCKPERMS = "net.luckperms.api.LuckPerms";

    private static final String WORLDGUARD = "com.sk89q.worldguard.WorldGuard";

    /** Classes the WorldGuard adapter reads, from the worldguard-core, worldedit-core and worldedit-bukkit jars. */
    private static final String[] WORLDGUARD_API = {
        WORLDGUARD, "com.sk89q.worldguard.protection.managers.RegionManager",
        "com.sk89q.worldguard.protection.ApplicableRegionSet",
        "com.sk89q.worldguard.protection.regions.ProtectedRegion", "com.sk89q.worldedit.math.BlockVector3",
        "com.sk89q.worldedit.bukkit.BukkitAdapter"};

    /** Classes the Bedrock adapters read, which stand-ins in the test sources carry the names of. */
    private static final String[] BEDROCK_API = {
        "org.geysermc.floodgate.api.FloodgateApi", "org.geysermc.geyser.api.GeyserApi",
        "org.geysermc.api.GeyserApiBase"};

    private static final String PLACEHOLDERAPI = "me.clip.placeholderapi.expansion.PlaceholderExpansion";

    private static final String MINIPLACEHOLDERS = "io.github.miniplaceholders.api.Expansion";

    /** Classes the placeholder adapters read, from the placeholderapi and miniplaceholders-api jars. */
    private static final String[] PLACEHOLDER_API = {
        PLACEHOLDERAPI, "me.clip.placeholderapi.PlaceholderHook", MINIPLACEHOLDERS,
        "io.github.miniplaceholders.api.Expansion$Builder",
        "io.github.miniplaceholders.api.resolver.AudienceTagResolver",
        "io.github.miniplaceholders.api.resolver.GlobalTagResolver"};

    /** API classes the module reads whose jars are on the test classpath. */
    private static final String[] ON_TEST_CLASSPATH = {
        "dev.lone.itemsadder.api.CustomStack", "dev.lone.itemsadder.api.ItemsAdder",
        "dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent", "com.nexomc.nexo.api.NexoItems",
        "com.nexomc.nexo.api.events.NexoItemsLoadedEvent", "com.nexomc.nexo.items.ItemBuilder",
        "io.th0rgal.oraxen.api.OraxenItems", "io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent",
        "io.th0rgal.oraxen.items.ItemBuilder", "me.arcaniax.hdb.api.DatabaseLoadEvent",
        "me.arcaniax.hdb.api.HeadDatabaseAPI", "net.momirealms.craftengine.bukkit.api.CraftEngineItems",
        "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent",
        "net.momirealms.craftengine.bukkit.item.BukkitItemDefinition",
        "net.momirealms.craftengine.core.plugin.CraftEngine", "net.momirealms.craftengine.core.util.Key",
        "net.Indyuce.mmoitems.MMOItems", "net.Indyuce.mmoitems.api.event.MMOItemsReloadEvent",
        "io.lumine.mythic.lib.module.MMOPlugin", VAULT_ECONOMY, "net.milkbowl.vault.economy.EconomyResponse", LUCKPERMS,
        PLAYERPOINTS, "org.black_ixx.playerpoints.PlayerPoints",
        "net.luckperms.api.LuckPermsProvider", "net.luckperms.api.model.user.User", "net.luckperms.api.node.NodeType",
        WORLDGUARD_API[0], WORLDGUARD_API[1], WORLDGUARD_API[2], WORLDGUARD_API[3], WORLDGUARD_API[4],
        WORLDGUARD_API[5], BEDROCK_API[0], BEDROCK_API[1], BEDROCK_API[2], PLACEHOLDER_API[0], PLACEHOLDER_API[1],
        PLACEHOLDER_API[2], PLACEHOLDER_API[3], PLACEHOLDER_API[4], PLACEHOLDER_API[5],
        "net.citizensnpcs.api.CitizensAPI", "net.citizensnpcs.api.event.NPCRightClickEvent",
        "net.citizensnpcs.api.event.NPCLeftClickEvent", "de.oliver.fancynpcs.api.FancyNpcsPlugin",
        "de.oliver.fancynpcs.api.events.NpcInteractEvent", "lol.pyr.znpcsplus.api.NpcApiProvider",
        "lol.pyr.znpcsplus.api.event.NpcInteractEvent", VAULTUNLOCKED_ECONOMY,
        "net.milkbowl.vault2.economy.EconomyResponse", EXCELLENTECONOMY,
        "su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency",
        "su.nightexpress.excellenteconomy.api.currency.operation.OperationResult",
        "su.nightexpress.nightcore.util.placeholder.PlaceholderResolvable"};

    private static ClassLoader hidden() {
        return SimulatedClasspath.hiding(OWN, HIDDEN);
    }

    /** Whether code outside the package can name {@code type}: it and every enclosing class are public. */
    private static boolean exported(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /** An enabled plugin whose class is defined by {@code loader}, so the classes it sees are those of the loader. */
    private static Plugin pluginOn(ClassLoader loader, String version) {
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn(version);
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getPluginMeta" -> meta;
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "plugin " + version;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /** A plugin manager on which each named plugin is installed and enabled. */
    private static PluginManager installed(Plugin plugin, String... names) {
        PluginManager plugins = mock(PluginManager.class);
        for (String name : names) {
            when(plugins.getPlugin(name)).thenReturn(plugin);
            when(plugins.isPluginEnabled(name)).thenReturn(true);
        }
        return plugins;
    }

    private static ClassLoader withApis() {
        return AbsentIntegrationsTest.class.getClassLoader();
    }

    @Test
    void theModuleLoadsAsItsOwnCopyAndEveryProbeIsHidden() throws Exception {
        List<String> probes = new ArrayList<>();
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            probes.add(plugin.probe());
        }
        probes.add(VAULT_ECONOMY);
        probes.add(PLAYERPOINTS);
        probes.add(EconomyWatcher.UNLOCKED_ECONOMY);
        probes.add(CoinsEngineLookup.API);
        probes.add(CoinsEngineLookup.NIGHTCORE_PROBE);
        probes.add(LUCKPERMS);
        probes.add(WORLDGUARD);
        probes.add(PluginBedrock.FLOODGATE_PROBE);
        probes.add(PluginBedrock.GEYSER_PROBE);
        probes.add(PLACEHOLDERAPI);
        probes.add(MINIPLACEHOLDERS);
        for (HologramPlugin plugin : HologramPlugin.values()) {
            probes.add(plugin.probe());
        }
        for (NpcPlugin plugin : NpcPlugin.values()) {
            probes.add(plugin.probe());
        }
        ClassLoader loader = hidden();

        assertThat(Class.forName(ApiLink.class.getName(), false, loader).getClassLoader()).isSameAs(loader);
        for (String probe : probes) {
            assertThat(HIDDEN).as(probe).anyMatch(probe::startsWith);
            assertThatThrownBy(() -> Class.forName(probe, false, loader))
                .as(probe)
                .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void anApiOnTheTestClasspathIsPresentThereAndHiddenFromTheCopy() {
        ClassLoader loader = hidden();

        assertThat(ON_TEST_CLASSPATH).contains(CustomItemPlugin.ITEMSADDER.probe(), CustomItemPlugin.NEXO.probe(),
            CustomItemPlugin.ORAXEN.probe(), CustomItemPlugin.HEADDATABASE.probe(),
            CustomItemPlugin.CRAFTENGINE.probe(), CustomItemPlugin.MMOITEMS.probe(), NpcPlugin.CITIZENS.probe(),
            NpcPlugin.FANCYNPCS.probe(), NpcPlugin.ZNPCSPLUS.probe());
        for (String api : ON_TEST_CLASSPATH) {
            assertThatCode(() -> Class.forName(api, false, withApis()))
                .as(api)
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> Class.forName(api, false, loader))
                .as(api)
                .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void aPluginDefinedOnALoaderSeesOnlyThatLoadersClasses() {
        ClassLoader loader = hidden();

        assertThat(pluginOn(loader, "1.0").getClass().getClassLoader()).isSameAs(loader);
        assertThat(pluginOn(withApis(), "1.0").getClass().getClassLoader()).isSameAs(withApis());
    }

    @Test
    void publicClassesLoadWithEveryIntegrationHidden() throws Exception {
        List<Class<?>> exported = ApiRules.classes(ApiLink.class).stream()
            .filter(AbsentIntegrationsTest::exported)
            .toList();
        ClassLoader loader = hidden();

        assertThat(exported).containsExactlyInAnyOrder(HookState.class, HookStatus.class, Detected.class,
            CustomItemPlugin.class, CustomItemRef.class, CustomItems.class, CustomItems.Resolution.class,
            CustomItems.Built.class, CustomItems.Built.Reason.class, EconomyResult.class, EconomyResult.Status.class,
            EconomyReceipt.class, EconomyCurrency.class, EconomyProvider.class, VaultOptions.class,
            VaultOptions.ThreadRule.class, VaultOptions.FundsCheck.class, EconomyWatcher.class, VaultWatch.class,
            PermissionProvider.class, RegionProvider.class, BedrockProvider.class,
            PlaceholderExpansionBuilder.class, PlaceholderRegistration.class, PlaceholderCache.class,
            HologramPlugin.class,
            HologramProvider.class,
            DisplayHolograms.class,
            NpcPlugin.class,
            NpcProvider.class,
            Condition.class, ConditionResult.class, ConditionType.class, ConditionConfig.class,
            ConditionDenial.class, ConditionProblems.class, ConditionRegistry.class);
        assertThat(exported).doesNotContain(EconomyLookup.class, Detection.class, PlayerPointsEconomy.class,
            VaultUnlockedEconomy.class, CoinsEngineEconomy.class, CoinsEngineLookup.class);

        for (Class<?> type : exported) {
            assertThatCode(() -> {
                Class<?> copy = Class.forName(type.getName(), true, loader);
                copy.getDeclaredFields();
                copy.getDeclaredMethods();
                copy.getDeclaredConstructors();
                if (copy.isRecord()) {
                    copy.getRecordComponents();
                }
            }).as(type.getName()).doesNotThrowAnyException();
        }
    }

    @Test
    void detectionOnTheHiddenCopyFindsAnEnabledIntegrationWithoutItsApi() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = installed(pluginOn(loader, "1.7.3"), "Vault");
        CapturedLog log = new CapturedLog();

        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> presenceType = Class.forName(Presence.class.getName(), true, loader);
        Class<?> pluginPresenceType = Class.forName(PluginPresence.class.getName(), true, loader);
        Class<?> detectionType = Class.forName(Detection.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandle construct = MethodHandles.privateLookupIn(pluginPresenceType, MethodHandles.lookup())
            .findConstructor(pluginPresenceType, MethodType.methodType(void.class, PluginManager.class, Logger.class));
        MethodHandle run = MethodHandles.privateLookupIn(detectionType, MethodHandles.lookup()).findStatic(
            detectionType, "run", MethodType.methodType(detectedType, String.class, String.class, boolean.class,
                presenceType, Function.class, Logger.class, ErrorReports.class));
        MethodHandle status = MethodHandles.publicLookup().findVirtual(detectedType, "status",
            MethodType.methodType(statusType));
        MethodHandle describe = MethodHandles.publicLookup().findVirtual(statusType, "describe",
            MethodType.methodType(String.class));
        Function<Plugin, Object> factory = bound -> {
            throw new AssertionError("built without its API");
        };
        Object presence = construct.invoke(plugins, log.logger);

        Object detected = run.invoke("Vault", VAULT_ECONOMY, true, presence, factory, log.logger, errors);

        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(describe.invoke(status.invoke(detected))).isEqualTo("Vault 1.7.3 lacks the API that is read");
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<?> allOf(Class<?> enumType) {
        return EnumSet.allOf((Class) enumType);
    }

    private static PluginManager everyCustomItemPlugin(Plugin plugin) {
        List<String> names = new ArrayList<>();
        for (CustomItemPlugin each : CustomItemPlugin.values()) {
            names.add(each.plugin());
        }
        return installed(plugin, names.toArray(String[]::new));
    }

    private static Plugin owner(PluginManager plugins, CapturedLog log) {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        return owner;
    }

    private static List<String> described(ClassLoader loader, List<?> statuses) throws Throwable {
        MethodHandle describe = MethodHandles.publicLookup().findVirtual(
            Class.forName(HookStatus.class.getName(), true, loader), "describe", MethodType.methodType(String.class));
        List<String> described = new ArrayList<>();
        for (Object status : statuses) {
            described.add((String) describe.invoke(status));
        }
        return described;
    }

    @Test
    void customItemDetectionOnTheHiddenCopyFindsEveryPluginWithoutItsApiAndEveryCallAnswers() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = everyCustomItemPlugin(pluginOn(loader, "3.0"));
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(plugins, log);

        Class<?> items = Class.forName(CustomItems.class.getName(), true, loader);
        Class<?> resolutionType = Class.forName(CustomItems.Resolution.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(items, "detect",
            MethodType.methodType(items, Plugin.class, ErrorReports.class, Set.class));
        MethodHandle statuses = lookup.findVirtual(items, "statuses", MethodType.methodType(List.class));
        MethodHandle resolve = lookup.findVirtual(items, "resolve", MethodType.methodType(resolutionType,
            ItemStack.class));
        MethodHandle failed = lookup.findVirtual(resolutionType, "failed", MethodType.methodType(boolean.class));
        MethodHandle idOrNull = lookup.findVirtual(items, "idOrNull", MethodType.methodType(String.class,
            ItemStack.class));
        MethodHandle create = lookup.findVirtual(items, "create", MethodType.methodType(ItemStack.class,
            String.class));
        MethodHandle ready = lookup.findVirtual(items, "ready", MethodType.methodType(boolean.class));
        MethodHandle available = lookup.findVirtual(items, "available", MethodType.methodType(boolean.class,
            String.class));
        MethodHandle active = lookup.findVirtual(items, "active", MethodType.methodType(List.class));
        Set<?> all = allOf(Class.forName(CustomItemPlugin.class.getName(), true, loader));
        ItemStack gem = FakeItems.item(Material.EMERALD, 1);

        Object detected = detect.invoke(owner, new ErrorReports(), all);

        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(described(loader, (List<?>) statuses.invoke(detected))).containsExactly(
            "ItemsAdder 3.0 lacks the API that is read", "Nexo 3.0 lacks the API that is read",
            "Oraxen 3.0 lacks the API that is read", "HeadDatabase 3.0 lacks the API that is read",
            "CraftEngine 3.0 lacks the API that is read", "MMOItems 3.0 lacks the API that is read");
        assertThat(log.lines()).containsExactly(
            "WARNING ItemsAdder 3.0 lacks the API that is read, so it stays off.",
            "WARNING Nexo 3.0 lacks the API that is read, so it stays off.",
            "WARNING Oraxen 3.0 lacks the API that is read, so it stays off.",
            "WARNING HeadDatabase 3.0 lacks the API that is read, so it stays off.",
            "WARNING CraftEngine 3.0 lacks the API that is read, so it stays off.",
            "WARNING MMOItems 3.0 lacks the API that is read, so it stays off.",
            "INFO Custom item plugins: ItemsAdder 3.0 lacks the API that is read; Nexo 3.0 lacks the API that is"
                + " read; Oraxen 3.0 lacks the API that is read; HeadDatabase 3.0 lacks the API that is read;"
                + " CraftEngine 3.0 lacks the API that is read; MMOItems 3.0 lacks the API that is read.");
        verify(plugins, never()).registerEvents(any(Listener.class), any(Plugin.class));

        Object resolution = resolve.invoke(detected, gem);
        assertThat(resolution.getClass().getClassLoader()).isSameAs(loader);
        assertThat((boolean) failed.invoke(resolution)).isFalse();
        assertThat(idOrNull.invoke(detected, gem)).isNull();
        assertThat(create.invoke(detected, "ia:gems:ruby")).isNull();
        assertThat(create.invoke(detected, "ruby")).isNull();
        assertThat(create.invoke(detected, "craftengine:default:topaz")).isNull();
        assertThat(create.invoke(detected, "mmoitems:SWORD:CUTLASS")).isNull();
        assertThat((boolean) ready.invoke(detected)).isTrue();
        assertThat((boolean) available.invoke(detected, "nexo")).isFalse();
        assertThat((List<?>) active.invoke(detected)).isEmpty();
    }

    @Test
    void customItemDetectionOnTheHiddenCopyNamesEveryPluginThePluginCannotSee() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = everyCustomItemPlugin(pluginOn(withApis(), "3.0"));
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(plugins, log);

        Class<?> items = Class.forName(CustomItems.class.getName(), true, loader);
        MethodHandle detect = MethodHandles.publicLookup().findStatic(items, "detect",
            MethodType.methodType(items, Plugin.class, ErrorReports.class, Set.class));
        MethodHandle statuses = MethodHandles.publicLookup().findVirtual(items, "statuses",
            MethodType.methodType(List.class));

        Object detected = detect.invoke(owner, new ErrorReports(),
            allOf(Class.forName(CustomItemPlugin.class.getName(), true, loader)));

        assertThat(described(loader, (List<?>) statuses.invoke(detected))).containsExactly(
            "ItemsAdder 3.0 not visible to this plugin", "Nexo 3.0 not visible to this plugin",
            "Oraxen 3.0 not visible to this plugin", "HeadDatabase 3.0 not visible to this plugin",
            "CraftEngine 3.0 not visible to this plugin", "MMOItems 3.0 not visible to this plugin");
        assertThat(log.lines()).first().isEqualTo("WARNING ItemsAdder 3.0 is not visible to this plugin, so it stays"
            + " off: this plugin's paper-plugin.yml does not list ItemsAdder under dependencies.server.");
        verify(plugins, never()).registerEvents(any(Listener.class), any(Plugin.class));
    }

    @Test
    void customItemDetectionWithACallbackOnTheHiddenCopyRegistersNoListenerAndNeverRunsIt() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = everyCustomItemPlugin(pluginOn(loader, "3.0"));
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(plugins, log);
        List<Object> told = new ArrayList<>();
        Consumer<Object> callback = told::add;

        Class<?> items = Class.forName(CustomItems.class.getName(), true, loader);
        MethodHandle detect = MethodHandles.publicLookup().findStatic(items, "detect",
            MethodType.methodType(items, Plugin.class, ErrorReports.class, Set.class, Consumer.class));
        MethodHandle statuses = MethodHandles.publicLookup().findVirtual(items, "statuses",
            MethodType.methodType(List.class));
        MethodHandle ready = MethodHandles.publicLookup().findVirtual(items, "ready",
            MethodType.methodType(boolean.class));

        Object detected = detect.invoke(owner, new ErrorReports(),
            allOf(Class.forName(CustomItemPlugin.class.getName(), true, loader)), callback);

        assertThat(described(loader, (List<?>) statuses.invoke(detected))).containsExactly(
            "ItemsAdder 3.0 lacks the API that is read", "Nexo 3.0 lacks the API that is read",
            "Oraxen 3.0 lacks the API that is read", "HeadDatabase 3.0 lacks the API that is read",
            "CraftEngine 3.0 lacks the API that is read", "MMOItems 3.0 lacks the API that is read");
        assertThat((boolean) ready.invoke(detected)).isTrue();
        assertThat(told).isEmpty();
        verify(plugins, never()).registerEvents(any(Listener.class), any(Plugin.class));
    }

    private static Object vaultLookup(ClassLoader loader, Plugin owner, ErrorReports errors, boolean switchedOn)
        throws Throwable {
        return vaultLookup(loader, owner, errors, switchedOn, false);
    }

    private static Object vaultLookup(ClassLoader loader, Plugin owner, ErrorReports errors, boolean switchedOn,
                                      boolean folia) throws Throwable {
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        MethodHandle vault = MethodHandles.publicLookup().findStatic(providerType, "vault",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class,
                boolean.class, Integer.class));
        Object detected = vault.invoke(owner, errors, switchedOn, folia, (Integer) null);
        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(MethodHandles.publicLookup().findVirtual(detectedType, "provider",
            MethodType.methodType(Object.class)).invoke(detected)).isNull();
        return MethodHandles.publicLookup().findVirtual(detectedType, "status",
            MethodType.methodType(Class.forName(HookStatus.class.getName(), true, loader))).invoke(detected);
    }

    private static Object read(ClassLoader loader, Object status, String component, Class<?> type) throws Throwable {
        return MethodHandles.publicLookup().findVirtual(Class.forName(HookStatus.class.getName(), true, loader),
            component, MethodType.methodType(type)).invoke(status);
    }

    private static String stateOf(ClassLoader loader, Object status) throws Throwable {
        return ((Enum<?>) read(loader, status, "state", Class.forName(HookState.class.getName(), true, loader)))
            .name();
    }

    @Test
    void theVaultLookupOnTheHiddenCopyGivesApiMissingWithoutLoadingVault() throws Throwable {
        assertThatCode(() -> Class.forName(VAULT_ECONOMY, false, withApis())).doesNotThrowAnyException();
        ClassLoader loader = hidden();
        assertThatThrownBy(() -> Class.forName(VAULT_ECONOMY, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        PluginManager plugins = installed(pluginOn(loader, "1.7.3"), "Vault");
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(plugins, log);
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Object status = vaultLookup(loader, owner, errors, true);

        assertThat(stateOf(loader, status)).isEqualTo(HookState.API_MISSING.name());
        assertThat(read(loader, status, "describe", String.class)).isEqualTo("Vault 1.7.3 lacks the API that is read");
        assertThat(read(loader, status, "reason", String.class)).isEqualTo("Vault is installed, but it lacks the"
            + " economy API that is read. Install a version of Vault that holds it.");
        assertThat(reported).isEmpty();
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 lacks the API that is read, so it stays off.");

        Object folia = vaultLookup(loader, owner, errors, true, true);

        assertThat(stateOf(loader, folia)).isEqualTo(HookState.API_MISSING.name());
        assertThat(read(loader, folia, "plugin", String.class)).isEqualTo("Vault");
        assertThat(read(loader, folia, "reason", String.class)).isEqualTo("VaultUnlocked is installed, but it lacks"
            + " the economy API that is read. Install a version of VaultUnlocked that holds it.");
        assertThat(reported).isEmpty();
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 lacks the API that is read, so it stays off.",
            "WARNING Vault 1.7.3 lacks the API that is read, so it stays off.");
        verify(owner.getServer(), never()).getServicesManager();
    }

    @Test
    void theVaultLookupOnTheHiddenCopyNamesAVaultThePluginCannotSee() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = installed(pluginOn(withApis(), "1.7.3"), "Vault");
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(plugins, log);
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Object status = vaultLookup(loader, owner, errors, true);

        assertThat(stateOf(loader, status)).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, status, "reason", String.class)).isEqualTo("Vault is installed, but this plugin"
            + " cannot see it, because its paper-plugin.yml does not list Vault under dependencies.server.");
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 is not visible to this plugin, so it stays off:"
            + " this plugin's paper-plugin.yml does not list Vault under dependencies.server.");
        assertThat(reported).isEmpty();

        Object folia = vaultLookup(loader, owner, errors, true, true);

        assertThat(stateOf(loader, folia)).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, folia, "plugin", String.class)).isEqualTo("Vault");
        assertThat(read(loader, folia, "reason", String.class)).isEqualTo("VaultUnlocked is installed, but this"
            + " plugin cannot see it, because its paper-plugin.yml does not list Vault under dependencies.server.");
        assertThat(log.lines()).hasSize(2).last().isEqualTo("WARNING Vault 1.7.3 is not visible to this plugin, so it"
            + " stays off: this plugin's paper-plugin.yml does not list Vault under dependencies.server.");
        assertThat(reported).isEmpty();
        verify(owner.getServer(), never()).getServicesManager();
    }

    @Test
    void theVaultLookupOnTheHiddenCopyWithoutVaultAsksForVaultAndAnEconomy() throws Throwable {
        ClassLoader loader = hidden();
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(mock(PluginManager.class), log);

        Object status = vaultLookup(loader, owner, new ErrorReports(), true);

        assertThat(stateOf(loader, status)).isEqualTo(HookState.NOT_INSTALLED.name());
        assertThat(read(loader, status, "reason", String.class))
            .isEqualTo("Vault and an economy plugin, such as EssentialsX, are needed. Install both.");
        assertThat(log.lines()).isEmpty();
        verify(owner.getServer(), never()).getServicesManager();
    }

    private static Object constructWatcher(ClassLoader loader, Runnable resolve, BooleanSupplier stopping)
        throws Throwable {
        Class<?> watcher = Class.forName(EconomyWatcher.class.getName(), true, loader);
        return MethodHandles.publicLookup()
            .findConstructor(watcher, MethodType.methodType(void.class, Runnable.class, BooleanSupplier.class))
            .invoke(resolve, stopping);
    }

    private static ServiceRegisterEvent registered(RegisteredServiceProvider<?> provider) {
        ServiceRegisterEvent event = mock(ServiceRegisterEvent.class);
        doReturn(provider).when(event).getProvider();
        return event;
    }

    private static ServiceUnregisterEvent unregistered(RegisteredServiceProvider<?> provider) {
        ServiceUnregisterEvent event = mock(ServiceUnregisterEvent.class);
        doReturn(provider).when(event).getProvider();
        return event;
    }

    @Test
    void theEconomyWatcherOnTheHiddenCopyHandlesEveryServiceEventWithoutLoadingVault() throws Throwable {
        ClassLoader loader = hidden();
        AtomicInteger resolved = new AtomicInteger();
        Object watcher = constructWatcher(loader, resolved::incrementAndGet, () -> false);
        Class<?> watcherType = watcher.getClass();
        MethodHandle onRegister = MethodHandles.publicLookup().findVirtual(watcherType, "onRegister",
            MethodType.methodType(void.class, ServiceRegisterEvent.class));
        MethodHandle onUnregister = MethodHandles.publicLookup().findVirtual(watcherType, "onUnregister",
            MethodType.methodType(void.class, ServiceUnregisterEvent.class));
        Plugin owner = mock(Plugin.class);
        RegisteredServiceProvider<Runnable> unrelated = new RegisteredServiceProvider<>(Runnable.class, () -> {
        }, ServicePriority.Normal, owner);
        RegisteredServiceProvider<Economy> economy = new RegisteredServiceProvider<>(Economy.class,
            mock(Economy.class), ServicePriority.Normal, owner);

        assertThat(watcherType.getClassLoader()).isSameAs(loader);
        onRegister.invoke(watcher, registered(unrelated));
        onUnregister.invoke(watcher, unregistered(unrelated));
        assertThat(resolved).hasValue(0);

        onRegister.invoke(watcher, registered(economy));
        onUnregister.invoke(watcher, unregistered(economy));
        assertThat(resolved).hasValue(2);
        assertThatThrownBy(() -> Class.forName(VAULT_ECONOMY, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void theVaultLookupOnTheHiddenCopyGivenTheResultBeforeItWarnsOnce() throws Throwable {
        ClassLoader loader = hidden();
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        Class<?> optionsType = Class.forName(VaultOptions.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle of = lookup.findStatic(optionsType, "of",
            MethodType.methodType(optionsType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle vault = lookup.findStatic(providerType, "vault",
            MethodType.methodType(detectedType, optionsType, detectedType));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        ErrorReports errors = new ErrorReports();

        CapturedLog lackingLog = new CapturedLog();
        Object lacking = of.invoke(owner(installed(pluginOn(loader, "1.7.3"), "Vault"), lackingLog), errors, false);
        Object first = vault.invoke(lacking, null);
        Object second = vault.invoke(lacking, first);

        assertThat(stateOf(loader, status.invoke(second))).isEqualTo(HookState.API_MISSING.name());
        assertThat(status.invoke(second)).isEqualTo(status.invoke(first));
        assertThat(lackingLog.lines())
            .containsExactly("WARNING Vault 1.7.3 lacks the API that is read, so it stays off.");

        CapturedLog hiddenLog = new CapturedLog();
        Object unseen = of.invoke(owner(installed(pluginOn(withApis(), "1.7.3"), "Vault"), hiddenLog), errors, false);
        Object third = vault.invoke(unseen, second);
        Object fourth = vault.invoke(unseen, third);

        assertThat(stateOf(loader, status.invoke(fourth))).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(status.invoke(fourth)).isEqualTo(status.invoke(third));
        assertThat(hiddenLog.lines()).containsExactly("WARNING Vault 1.7.3 is not visible to this plugin, so it stays"
            + " off: this plugin's paper-plugin.yml does not list Vault under dependencies.server.");
    }

    @Test
    void playerPointsDetectionOnTheHiddenCopyFindsPlayerPointsWithoutItsApiOrOutOfSight() throws Throwable {
        assertThatCode(() -> Class.forName(PLAYERPOINTS, false, withApis())).doesNotThrowAnyException();
        ClassLoader loader = hidden();
        assertThatThrownBy(() -> Class.forName(PLAYERPOINTS, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "playerPoints",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));

        CapturedLog lackingLog = new CapturedLog();
        Object lacking = detect.invoke(
            owner(installed(pluginOn(loader, "3.3.5"), "PlayerPoints"), lackingLog), errors, true);

        assertThat(lacking.getClass().getClassLoader()).isSameAs(loader);
        assertThat(read(loader, status.invoke(lacking), "describe", String.class))
            .isEqualTo("PlayerPoints 3.3.5 lacks the API that is read");
        assertThat(provider.invoke(lacking)).isNull();
        assertThat(lackingLog.lines())
            .containsExactly("WARNING PlayerPoints 3.3.5 lacks the API that is read, so it stays off.");

        CapturedLog hiddenLog = new CapturedLog();
        Object unseen = detect.invoke(
            owner(installed(pluginOn(withApis(), "3.3.5"), "PlayerPoints"), hiddenLog), errors, true);

        assertThat(stateOf(loader, status.invoke(unseen))).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, status.invoke(unseen), "reason", String.class)).isEqualTo("PlayerPoints is installed,"
            + " but this plugin cannot see it, because its paper-plugin.yml does not list PlayerPoints under"
            + " dependencies.server.");
        assertThat(provider.invoke(unseen)).isNull();
        assertThat(hiddenLog.lines()).containsExactly("WARNING PlayerPoints 3.3.5 is not visible to this plugin, so it"
            + " stays off: this plugin's paper-plugin.yml does not list PlayerPoints under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    private static Object unlockedLookup(ClassLoader loader, Plugin owner, ErrorReports errors, boolean folia)
        throws Throwable {
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        MethodHandle lookup = MethodHandles.publicLookup().findStatic(providerType, "vaultUnlocked",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class, boolean.class,
                String.class, Integer.class));
        Object detected = lookup.invoke(owner, errors, true, folia, "gems", (Integer) null);
        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(MethodHandles.publicLookup().findVirtual(detectedType, "provider",
            MethodType.methodType(Object.class)).invoke(detected)).isNull();
        return MethodHandles.publicLookup().findVirtual(detectedType, "status",
            MethodType.methodType(Class.forName(HookStatus.class.getName(), true, loader))).invoke(detected);
    }

    @Test
    void theVaultUnlockedLookupOnTheHiddenCopyTellsAVaultWithoutTheApiFromOneOutOfSightAndLoadsNoClassOfIt()
        throws Throwable {
        assertThatCode(() -> Class.forName(VAULTUNLOCKED_ECONOMY, false, withApis())).doesNotThrowAnyException();
        ClassLoader loader = hidden();
        assertThatThrownBy(() -> Class.forName(VAULTUNLOCKED_ECONOMY, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        CapturedLog lackingLog = new CapturedLog();
        Plugin lackingOwner = owner(installed(pluginOn(loader, "1.7.3"), "Vault"), lackingLog);
        Object lacking = unlockedLookup(loader, lackingOwner, errors, false);

        assertThat(stateOf(loader, lacking)).isEqualTo(HookState.API_MISSING.name());
        assertThat(read(loader, lacking, "plugin", String.class)).isEqualTo("VaultUnlocked");
        assertThat(read(loader, lacking, "reason", String.class)).isEqualTo("The installed Vault lacks the"
            + " VaultUnlocked economy API that is read. Install VaultUnlocked in its place.");
        assertThat(lackingLog.lines()).containsExactly("WARNING The installed Vault 1.7.3 lacks the VaultUnlocked"
            + " economy API, so VaultUnlocked stays off.");

        CapturedLog hiddenLog = new CapturedLog();
        Plugin unseenOwner = owner(installed(pluginOn(withApis(), "2.20.3"), "Vault"), hiddenLog);
        Object unseen = unlockedLookup(loader, unseenOwner, errors, true);

        assertThat(stateOf(loader, unseen)).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, unseen, "reason", String.class)).isEqualTo("VaultUnlocked is installed, but this"
            + " plugin cannot see it, because its paper-plugin.yml does not list Vault, the name of the VaultUnlocked"
            + " plugin, under dependencies.server.");
        assertThat(hiddenLog.lines()).containsExactly("WARNING VaultUnlocked 2.20.3 is not visible to this plugin, so"
            + " it stays off: this plugin's paper-plugin.yml does not list Vault, the name of the VaultUnlocked"
            + " plugin, under dependencies.server.");

        CapturedLog absentLog = new CapturedLog();
        Object absent = unlockedLookup(loader, owner(mock(PluginManager.class), absentLog), errors, true);

        assertThat(stateOf(loader, absent)).isEqualTo(HookState.NOT_INSTALLED.name());
        assertThat(read(loader, absent, "reason", String.class)).isEqualTo("VaultUnlocked and an economy plugin that"
            + " supports VaultUnlocked and Folia are needed. Install both.");
        assertThat(absentLog.lines()).isEmpty();
        assertThat(reported).isEmpty();
        verify(lackingOwner.getServer(), never()).getServicesManager();
        verify(unseenOwner.getServer(), never()).getServicesManager();
    }

    @Test
    void coinsEngineDetectionOnTheHiddenCopyFindsExcellentEconomyWithoutItsApiOrOutOfSight() throws Throwable {
        assertThatCode(() -> Class.forName(EXCELLENTECONOMY, false, withApis())).doesNotThrowAnyException();
        ClassLoader loader = hidden();
        assertThatThrownBy(() -> Class.forName(EXCELLENTECONOMY, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "coinsEngine",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class, String.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));

        CapturedLog lackingLog = new CapturedLog();
        Object lacking = detect.invoke(
            owner(installed(pluginOn(loader, "2.8.0"), "ExcellentEconomy"), lackingLog), errors, true, "gems");

        assertThat(lacking.getClass().getClassLoader()).isSameAs(loader);
        assertThat(read(loader, status.invoke(lacking), "describe", String.class))
            .isEqualTo("ExcellentEconomy 2.8.0 lacks the API that is read");
        assertThat(provider.invoke(lacking)).isNull();
        assertThat(lackingLog.lines())
            .containsExactly("WARNING ExcellentEconomy 2.8.0 lacks the API that is read, so it stays off.");

        CapturedLog hiddenLog = new CapturedLog();
        Object unseen = detect.invoke(
            owner(installed(pluginOn(withApis(), "2.8.0"), "ExcellentEconomy"), hiddenLog), errors, true, "gems");

        assertThat(stateOf(loader, status.invoke(unseen))).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, status.invoke(unseen), "reason", String.class)).isEqualTo("ExcellentEconomy is"
            + " installed, but this plugin cannot see it, because its paper-plugin.yml does not list"
            + " ExcellentEconomy under dependencies.server.");
        assertThat(provider.invoke(unseen)).isNull();
        assertThat(reported).isEmpty();
    }

    @Test
    void theCurrencyListsOnTheHiddenCopyAreEmptyAndLoadNoClassOfEitherPlugin() throws Throwable {
        ClassLoader loader = hidden();
        Class<?> providerType = Class.forName(EconomyProvider.class.getName(), true, loader);
        MethodType listing = MethodType.methodType(List.class, Plugin.class, ErrorReports.class);
        CapturedLog log = new CapturedLog();
        Plugin owner = owner(installed(pluginOn(withApis(), "2.8.0"), "Vault", "ExcellentEconomy", "nightcore"), log);

        assertThat((List<?>) MethodHandles.publicLookup().findStatic(providerType, "vaultUnlockedCurrencies", listing)
            .invoke(owner, new ErrorReports())).isEmpty();
        assertThat((List<?>) MethodHandles.publicLookup().findStatic(providerType, "coinsEngineCurrencies", listing)
            .invoke(owner, new ErrorReports())).isEmpty();

        assertThat(log.lines()).isEmpty();
        verify(owner.getServer(), never()).getServicesManager();
    }

    @Test
    void theVaultUnlockedWatcherOnTheHiddenCopyHearsOnlyTheVaultUnlockedEconomy() throws Throwable {
        ClassLoader loader = hidden();
        AtomicInteger resolved = new AtomicInteger();
        Class<?> watcherType = Class.forName(EconomyWatcher.class.getName(), true, loader);
        Runnable resolve = resolved::incrementAndGet;
        BooleanSupplier stopping = () -> false;
        Object watcher = MethodHandles.publicLookup().findStatic(watcherType, "vaultUnlocked",
            MethodType.methodType(watcherType, Runnable.class, BooleanSupplier.class)).invoke(resolve, stopping);
        MethodHandle onRegister = MethodHandles.publicLookup().findVirtual(watcherType, "onRegister",
            MethodType.methodType(void.class, ServiceRegisterEvent.class));
        Plugin owner = mock(Plugin.class);
        RegisteredServiceProvider<Economy> classic = new RegisteredServiceProvider<>(Economy.class,
            mock(Economy.class), ServicePriority.Normal, owner);
        RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> unlocked = new RegisteredServiceProvider<>(
            net.milkbowl.vault2.economy.Economy.class, mock(net.milkbowl.vault2.economy.Economy.class),
            ServicePriority.Normal, owner);

        onRegister.invoke(watcher, registered(classic));
        assertThat(resolved).hasValue(0);
        onRegister.invoke(watcher, registered(unlocked));
        assertThat(resolved).hasValue(1);
        assertThatThrownBy(() -> Class.forName(VAULTUNLOCKED_ECONOMY, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void permissionDetectionOnTheHiddenCopyFindsLuckPermsWithoutItsApi() throws Throwable {
        assertThatCode(() -> Class.forName(LUCKPERMS, false, withApis())).doesNotThrowAnyException();
        ClassLoader loader = hidden();
        assertThatThrownBy(() -> Class.forName(LUCKPERMS, false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        PluginManager plugins = installed(pluginOn(loader, "5.5.17"), "LuckPerms");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> providerType = Class.forName(PermissionProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "luckPerms",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));
        MethodHandle describe = lookup.findVirtual(statusType, "describe", MethodType.methodType(String.class));

        Object detected = detect.invoke(owner(plugins, log), errors, true);

        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(describe.invoke(status.invoke(detected))).isEqualTo("LuckPerms 5.5.17 lacks the API that is read");
        assertThat(provider.invoke(detected)).isNull();
        assertThat(log.lines()).containsExactly(
            "WARNING LuckPerms 5.5.17 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @Test
    void permissionDetectionOnTheHiddenCopyNamesALuckPermsThePluginCannotSee() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = installed(pluginOn(withApis(), "5.5.17"), "LuckPerms");
        CapturedLog log = new CapturedLog();

        Class<?> providerType = Class.forName(PermissionProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        Class<?> stateType = Class.forName(HookState.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "luckPerms",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle state = lookup.findVirtual(statusType, "state", MethodType.methodType(stateType));
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Object detected = detect.invoke(owner(plugins, log), errors, true);
        Object found = status.invoke(detected);

        assertThat(((Enum<?>) state.invoke(found)).name()).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, found, "reason", String.class)).isEqualTo("LuckPerms is installed, but this plugin"
            + " cannot see it, because its paper-plugin.yml does not list LuckPerms under dependencies.server.");
        assertThat(log.lines()).containsExactly("WARNING LuckPerms 5.5.17 is not visible to this plugin, so it stays"
            + " off: this plugin's paper-plugin.yml does not list LuckPerms under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    @Test
    void bedrockDetectionOnTheHiddenCopyFindsFloodgateAndGeyserWithoutTheirApis() throws Throwable {
        for (String api : BEDROCK_API) {
            assertThatCode(() -> Class.forName(api, false, withApis()))
                .as(api)
                .doesNotThrowAnyException();
        }
        ClassLoader loader = hidden();
        for (String api : BEDROCK_API) {
            assertThatThrownBy(() -> Class.forName(api, false, loader))
                .as(api)
                .isInstanceOf(ClassNotFoundException.class);
        }
        PluginManager plugins = installed(pluginOn(loader, "2.2.5"), "floodgate", "Geyser-Spigot");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> providerType = Class.forName(BedrockProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "detect",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status",
            MethodType.methodType(Class.forName(HookStatus.class.getName(), true, loader)));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));

        Object detected = detect.invoke(owner(plugins, log), errors, true);
        Object found = status.invoke(detected);

        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(stateOf(loader, found)).isEqualTo(HookState.API_MISSING.name());
        assertThat(read(loader, found, "describe", String.class))
            .isEqualTo("floodgate 2.2.5 lacks the API that is read");
        assertThat(provider.invoke(detected)).isNull();
        assertThat(log.lines()).containsExactly(
            "WARNING floodgate 2.2.5 lacks the API that is read, so it stays off.",
            "WARNING Geyser-Spigot 2.2.5 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @Test
    void bedrockDetectionOnTheHiddenCopyNamesAFloodgateThePluginCannotSee() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = installed(pluginOn(withApis(), "2.2.5"), "floodgate");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> providerType = Class.forName(BedrockProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "detect",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status",
            MethodType.methodType(Class.forName(HookStatus.class.getName(), true, loader)));

        Object found = status.invoke(detect.invoke(owner(plugins, log), errors, true));

        assertThat(stateOf(loader, found)).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, found, "reason", String.class)).isEqualTo("floodgate is installed, but this plugin"
            + " cannot see it, because its paper-plugin.yml does not list floodgate under dependencies.server.");
        assertThat(log.lines()).containsExactly("WARNING floodgate 2.2.5 is not visible to this plugin, so it stays"
            + " off: this plugin's paper-plugin.yml does not list floodgate under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    @Test
    void regionDetectionOnTheHiddenCopyFindsWorldGuardWithoutItsApi() throws Throwable {
        for (String api : WORLDGUARD_API) {
            assertThatCode(() -> Class.forName(api, false, withApis()))
                .as(api)
                .doesNotThrowAnyException();
        }
        ClassLoader loader = hidden();
        for (String api : WORLDGUARD_API) {
            assertThatThrownBy(() -> Class.forName(api, false, loader))
                .as(api)
                .isInstanceOf(ClassNotFoundException.class);
        }
        PluginManager plugins = installed(pluginOn(loader, "7.0.20"), "WorldGuard");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> providerType = Class.forName(RegionProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        Class<?> stateType = Class.forName(HookState.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "worldGuard",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));
        MethodHandle state = lookup.findVirtual(statusType, "state", MethodType.methodType(stateType));
        MethodHandle describe = lookup.findVirtual(statusType, "describe", MethodType.methodType(String.class));

        Object detected = detect.invoke(owner(plugins, log), errors, true);
        Object found = status.invoke(detected);

        assertThat(detected.getClass().getClassLoader()).isSameAs(loader);
        assertThat(((Enum<?>) state.invoke(found)).name()).isEqualTo(HookState.API_MISSING.name());
        assertThat(describe.invoke(found)).isEqualTo("WorldGuard 7.0.20 lacks the API that is read");
        assertThat(provider.invoke(detected)).isNull();
        assertThat(log.lines()).containsExactly(
            "WARNING WorldGuard 7.0.20 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @Test
    void regionDetectionWithWorldGuardVisibleAndWorldEditHiddenIsNotVisibleAndNamesWorldEdit() throws Throwable {
        ClassLoader loader = SimulatedClasspath.hiding(OWN, "com.sk89q.worldedit.");
        assertThatCode(() -> Class.forName(WORLDGUARD, false, loader)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", false, loader))
            .isInstanceOf(ClassNotFoundException.class);
        PluginManager plugins = installed(pluginOn(loader, "7.0.20"), "WorldGuard");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));

        Class<?> providerType = Class.forName(RegionProvider.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(providerType, "worldGuard",
            MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle status = lookup.findVirtual(detectedType, "status",
            MethodType.methodType(Class.forName(HookStatus.class.getName(), true, loader)));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));

        Object detected = detect.invoke(owner(plugins, log), errors, true);
        Object found = status.invoke(detected);

        assertThat(stateOf(loader, found)).isEqualTo(HookState.NOT_VISIBLE.name());
        assertThat(read(loader, found, "reason", String.class)).isEqualTo("WorldGuard is installed, but this plugin"
            + " cannot see WorldEdit, which WorldGuard is read through, because its paper-plugin.yml does not list"
            + " WorldEdit under dependencies.server.");
        assertThat(provider.invoke(detected)).isNull();
        assertThat(log.lines()).containsExactly("WARNING WorldGuard 7.0.20 stays off, because WorldEdit, which it is"
            + " read through, is not visible to this plugin: this plugin's paper-plugin.yml does not list WorldEdit"
            + " under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    private static Object registerPlaceholders(ClassLoader loader, Plugin owner, ErrorReports errors,
                                               boolean switchedOn) throws Throwable {
        Class<?> builderType = Class.forName(PlaceholderExpansionBuilder.class.getName(), true, loader);
        Class<?> registrationType = Class.forName(PlaceholderRegistration.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle create = lookup.findConstructor(builderType, MethodType.methodType(void.class));
        MethodHandle player = lookup.findVirtual(builderType, "player",
            MethodType.methodType(builderType, String.class, Function.class));
        MethodHandle global = lookup.findVirtual(builderType, "global",
            MethodType.methodType(builderType, String.class, Supplier.class));
        MethodHandle register = lookup.findVirtual(builderType, "register",
            MethodType.methodType(registrationType, Plugin.class, ErrorReports.class, boolean.class));
        Function<Player, String> name = Player::getName;
        Supplier<String> online = () -> "5";

        Object builder = create.invoke();
        player.invoke(builder, "name", name);
        global.invoke(builder, "online", online);
        return register.invoke(builder, owner, errors, switchedOn);
    }

    private static List<String> placeholderStates(ClassLoader loader, Object registration) throws Throwable {
        List<?> statuses = (List<?>) MethodHandles.publicLookup().findVirtual(registration.getClass(), "statuses",
            MethodType.methodType(List.class)).invoke(registration);
        List<String> states = new ArrayList<>();
        for (Object status : statuses) {
            states.add(stateOf(loader, status));
        }
        return states;
    }

    private static Plugin placeholderOwner(PluginManager plugins, CapturedLog log) {
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.1");
        when(meta.getAuthors()).thenReturn(List.of("Ana"));
        Plugin owner = owner(plugins, log);
        when(owner.getName()).thenReturn("Shop");
        when(owner.getPluginMeta()).thenReturn(meta);
        return owner;
    }

    @Test
    void placeholderRegistrationOnTheHiddenCopyRegistersNothingWithoutEitherApi() throws Throwable {
        for (String api : PLACEHOLDER_API) {
            assertThatCode(() -> Class.forName(api, false, withApis()))
                .as(api)
                .doesNotThrowAnyException();
        }
        ClassLoader loader = hidden();
        for (String api : PLACEHOLDER_API) {
            assertThatThrownBy(() -> Class.forName(api, false, loader))
                .as(api)
                .isInstanceOf(ClassNotFoundException.class);
        }
        PluginManager plugins = installed(pluginOn(loader, "2.11"), "PlaceholderAPI", "MiniPlaceholders");
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));
        Class<?> registrationType = Class.forName(PlaceholderRegistration.class.getName(), true, loader);
        MethodHandle unregister = MethodHandles.publicLookup().findVirtual(registrationType, "unregister",
            MethodType.methodType(void.class));

        Object registration = registerPlaceholders(loader, placeholderOwner(plugins, log), errors, true);

        assertThat(registration.getClass().getClassLoader()).isSameAs(loader);
        assertThat(placeholderStates(loader, registration))
            .containsExactly(HookState.API_MISSING.name(), HookState.API_MISSING.name());
        assertThatCode(() -> unregister.invoke(registration)).doesNotThrowAnyException();
        assertThat(placeholderStates(loader, registration))
            .containsExactly(HookState.API_MISSING.name(), HookState.API_MISSING.name());
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI 2.11 lacks the API that is read, so it stays off.",
            "WARNING MiniPlaceholders 2.11 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
        verify(plugins).isPluginEnabled("PlaceholderAPI");
        verify(plugins).isPluginEnabled("MiniPlaceholders");
    }

    @Test
    void placeholderRegistrationOnTheHiddenCopyNamesEveryBackendThePluginCannotSee() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = installed(pluginOn(withApis(), "2.11"), "PlaceholderAPI", "MiniPlaceholders");
        CapturedLog log = new CapturedLog();

        Object registration = registerPlaceholders(loader, placeholderOwner(plugins, log), new ErrorReports(), true);

        assertThat(placeholderStates(loader, registration))
            .containsExactly(HookState.NOT_VISIBLE.name(), HookState.NOT_VISIBLE.name());
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI 2.11 is not visible to this plugin, so it stays off: this plugin's"
                + " paper-plugin.yml does not list PlaceholderAPI under dependencies.server.",
            "WARNING MiniPlaceholders 2.11 is not visible to this plugin, so it stays off: this plugin's"
                + " paper-plugin.yml does not list MiniPlaceholders under dependencies.server.");
    }

    @Test
    void hologramDetectionOnTheHiddenCopyFindsEveryHologramPluginWithoutItsApi() throws Throwable {
        ClassLoader loader = hidden();
        List<String> names = new ArrayList<>();
        for (HologramPlugin plugin : HologramPlugin.values()) {
            names.add(plugin.plugin());
        }
        PluginManager plugins = installed(pluginOn(loader, "2.0"), names.toArray(String[]::new));
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));
        Class<?> hologramPlugin = Class.forName(HologramPlugin.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(Class.forName(HologramProvider.class.getName(), true, loader),
            "detect", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class,
                hologramPlugin));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));

        List<String> states = new ArrayList<>();
        for (Object plugin : hologramPlugin.getEnumConstants()) {
            Object detected = detect.invoke(owner(plugins, log), errors, true, plugin);
            states.add(stateOf(loader, status.invoke(detected)));
            assertThat(provider.invoke(detected)).isNull();
        }

        assertThat(states).hasSize(4).containsOnly(HookState.API_MISSING.name());
        assertThat(log.lines()).containsExactly(
            "WARNING DecentHolograms 2.0 lacks the API that is read, so it stays off.",
            "WARNING FancyHolograms 2.0 lacks the API that is read, so it stays off.",
            "WARNING Holograms 2.0 lacks the API that is read, so it stays off.",
            "WARNING HologramLib 2.0 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @Test
    void npcDetectionOnTheHiddenCopyFindsEveryNpcPluginWithoutItsApiOrOutOfSight() throws Throwable {
        ClassLoader loader = hidden();
        List<String> names = new ArrayList<>();
        for (NpcPlugin plugin : NpcPlugin.values()) {
            names.add(plugin.plugin());
        }
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source));
        Class<?> npcPlugin = Class.forName(NpcPlugin.class.getName(), true, loader);
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle detect = lookup.findStatic(Class.forName(NpcProvider.class.getName(), true, loader),
            "detect", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class, npcPlugin));
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle provider = lookup.findVirtual(detectedType, "provider", MethodType.methodType(Object.class));
        PluginManager lacking = installed(pluginOn(loader, "2.0"), names.toArray(String[]::new));
        PluginManager unlisted = installed(pluginOn(withApis(), "2.0"), names.toArray(String[]::new));

        List<String> states = new ArrayList<>();
        for (PluginManager plugins : List.of(lacking, unlisted)) {
            for (Object plugin : npcPlugin.getEnumConstants()) {
                Object detected = detect.invoke(owner(plugins, log), errors, true, plugin);
                states.add(stateOf(loader, status.invoke(detected)));
                assertThat(provider.invoke(detected)).isNull();
            }
            verify(plugins, never()).registerEvents(any(Listener.class), any(Plugin.class));
        }

        assertThat(states).containsExactly(HookState.API_MISSING.name(), HookState.API_MISSING.name(),
            HookState.API_MISSING.name(), HookState.NOT_VISIBLE.name(), HookState.NOT_VISIBLE.name(),
            HookState.NOT_VISIBLE.name());
        assertThat(log.lines()).containsExactly(
            "WARNING Citizens 2.0 lacks the API that is read, so it stays off.",
            "WARNING FancyNpcs 2.0 lacks the API that is read, so it stays off.",
            "WARNING ZNPCsPlus 2.0 lacks the API that is read, so it stays off.",
            "WARNING Citizens 2.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml"
                + " does not list Citizens under dependencies.server.",
            "WARNING FancyNpcs 2.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml"
                + " does not list FancyNpcs under dependencies.server.",
            "WARNING ZNPCsPlus 2.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml"
                + " does not list ZNPCsPlus under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    @Test
    void everyEntryPointOnTheHiddenCopySwitchedOffLooksAtNothing() throws Throwable {
        ClassLoader loader = hidden();
        PluginManager plugins = mock(PluginManager.class);
        CapturedLog log = new CapturedLog();
        Plugin owner = placeholderOwner(plugins, log);
        ErrorReports errors = new ErrorReports();
        Class<?> detectedType = Class.forName(Detected.class.getName(), true, loader);
        Class<?> statusType = Class.forName(HookStatus.class.getName(), true, loader);
        Class<?> items = Class.forName(CustomItems.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle status = lookup.findVirtual(detectedType, "status", MethodType.methodType(statusType));
        MethodHandle luckPerms = lookup.findStatic(Class.forName(PermissionProvider.class.getName(), true, loader),
            "luckPerms", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle playerPoints = lookup.findStatic(Class.forName(EconomyProvider.class.getName(), true, loader),
            "playerPoints", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle worldGuard = lookup.findStatic(Class.forName(RegionProvider.class.getName(), true, loader),
            "worldGuard", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class));
        MethodHandle detect = lookup.findStatic(items, "detect",
            MethodType.methodType(items, Plugin.class, ErrorReports.class, Set.class));
        MethodHandle statuses = lookup.findVirtual(items, "statuses", MethodType.methodType(List.class));
        Class<?> hologramPlugin = Class.forName(HologramPlugin.class.getName(), true, loader);
        MethodHandle holograms = lookup.findStatic(Class.forName(HologramProvider.class.getName(), true, loader),
            "detect", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class,
                hologramPlugin));

        Class<?> npcPlugin = Class.forName(NpcPlugin.class.getName(), true, loader);
        MethodHandle npcs = lookup.findStatic(Class.forName(NpcProvider.class.getName(), true, loader),
            "detect", MethodType.methodType(detectedType, Plugin.class, ErrorReports.class, boolean.class, npcPlugin));

        List<String> states = new ArrayList<>();
        states.add(stateOf(loader, status.invoke(luckPerms.invoke(owner, errors, false))));
        states.add(stateOf(loader, status.invoke(worldGuard.invoke(owner, errors, false))));
        states.add(stateOf(loader, vaultLookup(loader, owner, errors, false)));
        states.add(stateOf(loader, status.invoke(playerPoints.invoke(owner, errors, false))));
        states.addAll(placeholderStates(loader, registerPlaceholders(loader, owner, errors, false)));
        for (Object each : (List<?>) statuses.invoke(detect.invoke(owner, errors, Set.of()))) {
            states.add(stateOf(loader, each));
        }
        for (Object plugin : hologramPlugin.getEnumConstants()) {
            states.add(stateOf(loader, status.invoke(holograms.invoke(owner, errors, false, plugin))));
        }
        for (Object plugin : npcPlugin.getEnumConstants()) {
            states.add(stateOf(loader, status.invoke(npcs.invoke(owner, errors, false, plugin))));
        }

        assertThat(states).hasSize(19).containsOnly(HookState.SWITCHED_OFF.name());
        verifyNoInteractions(plugins);
        assertThat(log.lines()).containsExactly("INFO Custom item plugins: ItemsAdder switched off; Nexo switched off;"
            + " Oraxen switched off; HeadDatabase switched off; CraftEngine switched off; MMOItems switched off.");
    }
}
