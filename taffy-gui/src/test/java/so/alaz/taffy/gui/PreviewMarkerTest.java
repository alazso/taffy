package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.FakeItems;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class PreviewMarkerTest {

    private static final NamespacedKey KEY = new NamespacedKey("taffytest", "preview");

    private final PreviewMarker marker = new PreviewMarker(KEY);

    @Test
    void theKeyIsTheOneGiven() {
        assertThat(marker.key()).isSameAs(KEY);
        assertThatThrownBy(() -> new PreviewMarker(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void markWritesByteOneUnderTheKeyAndReturnsTheSameStack() {
        ItemStack stack = mock(ItemStack.class);

        ItemStack marked = marker.mark(stack);

        assertThat(marked).isSameAs(stack);
        ArgumentCaptor<Consumer<PersistentDataContainer>> edit = ArgumentCaptor.forClass(Consumer.class);
        verify(stack).editPersistentDataContainer(edit.capture());
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        edit.getValue().accept(data);
        verify(data).set(KEY, PersistentDataType.BYTE, (byte) 1);
        verifyNoMoreInteractions(data);
    }

    @Test
    void aStackCarryingTheKeyWithAnyValueIsMarked() {
        assertThat(marker.marked(withData(new FakeData().put(KEY, (byte) 1)))).isTrue();
        assertThat(marker.marked(withData(new FakeData().put(KEY, (byte) 0)))).isTrue();
        assertThat(marker.marked(withData(new FakeData().put(KEY, "copy")))).isTrue();
        assertThat(marker.marked(GuiFakes.marked(Material.STONE, marker))).isTrue();
    }

    @Test
    void nullAnEmptyStackAStackWithoutDataAndAStackWithoutTheKeyAreUnmarked() {
        ItemStack empty = FakeItems.item(Material.DIAMOND, 0);
        when(empty.getPersistentDataContainer()).thenReturn(new FakeData().put(KEY, (byte) 1));
        NamespacedKey other = new NamespacedKey("taffytest", "other");

        assertThat(marker.marked(null)).isFalse();
        assertThat(marker.marked(empty)).isFalse();
        assertThat(marker.marked(FakeItems.item(Material.DIAMOND, 1))).isFalse();
        assertThat(marker.marked(withData(new FakeData().put(other, (byte) 1)))).isFalse();
        assertThat(new PreviewMarker(other).marked(GuiFakes.marked(Material.STONE, marker))).isFalse();
    }

    private static ItemStack withData(FakeData data) {
        ItemStack stack = FakeItems.item(Material.DIAMOND, 1);
        when(stack.getPersistentDataContainer()).thenReturn(data);
        return stack;
    }
}
