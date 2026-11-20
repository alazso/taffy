package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.List;

/**
 * Fluent {@link ItemStack} builder with Adventure names and lore, both with the default italic
 * styling removed. {@link #build()} requires a running server (item meta).
 */
public final class ItemBuilder {

    private final Material material;
    private int amount = 1;
    private @Nullable Component name;
    private List<Component> lore = List.of();
    private boolean glow;

    public ItemBuilder(Material material) {
        this.material = material;
    }

    public ItemBuilder amount(int amount) {
        this.amount = amount;
        return this;
    }

    public ItemBuilder name(Component name) {
        this.name = name;
        return this;
    }

    public ItemBuilder lore(List<Component> lore) {
        this.lore = lore;
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        this.glow = glow;
        return this;
    }

    public ItemStack build() {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(meta -> {
            if (name != null) {
                meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            }
            if (glow) {
                applyGlow(meta);
            }
        });
        return item;
    }

    /** Gives an item the enchantment glint without adding an enchantment. */
    public static void applyGlow(ItemMeta meta) {
        meta.setEnchantmentGlintOverride(true);
    }

    /**
     * Prefix of a full-fidelity item reference, {@code serialized:<base64>}, which carries the entire
     * item (enchants, name, lore, model data, attributes, components, and any other NBT) and
     * round-trips exactly.
     */
    public static final String SERIALIZED_PREFIX = "serialized:";

    public static boolean isSerialized(String reference) {
        return reference.startsWith(SERIALIZED_PREFIX);
    }

    /**
     * Serializes an item to a {@code serialized:<base64>} reference, preserving all of its data.
     *
     * <p>The item goes to {@link ItemStack#serializeAsBytes()} as it is, and nothing that call throws is
     * caught. An empty stack (air, or an amount of 0) has no reference: the server throws for it. A
     * {@code null} item throws {@link NullPointerException}. A caller that may hold an empty stack checks
     * {@link ItemStack#isEmpty()} first and stores the absence of an item its own way.
     */
    public static String serialize(ItemStack item) {
        return SERIALIZED_PREFIX + Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /**
     * Rebuilds the exact item from a {@code serialized:<base64>} reference, or returns {@code null} if
     * it is malformed or was written for an incompatible server version. The prefix is skipped by
     * length, not checked, so {@code reference} must first pass {@link #isSerialized(String)}.
     * A {@code null} reference gives {@code null}. {@link #deserializeOrThrow(String)} reads the same
     * reference and says why there is no item.
     */
    public static @Nullable ItemStack deserialize(String reference) {
        try {
            return decode(reference, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Rebuilds the exact item from a {@code serialized:<base64>} reference.
     *
     * @throws IllegalArgumentException if {@code reference} does not start with
     *     {@link #SERIALIZED_PREFIX} (no cause), if the text after the prefix is not base64 (the cause
     *     is the decoder's exception), or if the server refused the decoded bytes, which are corrupt
     *     or were written by a newer data version (the cause is the server's exception). The message
     *     names which of the three it was.
     * @throws NullPointerException if {@code reference} is {@code null}
     */
    public static ItemStack deserializeOrThrow(String reference) {
        return decode(reference, true);
    }

    private static ItemStack decode(String reference, boolean prefixChecked) {
        boolean prefixed = prefixChecked
            ? reference.startsWith(SERIALIZED_PREFIX)
            : reference.length() >= SERIALIZED_PREFIX.length();
        if (!prefixed) {
            throw new IllegalArgumentException("Item reference does not start with '" + SERIALIZED_PREFIX + "'");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(reference.substring(SERIALIZED_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Item reference holds invalid base64 after '" + SERIALIZED_PREFIX + "': " + ex.getMessage(), ex);
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The server refused the bytes of the item reference, which are"
                + " corrupt or were written by a newer data version: " + ex.getMessage(), ex);
        }
    }
}
