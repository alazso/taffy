package so.alaz.taffy.hooks;

import me.arcaniax.hdb.api.DatabaseLoadEvent;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
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
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class HeadDatabaseProviderTest {

    private final Plugin owner = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<String> order = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private final ItemStack head = mock(ItemStack.class);

    @BeforeEach
    void server() {
        HeadDatabaseProvider.forgetLoad();
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

    @Test
    void aKnownIdGivesItsHead() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("1234")).thenReturn(true);
            when(api.getItemHead("1234")).thenReturn(head);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(provider.create("1234")).isSameAs(head);
            assertThat(apis.constructed()).hasSize(2);
        }
    }

    @Test
    void anUnknownIdGivesNoHeadWithoutAskingForOne() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("9999")).thenReturn(false);
            when(api.getItemHead("9999")).thenReturn(head);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(provider.create("9999")).isNull();
            HeadDatabaseAPI api = apis.constructed().getLast();
            verify(api).isHead("9999");
            verify(api, never()).getItemHead("9999");
        }
    }

    @Test
    void theIdIsPassedAsGiven() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("hdb:1234")).thenReturn(false);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(provider.create("hdb:1234")).isNull();
            verify(apis.constructed().getLast()).isHead("hdb:1234");
        }
    }

    @Test
    void noItemIsReadBackAsAHead() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(provider.readsIds()).isFalse();
            assertThat(provider.id(head)).isNull();
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void theListenerIsRegisteredBeforeTheRandomHeadIsAskedFor() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) ->
            when(api.getRandomHead()).thenAnswer(call -> {
                order.add("read");
                return head;
            }))) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(order).containsExactly("register", "read");
            assertThat(listeners).containsExactly((Listener) provider);
            assertThat(provider.ready()).isTrue();
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void aDatabaseThatGivesNoRandomHeadBuildsFromTheLoadEvent() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(provider.ready()).isFalse();
            ((HeadDatabaseProvider) provider).loaded(new DatabaseLoadEvent(3));
            assertThat(provider.ready()).isTrue();
            verify(apis.constructed().getFirst()).getRandomHead();
        }
        assertThat(log.records).isEmpty();
    }

    @Test
    void eachLoadEventRunsTheSignalOnceTheProviderIsReady() {
        List<Boolean> readyWhenTold = new ArrayList<>();
        CustomItemProvider[] provider = new CustomItemProvider[1];
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            provider[0] = HeadDatabaseProvider.create(owner, () -> readyWhenTold.add(provider[0].ready()));

            assertThat(readyWhenTold).isEmpty();
            ((HeadDatabaseProvider) provider[0]).loaded(new DatabaseLoadEvent(3));
            ((HeadDatabaseProvider) provider[0]).loaded(new DatabaseLoadEvent(4));
            assertThat(readyWhenTold).containsExactly(true, true);
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void aDatabaseThatAlreadyHoldsAHeadRunsNoSignal() {
        List<String> told = new ArrayList<>();
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) ->
            when(api.getRandomHead()).thenReturn(head))) {
            CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> told.add("loaded"));

            assertThat(provider.ready()).isTrue();
            assertThat(told).isEmpty();
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void aProviderCreatedAfterALoadEventAnotherProviderSawBuildsWithoutAskingForARandomHead() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            CustomItemProvider first = HeadDatabaseProvider.create(owner, () -> { });
            assertThat(first.ready()).isFalse();
            ((HeadDatabaseProvider) first).loaded(new DatabaseLoadEvent(0));

            CustomItemProvider second = HeadDatabaseProvider.create(owner, () -> { });

            assertThat(second.ready()).isTrue();
            assertThat(listeners).containsExactly((Listener) first, (Listener) second);
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void aLoadEventWhileTheRandomHeadIsAskedForIsKept() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) ->
            when(api.getRandomHead()).thenAnswer(call -> {
                ((HeadDatabaseProvider) listeners.getFirst()).loaded(new DatabaseLoadEvent(3));
                return null;
            }))) {
            assertThat(HeadDatabaseProvider.create(owner, () -> { }).ready()).isTrue();
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void aRandomHeadThatThrowsWaitsForTheLoadEvent() {
        for (Throwable thrown : List.of(new IllegalArgumentException("bound must be positive"),
            new NoSuchMethodError("getRandomHead"))) {
            listeners.clear();
            HeadDatabaseProvider.forgetLoad();
            try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) ->
                when(api.getRandomHead()).thenThrow(thrown))) {
                CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

                assertThat(provider.ready()).isFalse();
                assertThat(listeners).containsExactly((Listener) provider);
                ((HeadDatabaseProvider) provider).loaded(new DatabaseLoadEvent(3));
                assertThat(provider.ready()).isTrue();
                assertThat(apis.constructed()).hasSize(1);
            }
        }
        assertThat(log.records).isEmpty();
    }

    @Test
    void aMissingLoadEventFailsTheStartBeforeTheListenerIsRegistered() throws ReflectiveOperationException {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", DatabaseLoadEvent.class.getName());
        Class<?> hidden = Class.forName(HeadDatabaseProvider.class.getName(), true, loader);
        Class<?> hiddenProvider = Class.forName(CustomItemProvider.class.getName(), false, loader);
        // getDeclaredMethod would resolve the parameter type of every declared method, the event handler included.
        MethodHandle create = MethodHandles.privateLookupIn(hidden, MethodHandles.lookup())
            .findStatic(hidden, "create", MethodType.methodType(hiddenProvider, Plugin.class, Runnable.class));

        assertThat(hidden.getClassLoader()).isSameAs(loader);
        assertThatThrownBy(hidden::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("DatabaseLoadEvent");
        assertThatThrownBy(() -> create.invoke(owner, (Runnable) () -> { }))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("DatabaseLoadEvent");
        verify(manager, never()).registerEvents(any(Listener.class), eq(owner));
    }

    // The HeadDatabase-API jar holds stubs, and its getRandomHead gives no head.
    @Test
    void theFactoryLinksTheApiAndRegistersTheListener() {
        CustomItemProvider provider = HeadDatabaseProvider.create(owner, () -> { });

        assertThat(provider).isInstanceOf(HeadDatabaseProvider.class);
        assertThat(listeners).containsExactly((Listener) provider);
        assertThat(provider.ready()).isFalse();
        assertThat(log.records).isEmpty();
    }
}
