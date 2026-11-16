package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.FakeItems;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PreviewLeakTest {

    @TempDir
    Path folder;

    @Test
    void aMarkedCurrentItemOutsideAMenuIsDeletedCancelledAndLogged() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        Inventory chest = GuiFakes.foreign(27);
        ItemStack copy = GuiFakes.marked(Material.DIAMOND, harness.marker);
        when(chest.getItem(3)).thenReturn(copy);
        viewer.foreign(chest);

        InventoryClickEvent event = viewer.click(3, ClickType.LEFT);

        assertThat(event.isCancelled()).isTrue();
        verify(chest).setItem(3, null);
        assertThat(harness.log.lines()).containsExactly("WARNING preview.leak: Alex (" + viewer.id + ") held a menu"
            + " copy of DIAMOND as current item; it was deleted.");
    }

    @Test
    void aMarkedCursorIsCleared() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        FakeView view = viewer.foreign(GuiFakes.foreign(27));
        view.setCursor(GuiFakes.marked(Material.EMERALD, harness.marker));

        InventoryClickEvent event = viewer.click(30, ClickType.LEFT);

        assertThat(event.isCancelled()).isTrue();
        assertThat(view.getCursor()).isNull();
        assertThat(harness.log.lines()).singleElement().asString().contains("EMERALD as cursor");
    }

    @Test
    void aNumberKeyOrOffhandSwapClearsTheMarkedSourceSlot() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        viewer.foreign(GuiFakes.foreign(27));
        viewer.slots[3] = GuiFakes.marked(Material.GOLD_INGOT, harness.marker);
        viewer.slots[40] = GuiFakes.marked(Material.PAPER, harness.marker);

        InventoryClickEvent number = viewer.click(5, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 3);
        InventoryClickEvent offhand = viewer.click(5, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, -1);

        assertThat(number.isCancelled()).isTrue();
        assertThat(offhand.isCancelled()).isTrue();
        assertThat(viewer.slots[3]).isNull();
        assertThat(viewer.slots[40]).isNull();
        assertThat(harness.log.lines()).hasSize(2).anySatisfy(line -> assertThat(line).contains("as hotbar slot 3"))
            .anySatisfy(line -> assertThat(line).contains("PAPER as offhand"));
    }

    @Test
    void aCreativeSetSlotWithAMarkedItemIsCancelledAndItsItemDropped() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        FakeView own = viewer.foreign(GuiFakes.foreign(5));

        InventoryCreativeEvent event = new InventoryCreativeEvent(own, InventoryType.SlotType.CONTAINER, 20,
            GuiFakes.marked(Material.DIAMOND_SWORD, harness.marker));
        harness.listener.onClick(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.getCursor()).isNull();
        assertThat(harness.log.lines()).singleElement().asString().contains("DIAMOND_SWORD as cursor");
    }

    @Test
    void unmarkedStacksAreLeftAlone() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        Inventory chest = GuiFakes.foreign(27);
        ItemStack plain = FakeItems.item(Material.DIAMOND, 1);
        when(chest.getItem(3)).thenReturn(plain);
        FakeView view = viewer.foreign(chest);
        ItemStack cursor = FakeItems.item(Material.STONE, 1);
        view.setCursor(cursor);
        viewer.slots[0] = FakeItems.item(Material.EMERALD, 1);

        InventoryClickEvent click = viewer.click(3, ClickType.LEFT);
        InventoryClickEvent number = viewer.click(3, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);

        assertThat(click.isCancelled()).isFalse();
        assertThat(number.isCancelled()).isFalse();
        verify(chest, never()).setItem(3, null);
        assertThat(view.getCursor()).isSameAs(cursor);
        assertThat(viewer.slots[0]).isNotNull();
        assertThat(harness.log.lines()).isEmpty();
    }

    @Test
    void insideAMenuAMarkedItemIsNeverDeleted() {
        Sample sample = new Sample(folder);
        ItemStack copy = GuiFakes.marked(Material.DIAMOND, sample.harness.marker);
        sample.viewer.slots[9] = copy;
        sample.viewer.current().setCursor(copy);

        InventoryClickEvent event = sample.viewer.click(54, ClickType.LEFT);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sample.viewer.slots[9]).isSameAs(copy);
        assertThat(sample.viewer.current().getCursor()).isSameAs(copy);
        assertThat(sample.harness.log.lines()).isEmpty();
    }

    @Test
    void aPromptsOwnViewIsLeftToThePrompt() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        Inventory anvil = GuiFakes.foreign(3);
        ItemStack result = GuiFakes.marked(Material.PAPER, harness.marker);
        when(anvil.getItem(2)).thenReturn(result);
        FakeView view = viewer.foreign(anvil);
        harness.prompts.views.add(view);

        InventoryClickEvent event = viewer.click(2, ClickType.LEFT);

        assertThat(event.isCancelled()).isFalse();
        verify(anvil, never()).setItem(2, null);
        assertThat(harness.log.lines()).isEmpty();
    }
}
