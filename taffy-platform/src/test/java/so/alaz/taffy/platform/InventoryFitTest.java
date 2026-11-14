package so.alaz.taffy.platform;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.FakeItems;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static so.alaz.taffy.test.FakeItems.item;

class InventoryFitTest {

    @Test
    void theFitCheckFollowsAddItem() {
        ItemStack[] empty = new ItemStack[FakeItems.STORAGE_SLOTS];
        ItemStack[] partial = new ItemStack[FakeItems.STORAGE_SLOTS];
        Arrays.setAll(partial, slot -> item("dirt", 64));
        partial[3] = item("diamond", 60);
        partial[7] = item("diamond", 63);
        partial[9] = item("air", 0);

        assertThat(InventoryFit.fits(empty, 99, List.of(item("diamond", 36 * 64)))).isTrue();
        assertThat(InventoryFit.fits(empty, 99, List.of(item("diamond", 36 * 64 + 1)))).isFalse();
        assertThat(InventoryFit.fits(empty, 16, List.of(item("diamond", 36 * 16 + 1)))).isFalse();
        assertThat(InventoryFit.fits(partial, 99, List.of(item("diamond", 5 + 64)))).isTrue();
        assertThat(InventoryFit.fits(partial, 99, List.of(item("diamond", 5 + 65)))).isFalse();
        assertThat(InventoryFit.fits(partial, 99, List.of(item("sword", 1, 1), item("sword", 1, 1)))).isFalse();
        assertThat(InventoryFit.fits(partial, 99, List.of(item("sword", 1, 1)))).isTrue();
    }

    @Test
    void onlySimilarPartialStacksTakeMore() {
        ItemStack[] storage = new ItemStack[FakeItems.STORAGE_SLOTS];
        Arrays.setAll(storage, slot -> item("dirt", 10));
        storage[20] = item("diamond", 60);

        assertThat(InventoryFit.fits(storage, 99, List.of(item("diamond", 4)))).isTrue();
        assertThat(InventoryFit.fits(storage, 99, List.of(item("diamond", 5)))).isFalse();
        assertThat(InventoryFit.fits(storage, 99, List.of(item("dirt", 35 * 54)))).isTrue();
        assertThat(InventoryFit.fits(storage, 99, List.of(item("dirt", 35 * 54 + 1)))).isFalse();
    }

    @Test
    void aLaterStackFillsWhatAnEarlierStackLeftFreeInItsSlot() {
        ItemStack[] storage = new ItemStack[FakeItems.STORAGE_SLOTS];
        Arrays.setAll(storage, slot -> item("dirt", 64));
        storage[5] = null;

        assertThat(InventoryFit.fits(storage, 99, List.of(item("diamond", 32), item("diamond", 32)))).isTrue();
        assertThat(InventoryFit.fits(storage, 99, List.of(item("diamond", 32), item("diamond", 33)))).isFalse();
        assertThat(InventoryFit.fits(storage, 99, List.of(item("diamond", 1), item("sword", 1, 1)))).isFalse();
        assertThat(InventoryFit.fits(storage, 16, List.of(item("diamond", 8), item("diamond", 8)))).isTrue();
        assertThat(InventoryFit.fits(storage, 16, List.of(item("diamond", 8), item("diamond", 9)))).isFalse();
    }

    @Test
    void theStorageAndTheStacksAreNotChanged() {
        ItemStack[] storage = new ItemStack[FakeItems.STORAGE_SLOTS];
        storage[0] = item("diamond", 60);
        storage[1] = item("air", 0);
        ItemStack[] before = storage.clone();
        ItemStack added = item("diamond", 70);

        assertThat(InventoryFit.fits(storage, 99, List.of(added))).isTrue();

        assertThat(storage).containsExactly(before);
        assertThat(storage[0].getAmount()).isEqualTo(60);
        assertThat(storage[1].getAmount()).isZero();
        assertThat(added.getAmount()).isEqualTo(70);
    }
}
