package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Menus and prompts for a plugin's tests, which run with no server.
 *
 * <p>{@link #menus()} gives a {@link Menus} built over the plugin's {@link Menus.Parts} that differs from
 * {@code new Menus(parts)} in what it takes from the server. Its menu inventories are fakes that record the
 * {@link Icon} each slot shows, read through {@link #icon(Inventory, int)}. Its time source stands still until
 * {@link #advance(Duration)} moves it, so a test steps over the click debounce window instead of waiting. The
 * calling thread owns every player until {@link #owned(Predicate)} says otherwise. Every material but
 * {@code AIR}, {@code CAVE_AIR} and {@code VOID_AIR} counts as an item a slot can show, also one that is a block
 * only, since {@code Material.isItem} and {@code Material.isAir} need a running server. The listeners are
 * registered with no plugin manager: the {@code deliver} methods and {@link #chat(Player, String)} pass an event to
 * them as the server does. {@link Menus.Parts#server()} is not used.
 *
 * <p>A fake menu inventory answers {@link Inventory#getHolder()}, {@link Inventory#getHolder(boolean)},
 * {@link Inventory#getSize()} and {@link Inventory#clear()}, which empties its slots, and equals only itself. Every
 * other method of it throws an {@link UnsupportedOperationException}.
 *
 * <p>The test supplies the players, as mocks of its own. A menu opens through
 * {@link Player#openInventory(Inventory)}, and it counts as shown only when the returned view's
 * {@link InventoryView#getTopInventory()} is the inventory passed in. A server posts an
 * {@link InventoryCloseEvent} when a view closes, also for the view an open replaces; here the test delivers it.
 */
public final class MenuTesting {

    private static final Set<Material> AIR = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Inventory> inventories = new CopyOnWriteArrayList<>();
    private final AtomicLong nanos = new AtomicLong();
    private final Menus menus;
    private volatile Predicate<Player> owned = player -> true;

    /**
     * Creates the menus over the parts, with no menu registered and their listener registered here.
     *
     * @param parts what the menus are built from
     * @throws NullPointerException if {@code parts} is {@code null}
     */
    public MenuTesting(Menus.Parts parts) {
        menus = new Menus(parts, marker -> this::create, player -> owned.test(player),
            material -> !AIR.contains(material), nanos::get, listeners::add);
    }

    /**
     * Gives the menus built over the parts.
     *
     * @return the same menus on every call
     */
    public Menus menus() {
        return menus;
    }

    /**
     * Builds chat prompts over {@link #menus()}, as {@code new Prompts(menus, PromptMode.CHAT, BedrockPlayers.NONE,
     * timer)} does, with these differences. Their deadlines read the time source {@link #advance(Duration)} moves.
     * Their listener is registered here. The cancel link of a question is a click event that runs nothing, so a test
     * cancels with the cancel word. A player is on their own screen, where an answer may open a menu, when
     * {@link Player#getOpenInventory()} is {@code null} or shows a top inventory whose
     * {@link Inventory#getHolder(boolean) getHolder(false)} is the player. The anvil and the dialog need a running
     * server.
     *
     * @param timer runs a task after a delay; a test keeps the tasks and runs one to let an ask time out
     * @return the prompts
     * @throws NullPointerException if {@code timer} is {@code null}
     * @throws IllegalStateException if prompts were already built over the menus, or {@link Menus#shutdown()} ran
     */
    public Prompts prompts(BiConsumer<Runnable, Duration> timer) {
        return new Prompts(menus, PromptMode.CHAT, BedrockPlayers.NONE, timer, null,
            action -> ClickEvent.runCommand("/cancel"), MenuTesting::ownScreen, nanos::get);
    }

    /**
     * Moves the time source of the menus and prompts forward.
     *
     * @param step how far to move it
     * @throws NullPointerException if {@code step} is {@code null}
     * @throws IllegalArgumentException if {@code step} is negative
     */
    public void advance(Duration step) {
        if (step.isNegative()) {
            throw new IllegalArgumentException("the step is negative: " + step);
        }
        nanos.addAndGet(step.toNanos());
    }

    /**
     * Sets which players the calling thread owns, which the menus and prompts ask before they open, redraw or ask.
     *
     * @param owned tells whether the calling thread owns a player; it may be called on any thread
     * @throws NullPointerException if {@code owned} is {@code null}
     */
    public void owned(Predicate<Player> owned) {
        this.owned = Objects.requireNonNull(owned, "owned");
    }

    /**
     * Gives the menu inventories created so far, one per opened or reopened menu, also for an open the player's
     * {@link Player#openInventory(Inventory)} refused.
     *
     * @return a copy, in creation order
     */
    public List<Inventory> inventories() {
        return List.copyOf(inventories);
    }

    /**
     * Gives what a slot of a menu inventory shows.
     *
     * @param inventory a menu inventory of these or another {@code MenuTesting}
     * @param slot the raw slot, from 0 to the inventory's size minus 1
     * @return the icon last drawn into the slot, or {@code null} when none was drawn since the inventory was created
     *     or cleared
     * @throws IllegalArgumentException if {@code inventory} is not a fake menu inventory
     * @throws IndexOutOfBoundsException if {@code slot} is outside the inventory
     */
    public @Nullable Icon icon(Inventory inventory, int slot) {
        Icon[] slots = slots(inventory).icons;
        synchronized (slots) {
            return slots[Objects.checkIndex(slot, slots.length)];
        }
    }

    /**
     * Gives the title a menu inventory was created with.
     *
     * @param inventory a menu inventory of these or another {@code MenuTesting}
     * @return the title
     * @throws IllegalArgumentException if {@code inventory} is not a fake menu inventory
     */
    public Component title(Inventory inventory) {
        return slots(inventory).title;
    }

    /**
     * Passes a click to the listeners of the menus and their prompts, in the order a server calls them: by priority,
     * and at one priority in registration order. A handler that ignores cancelled events is skipped while the event
     * is cancelled. What a handler throws is thrown on, and the later handlers do not run.
     *
     * @param event the click, such as {@code new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
     *     ClickType.LEFT, InventoryAction.PICKUP_ALL)}
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public void deliver(InventoryClickEvent event) {
        post(event);
    }

    /**
     * Passes a drag to the listeners, as {@link #deliver(InventoryClickEvent)} passes a click.
     *
     * @param event the drag
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public void deliver(InventoryDragEvent event) {
        post(event);
    }

    /**
     * Passes a close to the listeners, as {@link #deliver(InventoryClickEvent)} passes a click. The close of a menu
     * view by its viewer ends the menu's session.
     *
     * @param event the close
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public void deliver(InventoryCloseEvent event) {
        post(event);
    }

    /**
     * Passes a quit to the listeners, as {@link #deliver(InventoryClickEvent)} passes a click.
     *
     * @param event the quit
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public void deliver(PlayerQuitEvent event) {
        post(event);
    }

    /**
     * Passes a chat line of the player to the listeners as an {@link AsyncPlayerChatEvent}, the event a server
     * posts first for a chat line, on the calling thread.
     *
     * @param player the player who typed
     * @param line what the player typed
     * @return whether a prompt took the line as the answer to its ask, which cancels the event
     * @throws NullPointerException if {@code player} or {@code line} is {@code null}
     */
    @SuppressWarnings("deprecation")
    public boolean chat(Player player, String line) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, Objects.requireNonNull(player, "player"),
            Objects.requireNonNull(line, "line"), new HashSet<>());
        post(event);
        return event.isCancelled();
    }

    private Inventories.Surface create(InventoryHolder holder, int size, Component title) {
        Slots slots = new Slots(holder, size, title);
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
            new Class<?>[] {Inventory.class}, slots);
        inventories.add(inventory);
        return new Inventories.Surface(inventory, slots);
    }

    private static Slots slots(Inventory inventory) {
        if (Proxy.isProxyClass(Objects.requireNonNull(inventory, "inventory").getClass())
            && Proxy.getInvocationHandler(inventory) instanceof Slots slots) {
            return slots;
        }
        throw new IllegalArgumentException("not a menu inventory of a MenuTesting: " + inventory.getClass().getName());
    }

    private static boolean ownScreen(Player player) {
        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return true;
        }
        Inventory top = view.getTopInventory();
        return top != null && top.getHolder(false) == player;
    }

    private record Handler(Listener listener, Method method, EventHandler rule) {
    }

    private void post(Event event) {
        Objects.requireNonNull(event, "event");
        List<Handler> handlers = new ArrayList<>();
        for (Listener listener : listeners) {
            Arrays.stream(listener.getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(event))
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> handlers.add(new Handler(listener, method,
                    method.getAnnotation(EventHandler.class))));
        }
        handlers.sort(Comparator.comparing(handler -> handler.rule().priority()));
        for (Handler handler : handlers) {
            if (handler.rule().ignoreCancelled() && event instanceof Cancellable cancellable
                && cancellable.isCancelled()) {
                continue;
            }
            try {
                handler.method().invoke(handler.listener(), event);
            } catch (InvocationTargetException thrown) {
                if (thrown.getCause() instanceof RuntimeException failure) {
                    throw failure;
                }
                if (thrown.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(thrown.getCause());
            } catch (IllegalAccessException refused) {
                throw new IllegalStateException(refused);
            }
        }
    }

    // The invocation handler of one fake menu inventory and the canvas that draws into it.
    private static final class Slots implements InvocationHandler, Canvas {

        private final InventoryHolder holder;
        private final Component title;
        private final Icon[] icons;

        Slots(InventoryHolder holder, int size, Component title) {
            this.holder = holder;
            this.title = title;
            this.icons = new Icon[size];
        }

        @Override
        public void put(int slot, Icon icon) {
            synchronized (icons) {
                icons[slot] = icon;
            }
        }

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] arguments) {
            switch (method.getName()) {
                case "getHolder":
                    return holder;
                case "getSize":
                    return icons.length;
                case "clear":
                    if (method.getParameterCount() == 0) {
                        synchronized (icons) {
                            Arrays.fill(icons, null);
                        }
                        return null;
                    }
                    break;
                case "equals":
                    return arguments != null && proxy == arguments[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "a menu inventory of " + icons.length + " slots";
                default:
                    break;
            }
            throw new UnsupportedOperationException("a menu inventory of a MenuTesting has no " + method.getName());
        }
    }
}
