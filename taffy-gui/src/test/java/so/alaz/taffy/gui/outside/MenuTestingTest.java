package so.alaz.taffy.gui.outside;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.gui.Answer;
import so.alaz.taffy.gui.Frame;
import so.alaz.taffy.gui.Icon;
import so.alaz.taffy.gui.ItemLayout;
import so.alaz.taffy.gui.Menu;
import so.alaz.taffy.gui.MenuKind;
import so.alaz.taffy.gui.MenuLayout;
import so.alaz.taffy.gui.MenuSession;
import so.alaz.taffy.gui.MenuSettings;
import so.alaz.taffy.gui.MenuTesting;
import so.alaz.taffy.gui.Menus;
import so.alaz.taffy.gui.PromptRequest;
import so.alaz.taffy.gui.Prompts;
import so.alaz.taffy.gui.Route;
import so.alaz.taffy.gui.TextSource;
import so.alaz.taffy.platform.EntityThread;
import so.alaz.taffy.platform.ErrorReports;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// A plugin's menu test: it names only public types of so.alaz.taffy.gui and mocks the plugin and the player itself.
// The shop menu shows a buy button in slot 13 whose stack amount is the menu's state; a click adds one and redraws.
final class MenuTestingTest {

    private static final MenuKind SHOP = MenuKind.of("shop", false, "buy");
    private static final Duration SETTLED = MenuSettings.DEFAULTS.clickDebounce().plusMillis(1);

    private final Queue<Runnable> ticks = new ArrayDeque<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Boolean> closes = new ArrayList<>();
    private final MenuTesting testing;
    private final Menus menus;
    private Consumer<InventoryClickEvent> duringBuy = event -> {
    };
    private @Nullable InventoryClickEvent delivering;

    MenuTestingTest() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Shop");
        when(plugin.namespace()).thenReturn("shop");
        when(plugin.getServer()).thenReturn(mock(Server.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        TextSource text = (locale, key, placeholders) -> Component.text(key);
        EntityThread entity = new EntityThread() {
            @Override
            public <T> CompletableFuture<T> call(UUID player, Function<Player, T> task) {
                return CompletableFuture.completedFuture(task.apply(online.get(player)));
            }
        };
        MenuLayout layout = MenuLayout.of(SHOP.id(), 3)
            .items(List.of(ItemLayout.of("buy", List.of(13), Material.DIAMOND).action("buy")));
        testing = new MenuTesting(Menus.Parts.of(plugin, text, (player, task) -> ticks.add(task), entity,
            new ErrorReports()).layouts(Map.of(SHOP.id(), layout)));
        menus = testing.menus();
        menus.register(new Menu<Integer>() {
            @Override
            public MenuKind kind() {
                return SHOP;
            }

            @Override
            public Integer initial(Player viewer) {
                return 1;
            }

            @Override
            public void render(Frame<Integer> frame) {
                MenuSession<Integer> session = frame.session();
                frame.action("buy", Frame.Spec.click(click -> {
                    duringBuy.accept(delivering);
                    session.state(session.state() + 1);
                    menus.rerenderLater(session);
                }).amount(frame.state()));
            }

            @Override
            public void closed(MenuSession<Integer> session, boolean left) {
                closes.add(left);
            }
        });
    }

    @Test
    void anOpenedMenuShowsItsIconsAndTitleInAFakeInventory() {
        Viewer alex = new Viewer();

        menus.open(alex.player, SHOP.id(), null);

        Inventory top = alex.top();
        assertThat(testing.inventories()).containsExactly(top);
        assertThat(top.getSize()).isEqualTo(27);
        assertThat(top.getHolder()).isSameAs(menus.session(alex.id).orElseThrow());
        assertThat(testing.title(top)).isEqualTo(Component.text("gui.shop.title"));
        assertThat(testing.icon(top, 13)).isInstanceOfSatisfying(Icon.Button.class, button -> {
            assertThat(button.material()).isEqualTo(Material.DIAMOND);
            assertThat(button.amount()).isEqualTo(1);
            assertThat(PlainTextComponentSerializer.plainText().serialize(button.name()))
                .isEqualTo("gui.shop.buy.name");
        });
        assertThat(testing.icon(top, 0)).isInstanceOf(Icon.Filler.class);
    }

    @Test
    void aClickReachesItsButtonOnlyOnceTheTestMovedTheClockPastTheOpeningWindow() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);

        InventoryClickEvent early = alex.click(13);
        tick();

        assertThat(early.isCancelled()).isTrue();
        assertThat(amount(alex)).isEqualTo(1);

        testing.advance(SETTLED);
        InventoryClickEvent settled = alex.click(13);
        tick();

        assertThat(settled.isCancelled()).isTrue();
        assertThat(amount(alex)).isEqualTo(2);
    }

    @Test
    void aClickIsCancelledAgainAtTheHighestPriorityAfterTheButtonRan() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);
        testing.advance(SETTLED);
        List<Boolean> seen = new ArrayList<>();
        duringBuy = event -> {
            seen.add(event.isCancelled());
            event.setCancelled(false);
        };

        InventoryClickEvent click = alex.click(13);

        assertThat(seen).containsExactly(true);
        assertThat(click.isCancelled()).isTrue();
    }

    @Test
    void aDragInAMenuIsCancelled() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);
        ItemStack cursor = mock(ItemStack.class);
        InventoryDragEvent drag = new InventoryDragEvent(alex.view, null, cursor, false, Map.of(13, cursor));

        testing.deliver(drag);

        assertThat(drag.isCancelled()).isTrue();
    }

    @Test
    void aDeliveredCloseEndsTheSessionAsTheViewerLeaving() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);

        alex.close(InventoryCloseEvent.Reason.PLAYER);

        assertThat(closes).containsExactly(true);
        assertThat(menus.session(alex.id)).isEmpty();
    }

    @Test
    void aDeliveredQuitForgetsTheSession() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);

        testing.deliver(new PlayerQuitEvent(alex.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(menus.session(alex.id)).isEmpty();
    }

    @Test
    void aChatLineAnswersAPromptAndOpensTheNextMenu() {
        Viewer alex = new Viewer();
        Prompts prompts = testing.prompts((task, delay) -> timers.add(task));
        menus.open(alex.player, SHOP.id(), null);
        Route origin = menus.session(alex.id).orElseThrow().route();

        prompts.ask(alex.player, new PromptRequest(Component.text("How many?"), "", origin,
            line -> new Answer.Accept(new Route(SHOP.id(), Integer.parseInt(line), null))));

        assertThat(closes).containsExactly(false);
        assertThat(alex.view).isNull();
        assertThat(alex.said).containsExactly(Prompts.QUESTION, Prompts.HINT + " " + Prompts.CANCEL_BUTTON);
        assertThat(testing.chat(alex.player, " 7 ")).isTrue();
        assertThat(amount(alex)).isEqualTo(7);
        assertThat(testing.inventories()).hasSize(2);
        assertThat(testing.chat(alex.player, "hello")).isFalse();
    }

    @Test
    void anAskTimesOutOnceTheTestMovedTheClockAndRanTheTimer() {
        Viewer alex = new Viewer();
        Prompts prompts = testing.prompts((task, delay) -> timers.add(task));
        menus.open(alex.player, SHOP.id(), null);
        Route origin = menus.session(alex.id).orElseThrow().route();
        prompts.ask(alex.player, new PromptRequest(Component.text("How many?"), "", origin,
            line -> new Answer.Accept(origin)));

        testing.advance(Prompts.TIMEOUT);
        List.copyOf(timers).forEach(Runnable::run);

        assertThat(alex.said).last().isEqualTo(Prompts.TIMED_OUT);
        assertThat(amount(alex)).isEqualTo(1);
        assertThat(testing.chat(alex.player, "7")).isFalse();
    }

    @Test
    void aSecondPromptsOverTheMenusIsRefused() {
        testing.prompts((task, delay) -> timers.add(task));

        assertThatThrownBy(() -> testing.prompts((task, delay) -> timers.add(task)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPlayerTheThreadDoesNotOwnOpensNoMenu() {
        Viewer alex = new Viewer();
        testing.owned(player -> player != alex.player);

        assertThatThrownBy(() -> menus.open(alex.player, SHOP.id(), null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Menus.openLater");
        assertThat(testing.inventories()).isEmpty();
    }

    @Test
    void aFakeInventoryAnswersItsHolderSizeAndClearAndNothingElse() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);
        Inventory top = alex.top();

        assertThat(top.getHolder(false)).isSameAs(top.getHolder());
        assertThat(top).isEqualTo(top).isNotEqualTo(mock(Inventory.class)).hasToString("a menu inventory of 27 slots");
        assertThat(top.hashCode()).isEqualTo(System.identityHashCode(top));
        assertThatThrownBy(() -> top.getItem(13)).isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("getItem");
        assertThatThrownBy(() -> top.clear(13)).isInstanceOf(UnsupportedOperationException.class);
        top.clear();
        assertThat(testing.icon(top, 13)).isNull();
    }

    @Test
    void anIconIsReadOnlyFromAFakeInventoryAndASlotInsideIt() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);

        assertThatThrownBy(() -> testing.icon(mock(Inventory.class), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> testing.title(mock(Inventory.class))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> testing.icon(alex.top(), 27)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theClockMovesForwardOnly() {
        assertThatThrownBy(() -> testing.advance(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatAButtonThrowsOutsideTheMenusReachesTheTest() {
        Viewer alex = new Viewer();
        menus.open(alex.player, SHOP.id(), null);
        testing.advance(SETTLED);
        duringBuy = event -> {
            throw new AssertionError("from the button");
        };

        assertThatThrownBy(() -> alex.click(13)).isInstanceOf(AssertionError.class).hasMessage("from the button");
    }

    private void tick() {
        Runnable task;
        while ((task = ticks.poll()) != null) {
            task.run();
        }
    }

    private int amount(Viewer viewer) {
        return ((Icon.Button) testing.icon(viewer.top(), 13)).amount();
    }

    // A mocked player whose open view is a mocked InventoryView, and whose closes are delivered as a server posts
    // them: an open closes the previous view with OPEN_NEW first.
    private final class Viewer {

        final Player player = mock(Player.class);
        final UUID id = UUID.randomUUID();
        final List<String> said = new ArrayList<>();
        @Nullable InventoryView view;

        Viewer() {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Alex");
            when(player.locale()).thenReturn(Locale.ENGLISH);
            when(player.isOnline()).thenReturn(true);
            when(player.getOpenInventory()).thenAnswer(call -> view);
            when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
                close(InventoryCloseEvent.Reason.OPEN_NEW);
                InventoryView opened = mock(InventoryView.class);
                when(opened.getTopInventory()).thenReturn(call.getArgument(0));
                when(opened.getPlayer()).thenReturn(player);
                view = opened;
                return opened;
            });
            doAnswer(call -> {
                close(call.getArgument(0));
                return null;
            }).when(player).closeInventory(any(InventoryCloseEvent.Reason.class));
            doAnswer(call -> said.add(PlainTextComponentSerializer.plainText().serialize(call.getArgument(0))))
                .when(player).sendMessage(any(Component.class));
            online.put(id, player);
        }

        void close(InventoryCloseEvent.Reason reason) {
            InventoryView closing = view;
            if (closing != null) {
                view = null;
                testing.deliver(new InventoryCloseEvent(closing, reason));
            }
        }

        Inventory top() {
            return view.getTopInventory();
        }

        InventoryClickEvent click(int slot) {
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
            delivering = event;
            testing.deliver(event);
            return event;
        }
    }
}
