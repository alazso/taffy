package so.alaz.taffy.gui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import so.alaz.taffy.platform.ErrorReports;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The listener that keeps menu views safe and dispatches button clicks. The menus build it over their marker and
 * register it as a listener of their plugin when they are built.
 *
 * <p>Every click and drag in a menu view is cancelled first, at {@link EventPriority#LOWEST}, and again at
 * {@link EventPriority#HIGHEST} in case another plugin cancelled it back. Closes and quits are watched at
 * {@link EventPriority#MONITOR}.
 *
 * <p>Only a click whose type is one of {@link ClickGuard#ANY_PLAIN}, by the viewer of an open session, on a raw slot
 * of the menu inventory that holds a button accepting that click type, reaches the button. The checks then run in this
 * order: the open arming (no click within the click debounce window of the menu opening), the busy check (an exclusive
 * button is dropped while {@link MenuSession#busy()}), and the per-button click debounce. An accepted click plays
 * {@link Cue#CLICK}. A handler that throws a {@link RuntimeException} leaves the event cancelled, is logged with its
 * stack, reported to {@link ErrorReports} under {@code gui.click}, clears {@link MenuSession#busy()} and plays
 * {@link Cue#FAIL}.
 *
 * <p>In any view that is not a menu, a stack carrying the menus' preview key found on the clicked slot, the
 * cursor, a number key's hotbar slot or, for an offhand swap, the offhand is deleted, the click is cancelled and a
 * {@code preview.leak} warning is logged. A drag outside menus with a marked cursor is cancelled and, for a player, the
 * cursor is cleared on the next tick. A view a prompt opened is left to the prompt.
 */
final class MenuListener implements Listener {

    // The player inventory slot of the offhand. Paper reports getHotbarButton() as -1 for SWAP_OFFHAND.
    private static final int OFFHAND = 40;

    private final Menus menus;
    private final PreviewMarker marker;
    private final Logger logger;
    private final ErrorReports errors;

    // Reads only what the menus set before building it. The prompts built over the menus are read when each event
    // arrives.
    MenuListener(Menus menus) {
        this.menus = menus;
        this.marker = menus.marker();
        this.logger = menus.logger();
        this.errors = menus.errors();
    }

    /**
     * Cancels a click in a menu view and dispatches it to its button, or deletes menu copies found outside menus.
     *
     * @param event the click
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder(false) instanceof MenuSession<?> session)) {
            if (!menus.prompts().owns(view)) {
                leak(event);
            }
            return;
        }
        event.setCancelled(true);
        if (session.closed() || !(event.getWhoClicked() instanceof Player player)
            || !player.getUniqueId().equals(session.viewer().getUniqueId())) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= view.getTopInventory().getSize()) {
            return;
        }
        ClickType click = event.getClick();
        if (!ClickGuard.dispatchable(click)) {
            return;
        }
        Button button = session.buttons().get(raw);
        ClickHandler handler = button == null ? null : button.handler();
        if (handler == null || !button.clicks().contains(click)) {
            return;
        }
        if (!menus.debounce().settled(session.openedAt())) {
            return;
        }
        if (button.exclusive() && session.busy()) {
            return;
        }
        if (!menus.debounce().pass(player.getUniqueId(), session.id() + ":" + button.key())) {
            return;
        }
        menus.feedback().play(player, Cue.CLICK);
        try {
            handler.click(new Click(session, player, click, raw));
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "gui: " + session.id() + " button " + button.key() + " failed for "
                + player.getName() + ": " + failure, failure);
            errors.report("gui.click", failure);
            session.busy(false);
            menus.feedback().play(player, Cue.FAIL);
        }
    }

    /**
     * Cancels a click in a menu view again, after every other plugin's handler below this priority.
     *
     * @param event the click
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void recancelClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuSession<?>) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels every drag in a menu view, and a drag outside menus whose cursor is a menu copy. The server puts the
     * cursor back after a cancelled drag, so a cursor still marked on the next tick is cleared then.
     *
     * @param event the drag
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryView view = event.getView();
        if (view.getTopInventory().getHolder(false) instanceof MenuSession<?>) {
            event.setCancelled(true);
            return;
        }
        if (menus.prompts().owns(view) || !marker.marked(event.getOldCursor())) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            menus.tasks().accept(player, () -> {
                ItemStack cursor = player.getItemOnCursor();
                if (marker.marked(cursor)) {
                    player.setItemOnCursor(null);
                    logLeak(player, cursor, "cursor");
                }
            });
        }
    }

    /**
     * Cancels a drag in a menu view again, after every other plugin's handler below this priority.
     *
     * @param event the drag
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void recancelDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuSession<?>) {
            event.setCancelled(true);
        }
    }

    /**
     * Ends the session of a menu view its own viewer closed, for any reason.
     *
     * @param event the close
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuSession<?> session
            && event.getPlayer().getUniqueId().equals(session.viewer().getUniqueId())) {
            menus.closed(session);
        }
    }

    /**
     * Forgets what the menus and prompts keep for a player who quit.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        menus.forget(event.getPlayer().getUniqueId());
        menus.prompts().quit(event.getPlayer().getUniqueId());
    }

    private void leak(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        boolean found = false;
        ItemStack current = event.getCurrentItem();
        if (marker.marked(current)) {
            event.setCurrentItem(null);
            logLeak(who, current, "current item");
            found = true;
        }
        ItemStack cursor = event.getCursor();
        if (marker.marked(cursor)) {
            if (event instanceof InventoryCreativeEvent creative) {
                creative.setCursor(null);
            } else {
                event.getView().setCursor(null);
            }
            logLeak(who, cursor, "cursor");
            found = true;
        }
        ClickType click = event.getClick();
        int slot = click == ClickType.SWAP_OFFHAND ? OFFHAND
            : click == ClickType.NUMBER_KEY ? event.getHotbarButton() : -1;
        if (slot >= 0) {
            PlayerInventory inventory = who.getInventory();
            ItemStack source = inventory.getItem(slot);
            if (marker.marked(source)) {
                inventory.setItem(slot, null);
                logLeak(who, source, slot == OFFHAND ? "offhand" : "hotbar slot " + slot);
                found = true;
            }
        }
        if (found) {
            event.setCancelled(true);
        }
    }

    private void logLeak(HumanEntity who, ItemStack copy, String where) {
        logger.warning("preview.leak: " + who.getName() + " (" + who.getUniqueId() + ") held a menu copy of "
            + copy.getType() + " as " + where + "; it was deleted.");
    }
}
