package so.alaz.taffy.platform;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.persistence.PersistentDataHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fluent {@link ItemStack} builder with Adventure names and lore, both with the default italic
 * styling removed. {@link #build()} requires a running server (item meta).
 *
 * <p>A builder starts from a {@link Material} or from an existing stack. Every setter is applied in
 * {@link #build()}, and a setter that was not called changes nothing: a builder made from a stack keeps what
 * the stack has. An empty lore, {@code glow(false)} and an empty custom model data list count as not called.
 */
public final class ItemBuilder {

    /** How {@link #glow(boolean)} makes an item glint. */
    public enum GlowStyle {

        /** The enchantment glint override, with no enchantment and no item flag. */
        GLINT_OVERRIDE,

        /**
         * {@link Enchantment#UNBREAKING} at level 1 with {@link ItemFlag#HIDE_ENCHANTS}. An item that already
         * has an enchantment glints through it and is left as it is. An item built this way does not stack
         * with one built by {@link #GLINT_OVERRIDE}.
         */
        HIDDEN_ENCHANTMENT
    }

    private static final ModelData MODEL_DATA = new ModelData(Components::customModelData);

    private final @Nullable Material material;
    private final @Nullable ItemStack base;
    private final ModelData modelData;
    private @Nullable Integer amount;
    private @Nullable Component name;
    private List<Component> lore = List.of();
    private boolean glow;
    private GlowStyle glowStyle = GlowStyle.GLINT_OVERRIDE;
    private @Nullable NamespacedKey itemModel;
    private List<Float> customModelData = List.of();
    private @Nullable Logger customModelDataLogger;
    private final Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
    private @Nullable Boolean unbreakable;
    private @Nullable Integer damage;
    private @Nullable ArmorTrim trim;
    private @Nullable Boolean hideTooltip;
    private @Nullable Color color;
    private final List<Consumer<PersistentDataHolder>> tags = new ArrayList<>();

    /** Starts from a new stack of {@code material}, with an amount of 1 unless {@link #amount(int)} sets it. */
    public ItemBuilder(Material material) {
        this(material, MODEL_DATA);
    }

    /**
     * Starts from {@code base}. {@link #build()} copies the stack as it is at that call and never changes
     * {@code base} itself. The copy keeps the amount, name, lore, glint and every other property of the stack
     * that no setter of this builder was called for.
     *
     * @throws NullPointerException if {@code base} is {@code null}
     */
    public ItemBuilder(ItemStack base) {
        this(base, MODEL_DATA);
    }

    ItemBuilder(Material material, ModelData modelData) {
        this.material = material;
        this.base = null;
        this.modelData = modelData;
    }

    ItemBuilder(ItemStack base, ModelData modelData) {
        this.material = null;
        this.base = Objects.requireNonNull(base, "base");
        this.modelData = modelData;
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

    /**
     * {@code true} makes the item glint in the style of {@link #glowStyle(GlowStyle)}. {@code false} adds
     * nothing and takes no glint off a base stack.
     */
    public ItemBuilder glow(boolean glow) {
        this.glow = glow;
        return this;
    }

    /** The style {@link #glow(boolean)} uses, {@link GlowStyle#GLINT_OVERRIDE} unless set. */
    public ItemBuilder glowStyle(GlowStyle glowStyle) {
        this.glowStyle = Objects.requireNonNull(glowStyle, "glowStyle");
        return this;
    }

    /** Sets the item model through {@link ItemMeta#setItemModel(NamespacedKey)}. */
    public ItemBuilder itemModel(NamespacedKey itemModel) {
        this.itemModel = Objects.requireNonNull(itemModel, "itemModel");
        return this;
    }

    /**
     * Sets the floats of the custom model data through the {@code CUSTOM_MODEL_DATA} data component, which
     * replaces the custom model data of a base stack. An empty list sets nothing.
     *
     * <p>When the data component API of the server cannot be linked, a list of one whole number is set
     * through {@link ItemMeta#setCustomModelData(Integer)} and any other list is left out. {@code logger}
     * gets one warning for the first such build, and none for a later one.
     */
    public ItemBuilder customModelData(List<Float> values, Logger logger) {
        this.customModelData = List.copyOf(values);
        this.customModelDataLogger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    /**
     * Adds an enchantment through {@link ItemMeta#addEnchant(Enchantment, int, boolean)}, with no limit on the
     * level. A second call for the same enchantment replaces its level.
     */
    public ItemBuilder enchant(Enchantment enchantment, int level) {
        enchantments.put(Objects.requireNonNull(enchantment, "enchantment"), level);
        return this;
    }

    /** Sets or clears the unbreakable property through {@link ItemMeta#setUnbreakable(boolean)}. */
    public ItemBuilder unbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
        return this;
    }

    /** Sets the damage through {@link Damageable#setDamage(int)}. An item that cannot be damaged ignores it. */
    public ItemBuilder damage(int damage) {
        this.damage = damage;
        return this;
    }

    /** Sets the armour trim through {@link ArmorMeta#setTrim(ArmorTrim)}. An item that is not armour ignores it. */
    public ItemBuilder trim(ArmorTrim trim) {
        this.trim = Objects.requireNonNull(trim, "trim");
        return this;
    }

    /** Hides or shows the whole tooltip through {@link ItemMeta#setHideTooltip(boolean)}. */
    public ItemBuilder hideTooltip(boolean hideTooltip) {
        this.hideTooltip = hideTooltip;
        return this;
    }

    /**
     * Sets the colour of leather armour ({@link LeatherArmorMeta#setColor(Color)}), a potion
     * ({@link PotionMeta#setColor(Color)}) or a map ({@link MapMeta#setColor(Color)}). Any other item ignores
     * it. From Paper 26.3 a map has no colour, and {@code MapMeta.setColor} does nothing there.
     */
    public ItemBuilder color(Color color) {
        this.color = Objects.requireNonNull(color, "color");
        return this;
    }

    /**
     * Writes {@code value} under {@code key} in the persistent data container of the item. Tags are written
     * in call order, so a later call for the same key wins.
     */
    public <P, C> ItemBuilder tag(PdcKey<P, C> key, C value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        tags.add(holder -> key.set(holder, value));
        return this;
    }

    /**
     * Builds a new stack on every call. A builder made from a stack copies it with {@link ItemStack#clone()}
     * and changes only the copy.
     */
    public ItemStack build() {
        ItemStack item;
        if (base != null) {
            item = base.clone();
            if (amount != null) {
                item.setAmount(amount);
            }
        } else {
            item = new ItemStack(material, amount != null ? amount : 1);
        }
        item.editMeta(this::apply);
        if (!customModelData.isEmpty()) {
            modelData.apply(item, customModelData, customModelDataLogger);
        }
        return item;
    }

    private void apply(ItemMeta meta) {
        if (name != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());
        }
        enchantments.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        if (glow) {
            applyGlow(meta, glowStyle);
        }
        if (itemModel != null) {
            meta.setItemModel(itemModel);
        }
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (damage != null && meta instanceof Damageable damageable) {
            damageable.setDamage(damage);
        }
        if (trim != null && meta instanceof ArmorMeta armor) {
            armor.setTrim(trim);
        }
        if (hideTooltip != null) {
            meta.setHideTooltip(hideTooltip);
        }
        if (color != null) {
            applyColor(meta, color);
        }
        tags.forEach(tag -> tag.accept(meta));
    }

    @SuppressWarnings("deprecation")
    private static void applyColor(ItemMeta meta, Color color) {
        if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(color);
        } else if (meta instanceof PotionMeta potion) {
            potion.setColor(color);
        } else if (meta instanceof MapMeta map) {
            map.setColor(color);
        }
    }

    /** Gives an item the enchantment glint without adding an enchantment. */
    public static void applyGlow(ItemMeta meta) {
        meta.setEnchantmentGlintOverride(true);
    }

    /** Makes an item glint in the given style. */
    public static void applyGlow(ItemMeta meta, GlowStyle style) {
        if (style == GlowStyle.GLINT_OVERRIDE) {
            applyGlow(meta);
        } else if (!meta.hasEnchants()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    /** Sets custom model data on a stack and warns once when the data component API cannot be linked. */
    static final class ModelData {

        private final BiConsumer<ItemStack, List<Float>> component;
        private final AtomicBoolean warned = new AtomicBoolean();

        ModelData(BiConsumer<ItemStack, List<Float>> component) {
            this.component = component;
        }

        void apply(ItemStack item, List<Float> values, Logger logger) {
            try {
                component.accept(item, values);
            } catch (LinkageError ex) {
                if (warned.compareAndSet(false, true)) {
                    logger.log(Level.WARNING, "The data component API of this server could not be linked, so"
                        + " custom model data of one whole number is set through ItemMeta and any other custom"
                        + " model data is left out", ex);
                }
                Integer whole = wholeNumber(values);
                if (whole != null) {
                    item.editMeta(meta -> setThroughMeta(meta, whole));
                }
            }
        }

        private static @Nullable Integer wholeNumber(List<Float> values) {
            if (values.size() != 1) {
                return null;
            }
            float value = values.getFirst();
            int whole = (int) value;
            return whole == value ? whole : null;
        }

        @SuppressWarnings("deprecation")
        private static void setThroughMeta(ItemMeta meta, int value) {
            meta.setCustomModelData(value);
        }
    }

    // The only class that names the data component types, so a server without them fails to link here.
    private static final class Components {

        private Components() {
        }

        static void customModelData(ItemStack item, List<Float> values) {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloats(values).build());
        }
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

    /**
     * Rebuilds the exact item from either stored form: a {@code serialized:<base64>} reference, read as
     * {@link #deserializeOrThrow(String)} reads it, or the bare base64 of
     * {@link ItemStack#serializeAsBytes()} with no prefix.
     *
     * <p>A reference without the prefix that {@link Material#matchMaterial(String)} finds is a material name
     * and is never decoded, so a name that is also valid base64, such as {@code DIRT}, gives no item.
     *
     * @throws IllegalArgumentException if {@code reference} does not start with {@link #SERIALIZED_PREFIX}
     *     and is a material name (no cause) or is not base64 (the cause is the decoder's exception), if the
     *     text after the prefix is not base64 (the cause is the decoder's exception), or if the server refused
     *     the decoded bytes, which are corrupt or were written by a newer data version (the cause is the
     *     server's exception). The message names which it was.
     * @throws NullPointerException if {@code reference} is {@code null}
     */
    public static ItemStack deserializeAnyOrThrow(String reference) {
        if (reference.startsWith(SERIALIZED_PREFIX)) {
            return decode(reference, true);
        }
        if (Material.matchMaterial(reference) != null) {
            throw new IllegalArgumentException("Item reference does not start with '" + SERIALIZED_PREFIX
                + "' and is the material name '" + reference + "'");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(reference);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Item reference does not start with '" + SERIALIZED_PREFIX
                + "' and is not base64: " + ex.getMessage(), ex);
        }
        return fromBytes(bytes);
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
        return fromBytes(bytes);
    }

    private static ItemStack fromBytes(byte[] bytes) {
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The server refused the bytes of the item reference, which are"
                + " corrupt or were written by a newer data version: " + ex.getMessage(), ex);
        }
    }
}
