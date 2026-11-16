package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PaperInventoriesTest {

    private static final Component TITLE = Component.text("Home");

    private final PreviewMarker marker = new PreviewMarker(new NamespacedKey("taffytest", "preview"));
    private final Server server = mock(Server.class);
    private final InventoryHolder holder = mock(InventoryHolder.class);
    private final Inventory inventory = mock(Inventory.class);

    PaperInventoriesTest() {
        when(inventory.getSize()).thenReturn(27);
        when(server.createInventory(holder, 27, TITLE)).thenReturn(inventory);
    }

    @Test
    void createAsksTheServerForTheInventoryAndItsCanvasWritesMarkedStacks() {
        ItemStack stack = GuiFakes.marked(Material.STONE, marker);
        PaperInventories inventories = new PaperInventories(server, marker, icon -> stack);

        Inventories.Surface surface = inventories.create(holder, 27, TITLE);
        surface.canvas().put(4, new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE));

        verify(server).createInventory(holder, 27, TITLE);
        assertThat(surface.inventory()).isSameAs(inventory);
        verify(inventory).setItem(4, stack);
        verify(stack).editPersistentDataContainer(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePublicConstructorDrawsIconsThroughTheItemMeta() {
        ItemMeta meta = mock(ItemMeta.class);
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getMaxStackSize()).thenReturn(64);
            when(stack.editMeta(any())).thenAnswer(call -> {
                Consumer<ItemMeta> edit = call.getArgument(0);
                edit.accept(meta);
                return true;
            });
        })) {
            Inventories.Surface surface = new PaperInventories(server, marker).create(holder, 27, TITLE);

            surface.canvas().put(0, new Icon.Button(Material.EMERALD, 1, Component.text("Browse"), List.of(), false));

            ItemStack drawn = stacks.constructed().getFirst();
            verify(inventory).setItem(0, drawn);
            verify(meta).itemName(Component.text("Browse"));
            ArgumentCaptor<Consumer<PersistentDataContainer>> edit = ArgumentCaptor.forClass(Consumer.class);
            verify(drawn).editPersistentDataContainer(edit.capture());
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            edit.getValue().accept(data);
            verify(data).set(marker.key(), PersistentDataType.BYTE, (byte) 1);
        }
    }

    @Test
    void theServerAndTheMarkerAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new PaperInventories(null, marker)).withMessage("server");
        assertThatNullPointerException().isThrownBy(() -> new PaperInventories(server, null)).withMessage("marker");
    }
}
