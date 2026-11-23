package so.alaz.taffy.hooks;

import io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent;
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

final class OraxenProviderTest {

    private final Plugin owner = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<String> order = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private final ItemStack ruby = mock(ItemStack.class);
    private final ItemStack dirt = mock(ItemStack.class);
    private final List<String> built = new ArrayList<>();
    private final List<String> told = new ArrayList<>();

    @BeforeEach
    void server() {
        OraxenProvider.forgetLoad();
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

    private OraxenProvider provider(Map<String, ItemStack> known, boolean builds) {
        return new OraxenProvider(
            known::containsKey,
            id -> {
                built.add(id);
                return builds ? known.get(id) : null;
            },
            item -> item == ruby ? "ruby" : null,
            () -> told.add("loaded"));
    }

    private OraxenProvider started(BooleanSupplier knowsItems) {
        return OraxenProvider.start(owner, provider(Map.of("ruby", ruby), true), knowsItems);
    }

    @Test
    void anOraxenItemGetsItsBareIdAndAnyOtherItemNone() {
        OraxenProvider provider = provider(Map.of(), true);

        assertThat(provider.id(ruby)).isEqualTo("ruby");
        assertThat(provider.id(dirt)).isNull();
    }

    @Test
    void aKnownIdGivesTheBuiltItem() {
        OraxenProvider provider = provider(Map.of("ruby", ruby), true);

        assertThat(provider.create("ruby")).isSameAs(ruby);
        assertThat(built).containsExactly("ruby");
    }

    @Test
    void anUnknownIdGivesNoItemWithoutBuilding() {
        OraxenProvider provider = provider(Map.of("ruby", ruby), true);

        assertThat(provider.create("sapphire")).isNull();
        assertThat(built).isEmpty();
    }

    @Test
    void aKnownIdWithoutABuilderGivesNoItem() {
        OraxenProvider provider = provider(Map.of("ruby", ruby), false);

        assertThat(provider.create("ruby")).isNull();
        assertThat(built).containsExactly("ruby");
    }

    @Test
    void theListenerIsRegisteredBeforeTheItemNamesAreRead() {
        OraxenProvider provider = started(() -> {
            order.add("read");
            return true;
        });

        assertThat(order).containsExactly("register", "read");
        assertThat(listeners).containsExactly((Listener) provider);
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void anOraxenThatKnowsNoItemYetBuildsFromTheLoadEvent() {
        OraxenProvider provider = started(() -> false);

        assertThat(provider.ready()).isFalse();
        provider.loaded(mock(OraxenItemsLoadedEvent.class));
        assertThat(provider.ready()).isTrue();
        assertThat(log.records).isEmpty();
    }

    @Test
    void eachLoadEventRunsTheSignalOnceTheProviderIsReady() {
        List<Boolean> readyWhenTold = new ArrayList<>();
        OraxenProvider[] provider = new OraxenProvider[1];
        provider[0] = OraxenProvider.start(owner, new OraxenProvider(id -> true, id -> ruby, item -> null,
            () -> readyWhenTold.add(provider[0].ready())), () -> false);

        assertThat(readyWhenTold).isEmpty();
        provider[0].loaded(mock(OraxenItemsLoadedEvent.class));
        provider[0].loaded(mock(OraxenItemsLoadedEvent.class));
        assertThat(readyWhenTold).containsExactly(true, true);
    }

    @Test
    void itemsKnownBeforeTheProviderStartsRunNoSignal() {
        OraxenProvider provider = started(() -> true);

        assertThat(provider.ready()).isTrue();
        assertThat(told).isEmpty();
    }

    @Test
    void aProviderStartedAfterALoadEventAnotherProviderSawBuildsWithoutReadingTheItemNames() {
        OraxenProvider first = started(() -> false);
        assertThat(first.ready()).isFalse();
        first.loaded(mock(OraxenItemsLoadedEvent.class));

        OraxenProvider second = started(() -> {
            order.add("read");
            return false;
        });

        assertThat(second.ready()).isTrue();
        assertThat(listeners).containsExactly((Listener) first, (Listener) second);
        assertThat(order).containsExactly("register", "register");
    }

    @Test
    void idsAreReadWhileOraxenIsStillLoadingItsItems() {
        OraxenProvider provider = started(() -> false);

        assertThat(provider.ready()).isFalse();
        assertThat(provider.idsReady()).isTrue();
        assertThat(provider.readsIds()).isTrue();
        assertThat(provider.id(ruby)).isEqualTo("ruby");
    }

    @Test
    void aLoadEventWhileTheItemNamesAreReadIsKept() {
        OraxenProvider provider = started(() -> {
            ((OraxenProvider) listeners.getFirst()).loaded(mock(OraxenItemsLoadedEvent.class));
            return false;
        });

        assertThat(provider.ready()).isTrue();
    }

    @Test
    void itemNamesThatCannotBeLinkedWaitForTheEvent() {
        OraxenProvider provider = started(() -> {
            throw new NoSuchMethodError("getNames");
        });

        assertThat(provider.ready()).isFalse();
        assertThat(log.lines()).containsExactly("WARNING Oraxen does not say which items it has loaded"
            + " (java.lang.NoSuchMethodError: getNames), so Oraxen items are built only after OraxenItemsLoadedEvent.");
        provider.loaded(mock(OraxenItemsLoadedEvent.class));
        assertThat(provider.ready()).isTrue();
    }

    @Test
    void itemNamesThatThrowFailTheStartAndUnregisterTheListener() {
        IllegalStateException broken = new IllegalStateException("broken");

        try (MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
            assertThatThrownBy(() -> started(() -> {
                throw broken;
            })).isSameAs(broken);

            assertThat(listeners).singleElement().isInstanceOf(OraxenProvider.class);
            handlers.verify(() -> HandlerList.unregisterAll(listeners.getFirst()));
        }
        assertThat(log.records).isEmpty();
    }

    @Test
    void aMissingLoadEventFailsTheStartBeforeTheListenerIsRegistered() throws ReflectiveOperationException {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", OraxenItemsLoadedEvent.class.getName());
        Class<?> hidden = Class.forName(OraxenProvider.class.getName(), true, loader);
        Class<?> hiddenProvider = Class.forName(CustomItemProvider.class.getName(), false, loader);
        // getDeclaredMethod would resolve the parameter type of every declared method, the event handler included.
        MethodHandle create = MethodHandles.privateLookupIn(hidden, MethodHandles.lookup())
            .findStatic(hidden, "create", MethodType.methodType(hiddenProvider, Plugin.class, Runnable.class));

        assertThat(hidden.getClassLoader()).isSameAs(loader);
        assertThatThrownBy(hidden::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("OraxenItemsLoadedEvent");
        assertThatThrownBy(() -> create.invoke(owner, (Runnable) () -> { }))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("OraxenItemsLoadedEvent");
        verify(manager, never()).registerEvents(any(Listener.class), eq(owner));
    }

    // OraxenItems cannot initialize without the Oraxen plugin, so its item names cannot be read here.
    @Test
    void theFactoryLinksTheApiAndRegistersTheListener() {
        CustomItemProvider provider = OraxenProvider.create(owner, () -> { });

        assertThat(provider).isInstanceOf(OraxenProvider.class);
        assertThat(listeners).containsExactly((Listener) provider);
        assertThat(provider.ready()).isFalse();
        assertThat(provider.idsReady()).isTrue();
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Oraxen does not say which items it has loaded (")
            .endsWith("), so Oraxen items are built only after OraxenItemsLoadedEvent.");
    }
}
