package so.alaz.taffy.hooks;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.event.MMOItemsReloadEvent;
import org.bukkit.Server;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

final class MMOItemsProviderTest {

    private final Plugin owner = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<Listener> listeners = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private final ItemStack cutlass = mock(ItemStack.class);
    private final ItemStack dirt = mock(ItemStack.class);
    private final Map<ItemStack, String> types = new HashMap<>();
    private final Map<ItemStack, String> ids = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> told = new ArrayList<>();

    @BeforeEach
    void server() {
        Server server = mock(Server.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(manager);
        doAnswer(call -> listeners.add(call.getArgument(0)))
            .when(manager).registerEvents(any(Listener.class), eq(owner));
        types.put(cutlass, "SWORD");
        ids.put(cutlass, "CUTLASS");
    }

    private MMOItemsProvider provider() {
        return new MMOItemsProvider(
            (type, id) -> {
                asked.add(type + " " + id);
                return type.equals("SWORD") && id.equals("CUTLASS") ? cutlass : null;
            },
            item -> {
                asked.add("type");
                return types.get(item);
            },
            item -> {
                asked.add("id");
                return ids.get(item);
            },
            () -> told.add("reloading"));
    }

    @Test
    void anIdIsSplitAtItsFirstColonIntoTheTypeAndTheId() {
        MMOItemsProvider provider = provider();

        assertThat(provider.create("SWORD:CUTLASS")).isSameAs(cutlass);
        assertThat(provider.create("SWORD:BLADES:CUTLASS")).isNull();
        assertThat(asked).containsExactly("SWORD CUTLASS", "SWORD BLADES:CUTLASS");
    }

    @Test
    void anUnknownTypeOrIdOrOneInAnotherCaseGivesNoItem() {
        MMOItemsProvider provider = provider();

        assertThat(provider.create("SWORD:KATANA")).isNull();
        assertThat(provider.create("STAFF:CUTLASS")).isNull();
        assertThat(provider.create("sword:cutlass")).isNull();
        assertThat(asked).containsExactly("SWORD KATANA", "STAFF CUTLASS", "sword cutlass");
    }

    @Test
    void anIdWithoutATypeOrWithoutAnIdGivesNoItemAndMmoItemsIsNotAsked() {
        MMOItemsProvider provider = provider();

        assertThat(provider.create("CUTLASS")).isNull();
        assertThat(provider.create(":CUTLASS")).isNull();
        assertThat(provider.create("SWORD:")).isNull();
        assertThat(provider.create(":")).isNull();
        assertThat(asked).isEmpty();
    }

    @Test
    void anMmoItemsItemGetsItsTypeAndIdAndAnItemWithoutATypeIsNotAskedForItsId() {
        MMOItemsProvider provider = provider();

        assertThat(provider.id(cutlass)).isEqualTo("SWORD:CUTLASS");
        assertThat(asked).containsExactly("type", "id");

        asked.clear();
        assertThat(provider.id(dirt)).isNull();
        assertThat(asked).containsExactly("type");
    }

    @Test
    void anItemWithAnEmptyTypeOrWithoutAnIdIsNoMmoItemsItem() {
        MMOItemsProvider provider = provider();
        ItemStack untyped = mock(ItemStack.class);
        ItemStack unnamed = mock(ItemStack.class);
        ItemStack blank = mock(ItemStack.class);
        types.put(untyped, "");
        ids.put(untyped, "CUTLASS");
        types.put(unnamed, "SWORD");
        types.put(blank, "SWORD");
        ids.put(blank, "");

        assertThat(provider.id(untyped)).isNull();
        assertThat(provider.id(unnamed)).isNull();
        assertThat(provider.id(blank)).isNull();
    }

    @Test
    void theProviderIsReadyAndReadsIdsFromTheStart() {
        MMOItemsProvider provider = provider();

        assertThat(provider.ready()).isTrue();
        assertThat(provider.idsReady()).isTrue();
        assertThat(provider.readsIds()).isTrue();
    }

    @Test
    void eachReloadEventRunsTheSignal() {
        MMOItemsProvider provider = provider();

        assertThat(told).isEmpty();
        provider.reloading(mock(MMOItemsReloadEvent.class));
        provider.reloading(mock(MMOItemsReloadEvent.class));
        assertThat(told).containsExactly("reloading", "reloading");
    }

    @Test
    void aMissingReloadEventFailsTheStartBeforeTheListenerIsRegistered() throws ReflectiveOperationException {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", MMOItemsReloadEvent.class.getName());
        Class<?> hidden = Class.forName(MMOItemsProvider.class.getName(), true, loader);
        Class<?> hiddenProvider = Class.forName(CustomItemProvider.class.getName(), false, loader);
        // getDeclaredMethod would resolve the parameter type of every declared method, the event handler included.
        MethodHandle create = MethodHandles.privateLookupIn(hidden, MethodHandles.lookup())
            .findStatic(hidden, "create", MethodType.methodType(hiddenProvider, Plugin.class, Runnable.class));

        assertThat(hidden.getClassLoader()).isSameAs(loader);
        assertThatThrownBy(hidden::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("MMOItemsReloadEvent");
        assertThatThrownBy(() -> create.invoke(owner, (Runnable) () -> { }))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("MMOItemsReloadEvent");
        verify(manager, never()).registerEvents(any(Listener.class), eq(owner));
    }

    @Test
    void theFactoryBuildsThroughGetItemAndReadsTheTypeAndTheIdFromTheItem() {
        MMOItems plugin = mock(MMOItems.class);
        when(plugin.getItem("SWORD", "CUTLASS")).thenReturn(cutlass);
        MMOItems before = MMOItems.plugin;
        MMOItems.plugin = plugin;

        try (MockedStatic<MMOItems> statics = mockStatic(MMOItems.class)) {
            statics.when(() -> MMOItems.getTypeName(cutlass)).thenReturn("SWORD");
            statics.when(() -> MMOItems.getID(cutlass)).thenReturn("CUTLASS");

            CustomItemProvider provider = MMOItemsProvider.create(owner, () -> { });

            assertThat(provider).isInstanceOf(MMOItemsProvider.class);
            assertThat(listeners).containsExactly((Listener) provider);
            assertThat(provider.ready()).isTrue();
            assertThat(provider.create("SWORD:CUTLASS")).isSameAs(cutlass);
            assertThat(provider.create("SWORD:KATANA")).isNull();
            assertThat(provider.id(cutlass)).isEqualTo("SWORD:CUTLASS");
            assertThat(provider.id(dirt)).isNull();
        } finally {
            MMOItems.plugin = before;
        }
        assertThat(log.records).isEmpty();
    }
}
