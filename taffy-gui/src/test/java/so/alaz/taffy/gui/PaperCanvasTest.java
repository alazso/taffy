package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.FakeItems;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PaperCanvasTest {

    private static final Icon FILLER = new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE);

    private final PreviewMarker marker = new PreviewMarker(new NamespacedKey("taffytest", "preview"));
    private final Inventory inventory = mock(Inventory.class);
    private final ItemStack[] slots = new ItemStack[9];
    private final List<ItemStack> built = new ArrayList<>();

    PaperCanvasTest() {
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
        doAnswer(call -> {
            slots[(int) call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyWrittenStackIsMarked() {
        PaperCanvas canvas = new PaperCanvas(inventory, icon -> stack(), marker);

        canvas.put(0, FILLER);

        ArgumentCaptor<Consumer<PersistentDataContainer>> edit = ArgumentCaptor.forClass(Consumer.class);
        verify(built.getFirst()).editPersistentDataContainer(edit.capture());
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        edit.getValue().accept(data);
        verify(data).set(marker.key(), PersistentDataType.BYTE, (byte) 1);
        assertThat(slots[0]).isSameAs(built.getFirst());
    }

    @Test
    void theFirstRenderWritesEverySlotAndAnUnchangedIconIsNotWrittenAgain() {
        PaperCanvas canvas = new PaperCanvas(inventory, icon -> GuiFakes.marked(Material.STONE, marker), marker);

        for (int slot = 0; slot < 9; slot++) {
            canvas.put(slot, FILLER);
        }
        ItemStack first = slots[4];
        canvas.put(4, FILLER);

        assertThat(slots).doesNotContainNull();
        assertThat(slots[4]).isSameAs(first);
    }

    @Test
    void aChangedIconIsWritten() {
        PaperCanvas canvas = new PaperCanvas(inventory, icon -> GuiFakes.marked(Material.STONE, marker), marker);
        canvas.put(4, FILLER);
        ItemStack first = slots[4];

        canvas.put(4, new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE));

        assertThat(slots[4]).isNotSameAs(first);
    }

    @Test
    void aSlotEmptiedOrReplacedBehindTheCanvasIsWrittenAgain() {
        PaperCanvas canvas = new PaperCanvas(inventory, icon -> GuiFakes.marked(Material.STONE, marker), marker);
        canvas.put(4, FILLER);
        canvas.put(5, FILLER);
        slots[4] = null;
        ItemStack foreign = FakeItems.item(Material.DIAMOND, 1);
        slots[5] = foreign;

        canvas.put(4, FILLER);
        canvas.put(5, FILLER);

        assertThat(slots[4]).isNotNull();
        assertThat(slots[5]).isNotSameAs(foreign);
    }

    @Test
    void aSlotOutsideTheInventoryIsRefusedWithoutAWrite() {
        PaperCanvas canvas = new PaperCanvas(inventory, icon -> stack(), marker);

        assertThatThrownBy(() -> canvas.put(9, FILLER)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> canvas.put(-1, FILLER)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(built).isEmpty();
        assertThat(slots).containsOnlyNulls();
    }

    private ItemStack stack() {
        ItemStack stack = mock(ItemStack.class);
        built.add(stack);
        return stack;
    }
}
