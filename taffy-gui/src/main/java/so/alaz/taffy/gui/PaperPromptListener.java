package so.alaz.taffy.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The prompts' events: the chat line answering a pending chat prompt, and the anvil prompt's events for the
 * {@link AnvilInput} the prompts built. The prompts build it and register it as a listener of their plugin
 * when they are built. Over prompts without an anvil the anvil handlers do nothing. A quit ends the ask of prompts
 * built over no menus; the listener of the menus tells the prompts built over them.
 *
 * <p>Both chat handlers run at {@link EventPriority#LOWEST} with {@code ignoreCancelled = false}, so the line is taken
 * before any other plugin sees it and a mute plugin cannot leave the prompt waiting. The line is cancelled and its
 * audience is emptied ({@link AsyncChatEvent#viewers()} and {@link AsyncPlayerChatEvent#getRecipients()}, where the
 * set can be changed), and its message is never modified. The event still reaches every later handler that does not
 * ignore cancelled events, at any priority, and such a handler can read the answer from it; with the audience empty, a
 * handler that delivers a cancelled line has nobody to deliver it to. A line from a player with no chat prompt is left
 * alone. Paper posts
 * {@link AsyncPlayerChatEvent} before {@link AsyncChatEvent} whenever a plugin listens to it, and this listener does,
 * so once it is registered Paper posts the legacy event for every chat line on the server. The legacy handler
 * therefore normally takes the answer, and {@link AsyncChatEvent} then arrives cancelled; the line is taken from
 * whichever event comes first, and once: a line the legacy handler took is left to no ask by the modern handler, even
 * when the same ask waits again after a refusal or the answer started another ask in between.
 *
 * <p>The listeners of every {@link Prompts} of one plugin receive each chat event. The first of them takes the
 * player's ask, whichever prompts asked it, and the prompts that asked judge the line; the others find the line
 * taken and leave it to no ask.
 *
 * <p>Every click and drag on a prompt anvil is cancelled first and again last. A click on its result slot submits the
 * name field. The result shows a PAPER named after the name field, marked with the menus' preview key. A close clears
 * the anvil, so vanilla returns nothing to the player, and ends a pending ask.
 */
@SuppressWarnings("UnstableApiUsage")
final class PaperPromptListener implements Listener {

    private final Prompts prompts;
    private final @Nullable AnvilInput anvil;

    // Reads only what the prompts set before building it.
    PaperPromptListener(Prompts prompts) {
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.anvil = prompts.anvil();
    }

    /** The asks of the prompts this listener was built for. */
    Asks asks() {
        return prompts.asks();
    }

    /**
     * Takes a chat line that answers a pending chat prompt.
     *
     * @param event the chat line
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        if (prompts.lineTaken(player, event, false)) {
            event.setCancelled(true);
            empty(event.viewers());
            return;
        }
        Pending taken = prompts.takeChat(player, event, false);
        if (taken == null) {
            return;
        }
        event.setCancelled(true);
        empty(event.viewers());
        prompts.answer(player, taken, PlainTextComponentSerializer.plainText().serialize(event.originalMessage()));
    }

    /**
     * Takes a chat line that answers a pending chat prompt, from the legacy chat event.
     *
     * @param event the chat line
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        if (prompts.lineTaken(player, event, true)) {
            event.setCancelled(true);
            empty(event.getRecipients());
            return;
        }
        Pending taken = prompts.takeChat(player, event, true);
        if (taken == null) {
            return;
        }
        event.setCancelled(true);
        empty(event.getRecipients());
        prompts.answer(player, taken, event.getMessage());
    }

    /**
     * Cancels a click on a prompt anvil and passes it to the prompts.
     *
     * @param event the click
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilClick(InventoryClickEvent event) {
        AnvilInput.Open open = anvil == null ? null : anvil.find(event.getView());
        if (open == null) {
            return;
        }
        event.setCancelled(true);
        prompts.anvilClick(event.getWhoClicked().getUniqueId(), open.token(), event.getRawSlot(),
            open.view().getRenameText());
    }

    /**
     * Cancels again a click on a prompt anvil that another plugin uncancelled.
     *
     * @param event the click
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void recancelAnvilClick(InventoryClickEvent event) {
        if (owns(event.getView())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels a drag on a prompt anvil.
     *
     * @param event the drag
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilDrag(InventoryDragEvent event) {
        if (owns(event.getView())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels again a drag on a prompt anvil that another plugin uncancelled.
     *
     * @param event the drag
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void recancelAnvilDrag(InventoryDragEvent event) {
        if (owns(event.getView())) {
            event.setCancelled(true);
        }
    }

    /**
     * Shows a marked paper named after the name field as a prompt anvil's result, at a repair cost of 0.
     *
     * @param event the anvil preparing its result
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        if (anvil == null || !anvil.owns(event.getView())) {
            return;
        }
        String text = event.getView().getRenameText();
        event.setResult(anvil.marker().mark(IconStacks.named(Material.PAPER,
            Component.text(text == null || text.isEmpty() ? " " : text))));
        event.getView().setRepairCost(0);
    }

    /**
     * Clears a closing prompt anvil and tells the prompts it closed.
     *
     * @param event the close
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        AnvilInput.Open open = anvil == null ? null : anvil.closed(event.getView());
        if (open == null) {
            return;
        }
        event.getView().getTopInventory().clear();
        prompts.surfaceClosed(event.getPlayer().getUniqueId(), open.token(), event.getReason());
    }

    /**
     * Ends the ask of a player who quit, for prompts built over no menus.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (prompts.alone()) {
            prompts.quit(event.getPlayer().getUniqueId());
        }
    }

    // Bukkit does not promise that the recipients of a chat event can be changed.
    private static void empty(Set<?> audience) {
        try {
            audience.clear();
        } catch (UnsupportedOperationException fixed) {
            return;
        }
    }

    private boolean owns(InventoryView view) {
        return anvil != null && anvil.owns(view);
    }
}
