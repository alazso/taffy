package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The anvil prompt: a virtual anvil with no location whose name field holds the answer, at a repair cost of 0.
 * {@link Prompts} builds one for {@link PromptMode#ANVIL} with the menus' {@link PreviewMarker}. Its methods are called
 * by the prompts and the {@link PaperPromptListener} they build.
 *
 * <p>{@link #open} and {@link #close} run on the player's entity thread. {@link #owns}, {@link #showing} and
 * {@link #forget} are safe from any thread, and {@link #shutdown} runs on the thread that disables the plugin.
 *
 * <p>Slot 0 holds a PAPER marked with the plugin's {@link PreviewMarker}, whose custom name is the starting text. The
 * name field only works with an item there, and the client sends an empty name for a field equal to the item name of
 * an item with no custom name, so the starting text is a custom name for the client to send it as typed.
 */
@SuppressWarnings("UnstableApiUsage")
final class AnvilInput {

    record Open(AnvilView view, long token) {
    }

    private final PreviewMarker marker;
    private final BiFunction<Player, Component, AnvilView> views;
    private final Function<String, ItemStack> papers;
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    /**
     * Creates the anvil prompt with no anvil open.
     *
     * @param marker the plugin's marker, written on the paper each anvil shows
     * @throws NullPointerException if {@code marker} is {@code null}
     */
    AnvilInput(PreviewMarker marker) {
        this(marker, AnvilInput::anvil, AnvilInput::paper);
    }

    AnvilInput(PreviewMarker marker, BiFunction<Player, Component, AnvilView> views,
               Function<String, ItemStack> papers) {
        this.marker = Objects.requireNonNull(marker, "marker");
        this.views = views;
        this.papers = papers;
    }

    static AnvilView anvil(Player player, Component question) {
        return MenuType.ANVIL.builder().title(question).checkReachable(false).build(player);
    }

    // A space stands in for empty starting text, since an empty custom name is no custom name.
    static ItemStack paper(String initial) {
        ItemStack paper = new ItemStack(Material.PAPER);
        paper.editMeta(meta -> meta.displayName(Component.text(initial.isEmpty() ? " " : initial)));
        return paper;
    }

    /**
     * Shows an anvil for {@code token} in place of whatever the player has open.
     *
     * @param player the player to ask
     * @param question the question shown
     * @param initial the text the input starts with
     * @param token the token of the ask
     * @return {@code false} when the server refused to open it
     */
    boolean open(Player player, Component question, String initial, long token) {
        AnvilView view = views.apply(player, question);
        view.getTopInventory().setFirstItem(marker.mark(papers.apply(initial)));
        view.setRepairCost(0);
        Open entry = new Open(view, token);
        Open replaced = open.put(player.getUniqueId(), entry);
        // A replaced anvil may still be on screen; it is cleared before the new one closes it, so its paper is gone.
        if (replaced != null && replaced.view() != view) {
            replaced.view().getTopInventory().clear();
        }
        player.openInventory(view);
        if (player.getOpenInventory() != view) {
            open.remove(player.getUniqueId(), entry);
            view.getTopInventory().clear();
            return false;
        }
        return true;
    }

    /**
     * Takes the anvil for {@code token} away from the player. Nothing happens when it no longer shows {@code token}.
     *
     * @param player the player
     * @param token the token of the ask
     */
    // Clears the anvil and drops its entry before closing it, so the marked paper is gone even when no close event
    // reaches the prompt listener.
    void close(Player player, long token) {
        Open entry = open.get(player.getUniqueId());
        if (entry == null || entry.token() != token || !open.remove(player.getUniqueId(), entry)) {
            return;
        }
        entry.view().getTopInventory().clear();
        if (player.isOnline() && player.getOpenInventory() == entry.view()) {
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        }
    }

    /**
     * Tells whether the anvil still shows the ask with {@code token} to the player.
     *
     * @param player the id of the player
     * @param token the token of the ask
     * @return {@code true} while it shows
     */
    boolean showing(UUID player, long token) {
        Open entry = open.get(player);
        return entry != null && entry.token() == token;
    }

    /**
     * Tells whether this anvil prompt opened the view and still shows it.
     *
     * @param view an open inventory view
     * @return {@code true} for a view of this anvil prompt
     */
    boolean owns(InventoryView view) {
        return find(view) != null;
    }

    /**
     * Drops what is kept for the player without sending the player anything.
     *
     * @param player the id of the player
     */
    void forget(UUID player) {
        open.remove(player);
    }

    /**
     * Takes the anvil away from every player it still shows whom {@code here} accepts. The anvil of any other
     * viewer is emptied from the calling thread, stays open and is forgotten.
     *
     * @param here whether the calling thread may act on a player
     */
    // Paper marks the plugin disabled before onDisable and skips its listeners, so close clears each anvil itself.
    void shutdown(Predicate<Player> here) {
        for (Map.Entry<UUID, Open> entry : List.copyOf(open.entrySet())) {
            Open shown = entry.getValue();
            if (shown.view().getPlayer() instanceof Player player && here.test(player)) {
                close(player, shown.token());
            } else if (open.remove(entry.getKey(), shown)) {
                shown.view().getTopInventory().clear();
            }
        }
    }

    PreviewMarker marker() {
        return marker;
    }

    @Nullable Open find(InventoryView view) {
        Open entry = open.get(view.getPlayer().getUniqueId());
        return entry != null && entry.view() == view ? entry : null;
    }

    // Removes and returns the entry of view, which is closing.
    @Nullable Open closed(InventoryView view) {
        Open entry = find(view);
        if (entry != null) {
            open.remove(view.getPlayer().getUniqueId(), entry);
        }
        return entry;
    }
}
