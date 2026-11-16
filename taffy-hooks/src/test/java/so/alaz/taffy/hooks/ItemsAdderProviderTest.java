package so.alaz.taffy.hooks;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
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

@SuppressWarnings("deprecation")
final class ItemsAdderProviderTest {

    private final Plugin owner = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<String> order = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private MockedStatic<ItemsAdder> itemsAdder;
    private MockedStatic<CustomStack> stacks;

    @BeforeEach
    void server() {
        Server server = mock(Server.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(manager);
        doAnswer(call -> {
            order.add("register");
            listeners.add(call.getArgument(0));
            return null;
        }).when(manager).registerEvents(any(Listener.class), eq(owner));
        itemsAdder = mockStatic(ItemsAdder.class);
        stacks = mockStatic(CustomStack.class);
    }

    @AfterEach
    void close() {
        itemsAdder.close();
        stacks.close();
    }

    @Test
    void theListenerIsRegisteredBeforeTheLoadedCheck() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenAnswer(call -> {
            order.add("read");
            return true;
        });

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(order).containsExactly("register", "read");
        assertThat(listeners).containsExactly((Listener) provider);
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void itemsNotYetLoadedAreReadyFromTheLoadEvent() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(false);

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(provider.ready()).isFalse();
        ((ItemsAdderProvider) provider).loaded(mock(ItemsAdderLoadDataEvent.class));
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void aLoadEventDuringTheLoadedCheckIsKept() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenAnswer(call -> {
            ((ItemsAdderProvider) listeners.getFirst()).loaded(mock(ItemsAdderLoadDataEvent.class));
            return false;
        });

        assertThat(ItemsAdderProvider.create(owner).ready()).isTrue();
    }

    @Test
    void anApiWithoutTheLoadedCheckWaitsForTheEvent() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenThrow(new NoSuchMethodError("areItemsLoaded"));

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(provider.ready()).isFalse();
        assertThat(log.lines()).containsExactly("WARNING ItemsAdder does not say whether its items are loaded"
            + " (java.lang.NoSuchMethodError: areItemsLoaded), so ItemsAdder item ids are read only after"
            + " ItemsAdderLoadDataEvent.");
        ((ItemsAdderProvider) provider).loaded(mock(ItemsAdderLoadDataEvent.class));
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void aLoadedCheckThatThrowsFailsTheStartAndUnregistersTheListener() {
        IllegalStateException broken = new IllegalStateException("broken");
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenThrow(broken);

        try (MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
            assertThatThrownBy(() -> ItemsAdderProvider.create(owner)).isSameAs(broken);

            assertThat(listeners).singleElement().isInstanceOf(ItemsAdderProvider.class);
            handlers.verify(() -> HandlerList.unregisterAll(listeners.getFirst()));
        }
        assertThat(log.records).isEmpty();
    }

    @Test
    void aMissingLoadEventFailsTheStartBeforeTheListenerIsRegistered() throws ReflectiveOperationException {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(false);
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.",
            ItemsAdderLoadDataEvent.class.getName());
        Class<?> hidden = Class.forName(ItemsAdderProvider.class.getName(), true, loader);
        Class<?> hiddenProvider = Class.forName(CustomItemProvider.class.getName(), false, loader);
        // getDeclaredMethod would resolve the parameter type of every declared method, the event handler included.
        MethodHandle create = MethodHandles.privateLookupIn(hidden, MethodHandles.lookup())
            .findStatic(hidden, "create", MethodType.methodType(hiddenProvider, Plugin.class));

        assertThat(hidden.getClassLoader()).isSameAs(loader);
        assertThat(hiddenProvider).isNotSameAs(CustomItemProvider.class);
        assertThatThrownBy(hidden::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("ItemsAdderLoadDataEvent");
        assertThatThrownBy(() -> create.invoke(owner))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("ItemsAdderLoadDataEvent");
        verify(manager, never()).registerEvents(any(Listener.class), eq(owner));
    }

    @Test
    void anItemsAdderItemGetsItsNamespacedIdAndAnyOtherItemNone() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(true);
        ItemStack coin = mock(ItemStack.class);
        ItemStack dirt = mock(ItemStack.class);
        CustomStack custom = mock(CustomStack.class);
        when(custom.getNamespacedID()).thenReturn("shop:coin");
        stacks.when(() -> CustomStack.byItemStack(coin)).thenReturn(custom);
        stacks.when(() -> CustomStack.byItemStack(dirt)).thenReturn(null);

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(provider.id(coin)).isEqualTo("shop:coin");
        assertThat(provider.id(dirt)).isNull();
    }

    @Test
    void aKnownIdGivesTheItemStackOfItsCustomStack() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(true);
        ItemStack coin = mock(ItemStack.class);
        CustomStack custom = mock(CustomStack.class);
        when(custom.getItemStack()).thenReturn(coin);
        stacks.when(() -> CustomStack.getInstance("shop:coin")).thenReturn(custom);

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(provider.create("shop:coin")).isSameAs(coin);
        stacks.verify(() -> CustomStack.getInstance("shop:coin"));
    }

    @Test
    void anUnknownIdGivesNoItem() {
        itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(true);
        stacks.when(() -> CustomStack.getInstance("shop:nothing")).thenReturn(null);

        CustomItemProvider provider = ItemsAdderProvider.create(owner);

        assertThat(provider.create("shop:nothing")).isNull();
        stacks.verify(() -> CustomStack.getInstance("shop:nothing"));
    }
}
