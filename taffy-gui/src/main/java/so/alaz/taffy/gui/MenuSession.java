package so.alaz.taffy.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
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
    private long animation;
    private Map<Integer, Button> buttons = Map.of();
    private Map<Integer, Long> changedAt = Map.of();
    private @Nullable Inventory inventory;
    private @Nullable Canvas canvas;
    private boolean held;
    private boolean guarded;
    private long holdLoad;
    private long guardLoad;
    private long step;
    private long holdStep;
    private long loadStep;
    private boolean loading;
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

    /** The number of the latest animation; an animation runs only while its number is the latest. */
    long animation() {
        return animation;
    }

    /** Starts an animation, which replaces the one before it, and gives its number. */
    long nextAnimation() {
        return ++animation;
    }

    Map<Integer, Button> buttons() {
        return buttons;
    }

    /**
     * Tells whether an exclusive action is in flight, during which a click on an exclusive button is dropped. The
     * session is busy while the load the handler of an exclusive button started has not ended, whatever other load
     * started or ended meanwhile, and while a handler holds it through {@link #busy(boolean)}. Inside a handler it is
     * {@code false} unless one of the two holds from an earlier click.
     *
     * @return whether the session is busy
     */
    public boolean busy() {
        return held || guarded;
    }

    /**
     * Holds the session busy, or releases it. The handler of an exclusive button whose work runs through
     * {@link Menus#load(MenuSession, java.util.concurrent.CompletableFuture, java.util.function.BiConsumer)} calls
     * neither: the session is busy until that load ends. A handler whose work runs outside {@code Menus.load}, such
     * as a claim that calls back, calls {@code busy(true)} before it starts the work, and the hold lasts past the
     * handler's return until {@code busy(false)} is called on the viewer's entity thread, such as in that callback.
     * {@code busy(false)} also releases a session that is busy for a load, and a handler that throws releases the
     * hold it took. A handler of a button that is not exclusive, which runs while the session is busy, leaves the
     * session busy when it throws.
     *
     * <p>A hold belongs to a load when both start in one step: the same run of a {@link ClickHandler}, the same
     * {@link Menu#opened(MenuSession, Player)}, the same frame of
     * {@link Menus#animate(MenuSession, java.time.Duration, java.util.function.Predicate)}, or the time between two
     * of those. Such a hold is released when that load applies, fails or is cancelled, also when a later load
     * started meanwhile, so a handler that calls {@code busy(true)} and then {@code Menus.load} calls no
     * {@code busy(false)}. A hold with no load in its step is released only by {@code busy(false)} or when the
     * handler that took it throws: a load another button or a timer starts does not release it. {@code busy(true)}
     * on a session that is already held changes nothing.
     *
     * @param busy {@code true} to hold the session, {@code false} to release it
     */
    public void busy(boolean busy) {
        if (busy) {
            if (!held) {
                this.held = true;
                this.holdStep = step;
                this.holdLoad = loading && loadStep == step ? loadToken : 0;
            }
        } else {
            this.held = false;
            this.holdLoad = 0;
            this.guarded = false;
            this.guardLoad = 0;
        }
    }

    /** Keeps the session busy until the load with the token ends or {@link #busy(boolean)} releases it. */
    void guard(long token) {
        this.guarded = true;
        this.guardLoad = token;
    }

    /**
     * Ends a step: a hold and a load belong together only when both started between two calls. The listener calls it
     * around each handler, and the menus after {@link Menu#opened(MenuSession, Player)} and around each frame of an
     * animation.
     */
    void step() {
        step++;
    }

    /** Tells whether the latest load has neither applied, failed nor been cancelled yet. */
    boolean loading() {
        return loading;
    }

    /**
     * The load with the token applied, failed, was cancelled or ended overtaken by a later load: releases the guard
     * and the hold that belong to it, and no other.
     */
    void loadEnded(long token) {
        if (token == loadToken) {
            this.loading = false;
        }
        if (guarded && guardLoad == token) {
            this.guarded = false;
            this.guardLoad = 0;
        }
        if (held && holdLoad == token) {
            this.held = false;
            this.holdLoad = 0;
        }
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

    /**
     * Takes the buttons of a render that ended at the reading {@code now} of the menus' nanosecond time source. A slot
     * whose button is another one than before is remembered as changed at {@code now}: a keyed button is another one
     * when its key differs, a button that is not keyed when its icon differs, and a button in a slot that had none is
     * always another one. Any other slot keeps the reading it had.
     */
    void rendered(Map<Integer, Button> buttons, long now) {
        Map<Integer, Long> changed = new HashMap<>();
        buttons.forEach((slot, button) -> {
            Long at = same(this.buttons.get(slot), button) ? changedAt.get(slot) : Long.valueOf(now);
            if (at != null) {
                changed.put(slot, at);
            }
        });
        this.buttons = Map.copyOf(buttons);
        this.changedAt = changed;
        this.generation++;
    }

    /**
     * Gives the reading of the menus' nanosecond time source at the render that last put another button in the slot.
     *
     * @return the reading, or {@code null} for a slot with no button
     */
    @Nullable Long changedAt(int slot) {
        return changedAt.get(slot);
    }

    private static boolean same(@Nullable Button before, Button after) {
        return before != null && before.keyed() == after.keyed() && before.key().equals(after.key())
            && (after.keyed() || before.icon().equals(after.icon()));
    }

    /** Starts a load and gives its token. A hold taken earlier in this step belongs to the load. */
    long nextLoad() {
        loading = true;
        loadStep = step;
        loadToken++;
        if (held && holdStep == step) {
            holdLoad = loadToken;
        }
        return loadToken;
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
