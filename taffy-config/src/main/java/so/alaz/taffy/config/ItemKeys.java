package so.alaz.taffy.config;

import org.jetbrains.annotations.Nullable;

/**
 * The key names {@link ConfigReader#item(String, ItemKeys)} reads an item definition from, so that each plugin
 * keeps the keys its files already have.
 *
 * <p>Every key is written relative to the group of keys that holds the item, and may be dotted to name a key
 * further down. {@link #defaults()} gives the names below, and each method returns a copy with one of them
 * replaced. A key given as {@code null} is not read: the part of the {@link ItemSpec} it stands for stays empty
 * and the key is left among {@link ConfigReader#unreadKeys()}.
 *
 * <table>
 *   <caption>The default key names</caption>
 *   <tr><th>Part</th><th>Key</th></tr>
 *   <tr><td>{@link #material}</td><td>{@code material}</td></tr>
 *   <tr><td>{@link #reference}</td><td>{@code reference}</td></tr>
 *   <tr><td>{@link #referenceParts}</td><td>not read</td></tr>
 *   <tr><td>{@link #name}</td><td>{@code name}</td></tr>
 *   <tr><td>{@link #lore}</td><td>{@code lore}</td></tr>
 *   <tr><td>{@link #glow}</td><td>{@code glow}</td></tr>
 *   <tr><td>{@link #itemModel}</td><td>{@code item-model}</td></tr>
 *   <tr><td>{@link #customModelData}</td><td>{@code custom-model-data}</td></tr>
 *   <tr><td>{@link #enchantments}</td><td>{@code enchantments}</td></tr>
 *   <tr><td>{@link #trim}</td><td>{@code trim}</td></tr>
 * </table>
 *
 * <p>Instances are immutable and may be shared between threads.
 *
 * @since 0.4.14
 */
public final class ItemKeys {

    private static final ItemKeys DEFAULTS = new ItemKeys(
        "material", "reference", null, null, "name", "lore", false, "glow", "item-model", "custom-model-data",
        "enchantments", "trim");

    final @Nullable String material;
    final @Nullable String reference;
    final @Nullable String referenceProvider;
    final @Nullable String referenceId;
    final @Nullable String name;
    final @Nullable String lore;
    final boolean textBesideSection;
    final @Nullable String glow;
    final @Nullable String itemModel;
    final @Nullable String customModelData;
    final @Nullable String enchantments;
    final @Nullable String trim;

    private ItemKeys(
        @Nullable String material,
        @Nullable String reference,
        @Nullable String referenceProvider,
        @Nullable String referenceId,
        @Nullable String name,
        @Nullable String lore,
        boolean textBesideSection,
        @Nullable String glow,
        @Nullable String itemModel,
        @Nullable String customModelData,
        @Nullable String enchantments,
        @Nullable String trim) {
        this.material = material;
        this.reference = reference;
        this.referenceProvider = referenceProvider;
        this.referenceId = referenceId;
        this.name = name;
        this.lore = lore;
        this.textBesideSection = textBesideSection;
        this.glow = glow;
        this.itemModel = itemModel;
        this.customModelData = customModelData;
        this.enchantments = enchantments;
        this.trim = trim;
    }

    /**
     * The default key names, listed in the table of this class.
     *
     * @since 0.4.14
     */
    public static ItemKeys defaults() {
        return DEFAULTS;
    }

    /**
     * The key of the material name.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys material(@Nullable String key) {
        return new ItemKeys(checked(key), reference, referenceProvider, referenceId, name, lore, textBesideSection,
            glow, itemModel, customModelData, enchantments, trim);
    }

    /**
     * The key of an item reference written as one text, such as {@code serialized:<base64>} or
     * {@code <plugin>:<id>}.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys reference(@Nullable String key) {
        return new ItemKeys(material, checked(key), referenceProvider, referenceId, name, lore, textBesideSection,
            glow, itemModel, customModelData, enchantments, trim);
    }

    /**
     * The two keys of an item reference written in two parts: the plugin that provides the item and the id of the
     * item in that plugin. The reader joins them as {@code <provider>:<id>}. Both are read or neither is: one
     * {@code null} key turns both off.
     *
     * @throws IllegalArgumentException when a key is blank
     * @since 0.4.14
     */
    public ItemKeys referenceParts(@Nullable String providerKey, @Nullable String idKey) {
        String provider = checked(providerKey);
        String id = checked(idKey);
        boolean both = provider != null && id != null;
        return new ItemKeys(material, reference, both ? provider : null, both ? id : null, name, lore,
            textBesideSection, glow, itemModel, customModelData, enchantments, trim);
    }

    /**
     * The key of the name, a MiniMessage text.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys name(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, checked(key), lore,
            textBesideSection, glow, itemModel, customModelData, enchantments, trim);
    }

    /**
     * The key of the lore, a list of MiniMessage texts.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys lore(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, checked(key),
            textBesideSection, glow, itemModel, customModelData, enchantments, trim);
    }

    /**
     * Whether the name and the lore are read beside the group of keys of the item instead of inside it.
     * {@code false} unless set. With {@code true} and the item at {@code vouchers.gold.item}, the name key
     * {@code display-name} is read at {@code vouchers.gold.display-name}, and for an item at {@code item} it is
     * read at {@code display-name}.
     *
     * @since 0.4.14
     */
    public ItemKeys textBesideSection(boolean beside) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, beside, glow,
            itemModel, customModelData, enchantments, trim);
    }

    /**
     * The key of the glow, {@code true} or {@code false}.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys glow(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, textBesideSection,
            checked(key), itemModel, customModelData, enchantments, trim);
    }

    /**
     * The key of the item model, a namespaced key such as {@code minecraft:diamond_sword}.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys itemModel(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, textBesideSection,
            glow, checked(key), customModelData, enchantments, trim);
    }

    /**
     * The key of the custom model data, one number or a list of numbers.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys customModelData(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, textBesideSection,
            glow, itemModel, checked(key), enchantments, trim);
    }

    /**
     * The key of the enchantments, a group of keys in which each key names an enchantment and holds its level.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys enchantments(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, textBesideSection,
            glow, itemModel, customModelData, checked(key), trim);
    }

    /**
     * The key of the armour trim, a group of keys that holds {@code material} and {@code pattern}. Those two
     * names are fixed.
     *
     * @throws IllegalArgumentException when {@code key} is blank
     * @since 0.4.14
     */
    public ItemKeys trim(@Nullable String key) {
        return new ItemKeys(material, reference, referenceProvider, referenceId, name, lore, textBesideSection,
            glow, itemModel, customModelData, enchantments, checked(key));
    }

    private static @Nullable String checked(@Nullable String key) {
        if (key != null && key.isBlank()) {
            throw new IllegalArgumentException("a key name must not be blank");
        }
        return key;
    }
}
