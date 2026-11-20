package so.alaz.taffy.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * One open menu for one viewer. The session is the {@link InventoryHolder} of its inventory, which is how listeners
 * recognise a menu view. Every field is read and written on the viewer's entity thread only.
 *
 * @param <S> the type of the state the menu shows
 */
public final class MenuSession<S> implements InventoryHolder {

    private final Menus owner;
    private final Player viewer;
    private final Menu<S> menu;
    private final MenuLayout layout;
    private final @Nullable Route back;
    private final long openedAt;
    private S state;
    private @Nullable Object data;
    private int generation;
    private long loadToken;
    private Map<Integer, Button> buttons = Map.of();
    private @Nullable Inventory inventory;
    private @Nullable Canvas canvas;
    private boolean busy;
    private boolean closed;
    private boolean handoff;
    private boolean closedInHandoff;

    MenuSession(Menus owner, Player viewer, Menu<S> menu, MenuLayout layout, S state, @Nullable Route back,
                long openedAt) {
        this.owner = owner;
        this.viewer = viewer;
        this.menu = menu;
        this.layout = layout;
        this.state = Objects.requireNonNull(state, "state");
        this.back = back;
        this.openedAt = openedAt;
    }

    /** The menus that opened the session. */
    Menus owner() {
        return owner;
    }

    /**
     * Gives the player who sees the menu.
     *
     * @return the viewer
     */
    public Player viewer() {
        return viewer;
    }

    /**
     * Gives the menu this session shows.
     *
     * @return the menu
     */
    public Menu<S> menu() {
        return menu;
    }

    /**
     * Gives the id of the menu.
     *
     * @return the id of the menu's kind
     */
    public String id() {
        return menu.kind().id();
    }

    /**
     * Gives the layout the session opened with; a reload does not change it.
     *
     * @return the layout
     */
    public MenuLayout layout() {
        return layout;
    }

    /**
     * Gives the state the menu shows.
     *
     * @return the state
     */
    public S state() {
        return state;
    }

    /**
     * Replaces the state; the next render shows it.
     *
     * @param state the new state
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void state(S state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Gives the route the back button returns to.
     *
     * @return the route, or {@code null} when the menu has no back route
     */
    public @Nullable Route back() {
        return back;
    }

    /**
     * Gives this menu with its current state, for a back button or a prompt to return to.
     *
     * @return the route of this session
     */
    public Route route() {
        return new Route(id(), state, back);
    }

    /**
     * Gives what the menu's last load applied.
     *
     * @return the data, or {@code null} when nothing was set
     */
    public @Nullable Object data() {
        return data;
    }

    /**
     * Keeps data for the menu's renders, such as the value a load brought.
     *
     * @param data the data, or {@code null} to clear it
     */
    public void data(@Nullable Object data) {
        this.data = data;
    }

    /** The reading of the menus' nanosecond time source when the session was created. */
    long openedAt() {
        return openedAt;
    }

    int generation() {
        return generation;
    }

    /** The token of the latest load; a load applies only while its token is the latest. */
    long loadToken() {
        return loadToken;
    }

    Map<Integer, Button> buttons() {
        return buttons;
    }

    /**
     * Tells whether an exclusive action is in flight.
     *
     * @return whether the session is busy
     */
    public boolean busy() {
        return busy;
    }

    /**
     * Marks an exclusive action as started or finished. A load clears it when it applies or fails.
     *
     * @param busy whether an exclusive action is in flight
     */
    public void busy(boolean busy) {
        this.busy = busy;
    }

    /**
     * Tells whether the session ended.
     *
     * @return whether the session is closed
     */
    public boolean closed() {
        return closed;
    }

    /**
     * Gives the inventory the session shows.
     *
     * @return the inventory
     * @throws IllegalStateException if the inventory was not created yet
     */
    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("the " + id() + " menu has no inventory yet");
        }
        return inventory;
    }

    @Nullable Canvas canvas() {
        return canvas;
    }

    void attach(Inventories.Surface surface) {
        this.inventory = surface.inventory();
        this.canvas = surface.canvas();
    }

    void rendered(Map<Integer, Button> buttons) {
        this.buttons = Map.copyOf(buttons);
        this.generation++;
    }

    long nextLoad() {
        return ++loadToken;
    }

    void handoff(boolean on) {
        this.handoff = on;
    }

    /**
     * Marks the session closed; true when it closed while handed off, so the closed callback waits for the hand-off
     * to finish.
     */
    boolean close() {
        closed = true;
        if (handoff) {
            closedInHandoff = true;
        }
        return handoff;
    }

    boolean takeClosedInHandoff() {
        boolean was = closedInHandoff;
        closedInHandoff = false;
        return was;
    }
}
