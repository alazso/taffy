package so.alaz.taffy.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Menus over the layouts built here and the English text below, or the lang files given, with fake inventories,
// next-tick tasks that run on drain(), an inline entity thread and a time source the test moves. Every material is an
// item but those in notItems, since Material.isItem needs a running server. Extra layouts are added by
// menu id. Each viewer is a mocked player whose open and close go through the listener as on a server: an open closes
// the previous view with OPEN_NEW first. Clicks and drags are real events passed to the listener. With listening false the close event
// reaches no listener, as on a server while the plugin is disabled. registered holds the listeners the menus and
// their prompts registered, in order.
final class MenuHarness {

    static final MenuKind MAIN = MenuKind.of("main", false, MenuKind.BACK, MenuKind.CLOSE);
    static final MenuKind OPTIONS = MenuKind.of("options", false, MenuKind.BACK, MenuKind.CLOSE);
    static final MenuKind LIST = MenuKind.of("list", true, MenuKind.BACK, MenuKind.PREVIOUS_PAGE,
        MenuKind.NEXT_PAGE, MenuKind.REFRESH, MenuKind.CLOSE);

    // The list fills slots 9 to 44, its empty state shows in 22, and the footer row holds back (45), previous page
    // (48), next page (50), refresh (51) and close (53).
    static final Map<String, MenuLayout> LAYOUTS = Map.of(
        MAIN.id(), layout(MAIN.id(), null, footer()),
        OPTIONS.id(), layout(OPTIONS.id(), null, footer()),
        LIST.id(), layout(LIST.id(), PagerLayout.of(IntStream.range(9, 45).boxed().toList()).emptySlot(22), footer()));

    static final String ENGLISH = """
        gui.error: "<red>Something went wrong. Try again in a moment."
        gui.common.loading.name: "<gray>Loading..."
        gui.main.title: "<gold>Home"
        gui.options.title: "Options"
        gui.list.title: "List"
        gui.list.empty.name: "<gray>Nothing here"
        gui.list.empty.lore: ["<gray>Nothing was found,", "<gray>try again later."]
        gui.footer.back.name: "<yellow>Back"
        gui.footer.back.lore: ["Return to the previous menu."]
        gui.footer.previous-page.name: "<yellow>Previous page"
        gui.footer.previous-page.lore: ["Page <page> of <pages>"]
        gui.footer.next-page.name: "<yellow>Next page"
        gui.footer.next-page.lore: ["Page <page> of <pages>"]
        gui.footer.refresh.name: "<aqua>Refresh"
        gui.footer.close.name: "<red>Close"
        prefix: "<gray>[Server]</gray> "
        gui.prompt.question: "<prefix><yellow><question>"
        gui.prompt.hint: "<gray>Type it in chat, or type <white><word></white> to stop."
        gui.prompt.cancel-button: "<red>[Cancel]"
        gui.prompt.cancel-word: "cancel"
        gui.prompt.cancelled: "<gray>Stopped. Nothing changed."
        gui.prompt.timed-out: "<yellow>No answer within 30 seconds, so nothing changed."
        gui.prompt.refused: ["<reason>", "<gray>You typed: <white><answer>"]
        """;

    static final Map<Cue, Sound> SOUNDS = Map.of(
        Cue.CLICK, Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.4f, 1.0f),
        Cue.SUCCESS, Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 0.6f, 1.2f),
        Cue.FAIL, Sound.sound(Key.key("entity.villager.no"), Sound.Source.MASTER, 0.6f, 1.0f));

    record Report(String source, Throwable error) {
    }

    final CapturedLog log = new CapturedLog();
    final GuiFakes.Ticker clock = new GuiFakes.Ticker();
    final GuiFakes.FakeInventories inventories = new GuiFakes.FakeInventories();
    final GuiFakes.FakeTasks tasks = new GuiFakes.FakeTasks();
    final GuiFakes.DirectEntity entity = new GuiFakes.DirectEntity();
    final PromptCalls prompts = new PromptCalls();
    final ErrorReports errors = new ErrorReports();
    final List<Report> reports = new ArrayList<>();
    final Map<String, MenuLayout> layouts;
    final Messages messages;
    final Plugin plugin;
    final List<Listener> registered = new ArrayList<>();
    final Menus menus;
    final PreviewMarker marker;
    final MenuListener listener;
    Predicate<Player> owned = player -> true;
    final Set<Material> notItems = new HashSet<>(Set.of(Material.AIR, Material.WATER, Material.WALL_TORCH));
    boolean listening = true;

    MenuHarness(Path folder) {
        this(folder, MenuSettings.DEFAULTS, Map.of());
    }

    MenuHarness(Path folder, MenuSettings settings, Map<String, MenuLayout> extra) {
        this(folder, settings, extra, MAIN.id());
    }

    MenuHarness(Path folder, MenuSettings settings, Map<String, MenuLayout> extra, @Nullable String home) {
        this(folder, settings, extra, home, null);
    }

    // With messages null, the lang files are the English text above, read from folder.
    MenuHarness(Path folder, MenuSettings settings, Map<String, MenuLayout> extra, @Nullable String home,
                @Nullable Messages messages) {
        this(folder, settings, extra, home, messages, null);
    }

    // With text null, the menus read the messages.
    MenuHarness(Path folder, MenuSettings settings, Map<String, MenuLayout> extra, @Nullable String home,
                @Nullable Messages messages, @Nullable TextSource text) {
        this.messages = messages != null ? messages : Messages.load(folder,
            () -> new ByteArrayInputStream(ENGLISH.getBytes(StandardCharsets.UTF_8)), log.logger);
        Map<String, MenuLayout> all = new HashMap<>(LAYOUTS);
        all.putAll(extra);
        layouts = Map.copyOf(all);
        errors.install((source, error) -> reports.add(new Report(source, error)));
        plugin = plugin(mock(Server.class), log.logger);
        Menus.Parts parts = text == null
            ? Menus.Parts.of(plugin, this.messages, tasks, entity, errors)
            : Menus.Parts.of(plugin, text, tasks, entity, errors);
        menus = new Menus(parts.layouts(layouts).settings(settings).sounds(() -> SOUNDS).home(home),
            marker -> inventories, player -> owned.test(player), material -> !notItems.contains(material), clock,
            registered::add);
        marker = menus.marker();
        menus.prompts(prompts);
        listener = menus.menuListener();
    }

    // A mocked plugin named TaffyTest, so its preview key is taffytest:preview.
    static Plugin plugin(Server server, Logger logger) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("TaffyTest");
        when(plugin.namespace()).thenReturn("taffytest");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }

    // A six-row layout with no filler and no title of its own, holding the items in the order given.
    static MenuLayout layout(String menu, @Nullable PagerLayout pager, List<ItemLayout> items) {
        return MenuLayout.of(menu, 6).pager(pager).items(items);
    }

    static ItemLayout item(String id, int slot, Material material, @Nullable String action) {
        return ItemLayout.of(id, List.of(slot), material).action(action);
    }

    static List<ItemLayout> footer() {
        return List.of(item("back", 45, Material.OAK_DOOR, MenuKind.BACK),
            item("previous", 48, Material.ARROW, MenuKind.PREVIOUS_PAGE),
            item("next", 50, Material.ARROW, MenuKind.NEXT_PAGE),
            item("refresh", 51, Material.SUNFLOWER, MenuKind.REFRESH),
            item("close", 53, Material.BARRIER, MenuKind.CLOSE));
    }

    Viewer viewer(String name) {
        return new Viewer(name);
    }

    void drain() {
        tasks.drain();
    }

    void advance(Duration step) {
        clock.advance(step);
    }

    // Opens menu in its initial state on the next tick and runs the tick.
    void open(Viewer viewer, String menu) {
        menus.openLater(viewer.player, menu, null);
        drain();
    }

    static @Nullable String plain(@Nullable Component text) {
        return text == null ? null : PlainTextComponentSerializer.plainText().serialize(text);
    }

    // The prompt seam, recording what menus asked of it. calls records each shutdown, in order with what a test adds.
    static final class PromptCalls implements MenuPrompts {

        final List<UUID> ended = new ArrayList<>();
        final List<UUID> quit = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final Set<InventoryView> views = new HashSet<>();

        @Override
        public void end(UUID player) {
            ended.add(player);
        }

        @Override
        public void quit(UUID player) {
            quit.add(player);
        }

        @Override
        public boolean owns(InventoryView view) {
            return views.contains(view);
        }

        @Override
        public void shutdown() {
            calls.add("shutdown");
        }
    }

    final class Viewer {

        final Player player = mock(Player.class);
        final UUID id = UUID.randomUUID();
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final @Nullable ItemStack[] slots = new ItemStack[41];
        final List<Component> messages = new ArrayList<>();
        final List<Sound> sounds = new ArrayList<>();
        final List<InventoryCloseEvent.Reason> closes = new ArrayList<>();
        final Set<String> denied = new HashSet<>();
        final List<FakeView> views = new ArrayList<>();
        @Nullable FakeView view;
        @Nullable ItemStack cursor;
        Locale locale = Locale.ENGLISH;
        boolean refuseOpen;
        boolean online = true;

        Viewer(String name) {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getSize()).thenReturn(slots.length);
            when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
            doAnswer(call -> {
                slots[(int) call.getArgument(0)] = call.getArgument(1);
                return null;
            }).when(inventory).setItem(anyInt(), any());
            when(player.locale()).thenAnswer(call -> locale);
            when(player.hasPermission(anyString())).thenAnswer(call -> !denied.contains(call.<String>getArgument(0)));
            when(player.isOnline()).thenAnswer(call -> online);
            when(player.openInventory(any(Inventory.class))).thenAnswer(call -> open(call.getArgument(0)));
            when(player.getOpenInventory()).thenAnswer(call -> view == null ? own() : view);
            doAnswer(call -> {
                close(call.getArgument(0));
                return null;
            }).when(player).closeInventory(any(InventoryCloseEvent.Reason.class));
            doAnswer(call -> {
                messages.add(call.getArgument(0));
                return null;
            }).when(player).sendMessage(any(Component.class));
            doAnswer(call -> {
                sounds.add(call.getArgument(0));
                return null;
            }).when(player).playSound(any(Sound.class), any(Sound.Emitter.class));
            when(player.getItemOnCursor()).thenAnswer(call -> view == null ? cursor : view.getCursor());
            doAnswer(call -> {
                if (view == null) {
                    cursor = call.getArgument(0);
                } else {
                    view.setCursor(call.getArgument(0));
                }
                return null;
            }).when(player).setItemOnCursor(any());
            entity.online.put(id, player);
        }

        // A refused open returns null when no view was open before, else a view of the player's own inventory, as
        // CraftHumanEntity.openInventory does once the previous view closed.
        private @Nullable InventoryView open(Inventory top) {
            boolean replacing = view != null;
            if (replacing) {
                close(InventoryCloseEvent.Reason.OPEN_NEW);
            }
            if (refuseOpen) {
                return replacing ? own() : null;
            }
            view = new FakeView(top, inventory, player);
            views.add(view);
            return view;
        }

        private void close(InventoryCloseEvent.Reason reason) {
            FakeView closing = view;
            if (closing == null) {
                return;
            }
            view = null;
            closes.add(reason);
            if (listening) {
                listener.onClose(new InventoryCloseEvent(closing, reason));
            }
        }

        private FakeView own() {
            Inventory crafting = mock(Inventory.class);
            when(crafting.getHolder(false)).thenReturn(player);
            when(crafting.getSize()).thenReturn(5);
            return new FakeView(crafting, inventory, player);
        }

        void offline() {
            online = false;
            entity.online.remove(id);
        }

        // Puts a view of another plugin's inventory in front of the player, as a chest or the player's own screen.
        FakeView foreign(Inventory top) {
            view = new FakeView(top, inventory, player);
            views.add(view);
            return view;
        }

        InventoryClickEvent click(int raw, ClickType type) {
            return click(raw, type, InventoryAction.PICKUP_ALL);
        }

        // Paper sends a hotbar key only with NUMBER_KEY; SWAP_OFFHAND carries -1.
        InventoryClickEvent click(int raw, ClickType type, InventoryAction action) {
            return click(raw, type, action, type == ClickType.NUMBER_KEY ? 0 : -1);
        }

        InventoryClickEvent click(int raw, ClickType type, InventoryAction action, int key) {
            return clickOn(current(), raw, type, action, key);
        }

        InventoryClickEvent clickOn(FakeView on, int raw, ClickType type, InventoryAction action, int key) {
            InventoryClickEvent event = new InventoryClickEvent(on, on.getSlotType(raw), raw, type, action, key);
            listener.onClick(event);
            return event;
        }

        InventoryDragEvent drag(ItemStack oldCursor, int... raws) {
            FakeView on = current();
            Map<Integer, ItemStack> dragged = new LinkedHashMap<>();
            for (int raw : raws) {
                dragged.put(raw, oldCursor);
            }
            InventoryDragEvent event = new InventoryDragEvent(on, null, oldCursor, false, dragged);
            listener.onDrag(event);
            return event;
        }

        FakeView current() {
            if (view == null) {
                throw new IllegalStateException("no view is open");
            }
            return view;
        }

        @Nullable MenuSession<?> session() {
            return view != null && view.getTopInventory().getHolder(false) instanceof MenuSession<?> session
                ? session : null;
        }

        Icon icon(int slot) {
            return inventories.canvases.get(current().getTopInventory()).slots[slot];
        }

        @Nullable String name(int slot) {
            return icon(slot) instanceof Icon.Button button ? plain(button.name()) : null;
        }

        List<String> lore(int slot) {
            return icon(slot) instanceof Icon.Button button ? button.lore().stream().map(MenuHarness::plain).toList()
                : List.of();
        }

        boolean filler(int slot) {
            return icon(slot) instanceof Icon.Filler;
        }

        List<String> said() {
            return messages.stream().map(MenuHarness::plain).toList();
        }

        List<String> heard() {
            return sounds.stream().map(sound -> sound.name().value()).toList();
        }
    }
}
