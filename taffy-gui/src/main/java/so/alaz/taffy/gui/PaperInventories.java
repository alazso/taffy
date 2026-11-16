package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.Function;

/**
 * Menu inventories created by the server. Every stack drawn into them carries the key of the given
 * {@link PreviewMarker}.
 */
final class PaperInventories implements Inventories {

    private final Server server;
    private final PreviewMarker marker;
    private final Function<Icon, ItemStack> stacks;

    /**
     * Inventories from the server, marked with the given marker.
     *
     * @param server the server that creates each inventory
     * @param marker the marker every drawn stack carries
     * @throws NullPointerException if {@code server} or {@code marker} is {@code null}
     */
    PaperInventories(Server server, PreviewMarker marker) {
        this(server, marker, IconStacks::stack);
    }

    PaperInventories(Server server, PreviewMarker marker, Function<Icon, ItemStack> stacks) {
        this.server = Objects.requireNonNull(server, "server");
        this.marker = Objects.requireNonNull(marker, "marker");
        this.stacks = stacks;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the server refuses {@code size}, as it does for a size that is not a
     *     multiple of 9
     */
    @Override
    public Surface create(InventoryHolder holder, int size, Component title) {
        Inventory inventory = server.createInventory(holder, size, title);
        return new Surface(inventory, new PaperCanvas(inventory, stacks, marker));
    }
}
