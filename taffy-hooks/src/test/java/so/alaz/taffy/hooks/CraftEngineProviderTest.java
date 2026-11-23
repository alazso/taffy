package so.alaz.taffy.hooks;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.SimulatedClasspath;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class CraftEngineProviderTest {

    private final Plugin owner = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<String> order = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private final ItemStack topaz = mock(ItemStack.class);
    private final ItemStack dirt = mock(ItemStack.class);
    private final List<String> asked = new ArrayList<>();
    private final List<String> told = new ArrayList<>();

    @BeforeEach
    void server() {
        CraftEngineProvider.forgetLoad();
        Server server = mock(Server.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(manager);
        doAnswer(call -> {
            order.add("register");
            listeners.add(call.getArgument(0));
            return null;
        }).when(manager).registerEvents(any(Listener.class), eq(owner));
    }

    private CraftEngineProvider provider(Map<String, ItemStack> known) {
        return new CraftEngineProvider(
            (namespace, value) -> {
                asked.add(namespace + " " + value);
                return known.get(namespace + ":" + value);
            },
            item -> item == topaz ? "default:topaz" : null,
            () -> told.add("loaded"));
    }

    private CraftEngineProvider started(BooleanSupplier fullyLoaded) {
        return CraftEngineProvider.start(owner, provider(Map.of("default:topaz", topaz)), fullyLoaded);
    }

    @Test
    void anIdIsSplitAtItsFirstColonIntoTheNamespaceAndTheId() {
        CraftEngineProvider provider = provider(Map.of("default:topaz", topaz));

        assertThat(provider.create("default:topaz")).isSameAs(topaz);
        assertThat(provider.create("default:gems:topaz")).isNull();
        assertThat(asked).containsExactly("default topaz", "default gems:topaz");
    }

    @Test
    void anUnknownIdGivesNoItem() {
        CraftEngineProvider provider = provider(Map.of("default:topaz", topaz));

        assertThat(provider.create("default:opal")).isNull();
        assertThat(asked).containsExactly("default opal");
    }

    @Test
    void anIdWithoutANamespaceOrWithoutAnIdGivesNoItemAndCraftEngineIsNotAsked() {
        CraftEngineProvider provider = provider(Map.of("default:topaz", topaz));

        assertThat(provider.create("topaz")).isNull();
        assertThat(provider.create(":topaz")).isNull();
        assertThat(provider.create("default:")).isNull();
        assertThat(provider.create(":")).isNull();
        assertThat(asked).isEmpty();
    }

    @Test
    void aCraftEngineItemGetsItsNamespacedIdAndAnyOtherItemNone() {
        CraftEngineProvider provider = provider(Map.of());

        assertThat(provider.id(topaz)).isEqualTo("default:topaz");
        assertThat(provider.id(dirt)).isNull();
        assertThat(provider.readsIds()).isTrue();
    }

    @Test
    void theListenerIsRegisteredBeforeTheFlagIsRead() {
        CraftEngineProvider provider = started(() -> {
            order.add("read");
            return true;
        });

        assertThat(order).containsExactly("register", "read");
        assertThat(listeners).containsExactly((Listener) provider);
        assertThat(provider.ready()).isTrue();
        assertThat(told).isEmpty();
    }

    @Test
    void aCraftEngineThatIsNotFullyLoadedBuildsFromTheReloadEvent() {
        CraftEngineProvider provider = started(() -> false);

        assertThat(provider.ready()).isFalse();
        provider.loaded(mock(CraftEngineReloadEvent.class));
        assertThat(provider.ready()).isTrue();
        assertThat(log.records).isEmpty();
    }

    @Test
    void eachReloadEventRunsTheSignalOnceTheProviderIsReady() {
        List<Boolean> readyWhenTold = new ArrayList<>();
        CraftEngineProvider[] provider = new CraftEngineProvider[1];
        provider[0] = CraftEngineProvider.start(owner, new CraftEngineProvider((namespace, value) -> topaz,
            item -> null, () -> readyWhenTold.add(provider[0].ready())), () -> false);

        assertThat(readyWhenTold).isEmpty();
        provider[0].loaded(mock(CraftEngineReloadEvent.class));
        provider[0].loaded(mock(CraftEngineReloadEvent.class));
        assertThat(readyWhenTold).containsExactly(true, true);
    }

    @Test
    void aProviderStartedAfterAReloadEventAnotherProviderSawBuildsWithoutReadingTheFlag() {
        CraftEngineProvider first = started(() -> false);
        first.loaded(mock(CraftEngineReloadEvent.class));

        CraftEngineProvider second = started(() -> {
            order.add("read");
            return false;
        });

        assertThat(second.ready()).isTrue();
        assertThat(listeners).containsExactly((Listener) first, (Listener) second);
        assertThat(order).containsExactly("register", "register");
    }

    @Test
    void idsAreReadWhileCraftEngineIsStillLoadingItsItems() {
        CraftEngineProvider provider = started(() -> false);

        assertThat(provider.ready()).isFalse();
        assertThat(provider.idsReady()).isTrue();
        assertThat(provider.id(topaz)).isEqualTo("default:topaz");
    }

    @Test
    void aReloadEventWhileTheFlagIsReadIsKept() {
        CraftEngineProvider provider = started(() -> {
            ((CraftEngineProvider) listeners.getFirst()).loaded(mock(CraftEngineReloadEvent.class));
            return false;
        });

        assertThat(provider.ready()).isTrue();
    }

    @Test
    void aCraftEngineWithoutTheFlagWaitsForTheEvent() {
        CraftEngineProvider provider = started(() -> {
            throw new NoSuchMethodError("isFullyLoaded");
        });

        assertThat(provider.ready()).isFalse();
        assertThat(log.lines()).containsExactly("WARNING CraftEngine does not say whether it is fully loaded"
            + " (java.lang.NoSuchMethodError: isFullyLoaded), so CraftEngine items are built only after"
            + " CraftEngineReloadEvent.");
        provider.loaded(mock(CraftEngineReloadEvent.class));
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void aFlagThatThrowsFailsTheStartAndUnregistersTheListener() {
        IllegalStateException broken = new IllegalStateException("CraftEngine has not been initialized");

        try (MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
            assertThatThrownBy(() -> started(() -> {
                throw broken;
            })).isSameAs(broken);

            assertThat(listeners).singleElement().isInstanceOf(CraftEngineProvider.class);
            handlers.verify(() -> HandlerList.unregisterAll(listeners.getFirst()));
        }
        assertThat(log.records).isEmpty();
    }

    @Test
    void aMissingReloadEventFailsTheStartBeforeTheListenerIsRegistered() throws ReflectiveOperationException {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.",
            CraftEngineReloadEvent.class.getName());
        Class<?> hidden = Class.forName(CraftEngineProvider.class.getName(), true, loader);
        Class<?> hiddenProvider = Class.forName(CustomItemProvider.class.getName(), false, loader);
        // getDeclaredMethod would resolve the parameter type of every declared method, the event handler included.
        MethodHandle create = MethodHandles.privateLookupIn(hidden, MethodHandles.lookup())
            .findStatic(hidden, "create", MethodType.methodType(hiddenProvider, Plugin.class, Runnable.class));

        assertThat(hidden.getClassLoader()).isSameAs(loader);
        assertThatThrownBy(hidden::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("CraftEngineReloadEvent");
        assertThatThrownBy(() -> create.invoke(owner, (Runnable) () -> { }))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("CraftEngineReloadEvent");
        verify(manager, never()).registerEvents(any(Listener.class), eq(owner));
    }

    // CraftEngine cannot initialize without its own libraries, so its flag cannot be read here.
    @Test
    void theFactoryBuildsThroughByIdAndReadsTheIdThroughGetCustomItemId() {
        BukkitItemDefinition definition = mock(BukkitItemDefinition.class);
        when(definition.buildBukkitItem()).thenReturn(topaz);

        try (MockedStatic<CraftEngineItems> items = mockStatic(CraftEngineItems.class)) {
            items.when(() -> CraftEngineItems.byId(Key.of("default", "topaz"))).thenReturn(definition);
            items.when(() -> CraftEngineItems.getCustomItemId(topaz)).thenReturn(Key.of("default", "topaz"));

            CustomItemProvider provider = CraftEngineProvider.create(owner, () -> { });

            assertThat(provider).isInstanceOf(CraftEngineProvider.class);
            assertThat(listeners).containsExactly((Listener) provider);
            assertThat(provider.ready()).isFalse();
            assertThat(provider.idsReady()).isTrue();
            assertThat(provider.create("default:topaz")).isSameAs(topaz);
            assertThat(provider.create("default:opal")).isNull();
            assertThat(provider.id(topaz)).isEqualTo("default:topaz");
            assertThat(provider.id(dirt)).isNull();
        }
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING CraftEngine does not say whether it is fully loaded (")
            .endsWith("), so CraftEngine items are built only after CraftEngineReloadEvent.");
    }
}
