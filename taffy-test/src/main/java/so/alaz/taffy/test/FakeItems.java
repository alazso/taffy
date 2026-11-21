package so.alaz.taffy.test;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Item stand-ins that behave like Paper's. An item's type decides {@code isSimilar}; its amount changes like a real
 * stack's.
 *
 * <p>Each item is a Mockito mock of {@link ItemStack}, so no server is needed. It answers {@code getAmount},
 * {@code setAmount}, {@code add}, {@code subtract}, {@code isEmpty}, {@code getMaxStackSize}, {@code isSimilar},
 * {@code asQuantity}, {@code asOne}, {@code clone} and {@code toString}. A copy is a new item of the same type with
 * its own amount. {@code toString} is the type, {@code " x "} and the amount. Every other method gives Mockito's
 * default answer.
 *
 * <p>{@code add(int)} raises the amount by the quantity, to the maximum stack size at most, and
 * {@code subtract(int)} lowers it, to 0 at least. {@code add()} and {@code subtract()} move it by 1. Each of the
 * four returns the item, and none of them is recorded on the mock as a {@code getAmount}, {@code setAmount} or
 * {@code getMaxStackSize} call. {@code add} uses the maximum stack size the item was made with, and both use the
 * amount this class keeps: a later stubbing of {@code getMaxStackSize} or {@code getAmount} changes neither
 * {@code add} nor {@code subtract}. {@code isEmpty} is {@code true} when the amount is 0 or less, and at any
 * amount for an item made from {@code Material.AIR}, {@code Material.CAVE_AIR} or {@code Material.VOID_AIR}.
 *
 * <p>The stubbings are lenient: a test under Mockito's strict stubs, such as one run with
 * {@code MockitoExtension}, does not fail for the stubbed methods it leaves uncalled.
 *
 * <p>An item is equal only to itself. Two items are similar when they were made with the same type, whatever
 * their amounts.
 */
public final class FakeItems {

    /** The number of storage slots in a player inventory: the hotbar and the three rows above it. */
    public static final int STORAGE_SLOTS = 36;

    // Weak keys, so an item is dropped with the test that made it. A mock is equal only to itself.
    private static final Map<ItemStack, String> TYPES = Collections.synchronizedMap(new WeakHashMap<>());

    // Material.isAir() initializes org.bukkit.Registry, which fails without a server.
    private static final Set<Material> AIR = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);

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
        ItemStack item = mock(ItemStack.class, withSettings().strictness(Strictness.LENIENT));
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
        doAnswer(call -> add(item, count, 1, maxStack)).when(item).add();
        doAnswer(call -> add(item, count, call.getArgument(0), maxStack)).when(item).add(anyInt());
        doAnswer(call -> subtract(item, count, 1)).when(item).subtract();
        doAnswer(call -> subtract(item, count, call.getArgument(0))).when(item).subtract(anyInt());
        boolean air = material != null && AIR.contains(material);
        when(item.isEmpty()).thenAnswer(call -> air || count.get() <= 0);
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

    private static ItemStack add(ItemStack item, AtomicInteger count, int quantity, int maxStack) {
        count.set(Math.min(maxStack, count.get() + quantity));
        return item;
    }

    private static ItemStack subtract(ItemStack item, AtomicInteger count, int quantity) {
        count.set(Math.max(0, count.get() - quantity));
        return item;
    }

    /** The type the item was made with, or {@code null} for an item this class did not make. */
    public static @Nullable String type(ItemStack item) {
        return TYPES.get(item);
    }
}
