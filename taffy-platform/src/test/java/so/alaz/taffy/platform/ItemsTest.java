package so.alaz.taffy.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static so.alaz.taffy.test.FakeItems.STORAGE_SLOTS;
import static so.alaz.taffy.test.FakeItems.item;
import static so.alaz.taffy.test.FakeItems.type;

class ItemsTest {

    @Test
    void anItemThatFitsIsAddedAndNothingIsDropped() {
        FakePlayer target = new FakePlayer();
        ItemStack diamonds = item("diamond", 5);

        Items.giveOrDrop(target.player, diamonds);

        assertThat(target.contents()).containsExactly("diamond x 5");
        assertThat(diamonds.getAmount()).isEqualTo(5);
        verifyNoInteractions(target.world);
    }

    @Test
    void anItemThatTopsUpAPartialStackIsLeftUnchanged() {
        FakePlayer target = new FakePlayer();
        target.slots[0] = item("diamond", 60);
        ItemStack diamonds = item("diamond", 16);

        Items.giveOrDrop(target.player, diamonds);

        assertThat(target.contents()).containsExactly("diamond x 64", "diamond x 12");
        assertThat(diamonds.getAmount()).isEqualTo(16);
        verifyNoInteractions(target.world);
    }

    @Test
    void anItemOverTheMaximumStackSizeIsSplitAndLeftUnchanged() {
        FakePlayer target = new FakePlayer();
        ItemStack pearls = item("ender_pearl", 40, 16);

        Items.giveOrDrop(target.player, pearls);

        assertThat(target.contents()).containsExactly("ender_pearl x 16", "ender_pearl x 16", "ender_pearl x 8");
        assertThat(pearls.getAmount()).isEqualTo(40);
        verifyNoInteractions(target.world);
    }

    @Test
    void oneStackGivenToTwoPlayersGivesEachTheFullAmount() {
        FakePlayer first = new FakePlayer();
        FakePlayer second = new FakePlayer();
        first.slots[0] = item("diamond", 60);
        second.slots[0] = item("diamond", 60);
        ItemStack template = item("diamond", 16);

        Items.giveOrDrop(first.player, template);
        Items.giveOrDrop(second.player, template);

        assertThat(first.contents()).containsExactly("diamond x 64", "diamond x 12");
        assertThat(second.contents()).containsExactly("diamond x 64", "diamond x 12");
        assertThat(template.getAmount()).isEqualTo(16);
    }

    @Test
    void whatDoesNotFitIsDroppedAtThePlayersFeetAndTheItemIsLeftUnchanged() {
        FakePlayer target = new FakePlayer();
        for (int slot = 0; slot < STORAGE_SLOTS - 1; slot++) {
            target.slots[slot] = item("stone", 64);
        }
        target.slots[STORAGE_SLOTS - 1] = item("diamond", 60);
        ItemStack diamonds = item("diamond", 16);

        Items.giveOrDrop(target.player, diamonds);

        ArgumentCaptor<ItemStack> dropped = ArgumentCaptor.forClass(ItemStack.class);
        verify(target.world).dropItemNaturally(eq(target.feet), dropped.capture());
        verifyNoMoreInteractions(target.world);
        assertThat(dropped.getValue()).isNotSameAs(diamonds);
        assertThat(type(dropped.getValue())).isEqualTo("diamond");
        assertThat(dropped.getValue().getAmount()).isEqualTo(12);
        assertThat(target.slots[STORAGE_SLOTS - 1].getAmount()).isEqualTo(64);
        assertThat(diamonds.getAmount()).isEqualTo(16);
    }

    /**
     * A player with {@code STORAGE_SLOTS} storage slots. {@code addItem} fills similar partial stacks first, then
     * empty slots, and changes each stack it is given as {@code CraftInventory.addItem} does: what a partial stack
     * takes, and each full stack split off into an empty slot, is subtracted from its amount, the rest goes into
     * an empty slot as a copy, and what does not fit is returned as that same stack.
     */
    private static final class FakePlayer {

        final World world = mock(World.class);
        final Location feet = new Location(world, 10.5, 64, -3.5);
        final Player player = mock(Player.class);
        final @Nullable ItemStack[] slots = new ItemStack[STORAGE_SLOTS];

        FakePlayer() {
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(feet);
            when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> add(call.getArguments()));
        }

        /** Each filled slot as type, {@code " x "} and amount, in slot order. */
        List<String> contents() {
            List<String> contents = new ArrayList<>();
            for (ItemStack slot : slots) {
                if (slot != null) {
                    contents.add(slot.toString());
                }
            }
            return contents;
        }

        private Map<Integer, ItemStack> add(Object[] stacks) {
            Map<Integer, ItemStack> leftover = new HashMap<>();
            for (int index = 0; index < stacks.length; index++) {
                ItemStack stack = (ItemStack) stacks[index];
                int max = stack.getMaxStackSize();
                while (true) {
                    int partial = firstPartial(stack);
                    if (partial >= 0) {
                        ItemStack held = slots[partial];
                        int total = held.getAmount() + stack.getAmount();
                        if (total <= max) {
                            held.setAmount(total);
                            break;
                        }
                        held.setAmount(max);
                        stack.setAmount(total - max);
                        continue;
                    }
                    int free = firstEmpty();
                    if (free < 0) {
                        leftover.put(index, stack);
                        break;
                    }
                    if (stack.getAmount() <= max) {
                        slots[free] = stack.clone();
                        break;
                    }
                    slots[free] = stack.asQuantity(max);
                    stack.setAmount(stack.getAmount() - max);
                }
            }
            return leftover;
        }

        private int firstPartial(ItemStack stack) {
            for (int slot = 0; slot < slots.length; slot++) {
                ItemStack held = slots[slot];
                if (held != null && held.getAmount() < held.getMaxStackSize() && held.isSimilar(stack)) {
                    return slot;
                }
            }
            return -1;
        }

        private int firstEmpty() {
            for (int slot = 0; slot < slots.length; slot++) {
                if (slots[slot] == null) {
                    return slot;
                }
            }
            return -1;
        }
    }
}
