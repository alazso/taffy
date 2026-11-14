package so.alaz.taffy.test;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Item stand-ins that behave like Paper's. An item's type decides {@code isSimilar}; its amount changes like a real
 * stack's.
 *
 * <p>Each item is a Mockito mock of {@link ItemStack}, so no server is needed. It answers {@code getAmount},
 * {@code setAmount}, {@code isEmpty}, {@code getMaxStackSize}, {@code isSimilar}, {@code asQuantity},
 * {@code asOne}, {@code clone} and {@code toString}. A copy is a new item of the same type with its own amount.
 * {@code toString} is the type, {@code " x "} and the amount. Every other method gives Mockito's default answer.
 *
 * <p>An item is equal only to itself. Two items are similar when they were made with the same type, whatever
 * their amounts.
 */
public final class FakeItems {

    /** The number of storage slots in a player inventory: the hotbar and the three rows above it. */
    public static final int STORAGE_SLOTS = 36;

    // Weak keys, so an item is dropped with the test that made it. A mock is equal only to itself.
    private static final Map<ItemStack, String> TYPES = Collections.synchronizedMap(new WeakHashMap<>());

    private FakeItems() {
    }

    /** An item of the given type and amount with a maximum stack size of 64. {@code getType} is {@code null}. */
    public static ItemStack item(String type, int amount) {
        return item(type, amount, 64);
    }

    /** An item of the given type, amount and maximum stack size. {@code getType} is {@code null}. */
    public static ItemStack item(String type, int amount, int maxStack) {
        return build(type, null, amount, maxStack);
    }

    /**
     * An item whose {@code getType} is the material, on its copies too, with a maximum stack size of 64. Its type
     * is the material's name in lowercase.
     */
    public static ItemStack item(Material material, int amount) {
        return item(material, amount, 64);
    }

    /**
     * An item whose {@code getType} is the material, on its copies too. Its type is the material's name in
     * lowercase.
     */
    public static ItemStack item(Material material, int amount, int maxStack) {
        return build(material.name().toLowerCase(Locale.ROOT), material, amount, maxStack);
    }

    private static ItemStack build(String type, @Nullable Material material, int amount, int maxStack) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(amount);
        TYPES.put(item, type);
        if (material != null) {
            when(item.getType()).thenReturn(material);
        }
        when(item.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> {
            count.set(call.getArgument(0));
            return null;
        }).when(item).setAmount(anyInt());
        when(item.isEmpty()).thenAnswer(call -> count.get() <= 0);
        when(item.getMaxStackSize()).thenReturn(maxStack);
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && type.equals(TYPES.get(other));
        });
        when(item.asQuantity(anyInt())).thenAnswer(call -> build(type, material, call.getArgument(0), maxStack));
        when(item.asOne()).thenAnswer(call -> build(type, material, 1, maxStack));
        when(item.clone()).thenAnswer(call -> build(type, material, count.get(), maxStack));
        when(item.toString()).thenAnswer(call -> type + " x " + count.get());
        return item;
    }

    /** The type the item was made with, or {@code null} for an item this class did not make. */
    public static @Nullable String type(ItemStack item) {
        return TYPES.get(item);
    }
}
