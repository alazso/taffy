package so.alaz.taffy.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ItemBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * An item as a configuration file defines it, read by {@link ConfigReader#item(String, ItemKeys)} without a
 * running server and turned into an {@link ItemBuilder} by {@link #overMaterial} or {@link #overStack} on one.
 *
 * <p>The record holds what the file says and resolves nothing against the server: the name and the lore are the
 * MiniMessage texts as written, and the keys of the enchantments and of the trim are texts. The two build
 * methods render the texts with the function the plugin passes and look the keys up in the registries of the
 * server. A key the server does not have is passed to the problem consumer and skipped. Neither method throws
 * for it.
 *
 * <p>The reference is carried for the plugin: Taffy does not build an item from it. A plugin that reads one
 * builds the stack itself, a {@code serialized:} reference with {@link ItemBuilder#deserializeOrThrow} and a
 * {@code <plugin>:<id>} reference with {@code CustomItems} of {@code taffy-hooks}, and passes that stack to
 * {@link #overStack}.
 *
 * <p>The record is immutable. Reading one needs no server, so it may happen on any thread.
 *
 * @param file the name of the file the item was read from, or {@code null} when the reader was built without
 *     one. It becomes the file of every {@link ConfigProblem} the build methods report.
 * @param path the path of the group of keys the item was read from, as the reader writes it in its lines, such
 *     as {@code item} or {@code rewards[1].item}
 * @param material the material, or {@code null} when the file names none
 * @param reference the item reference, such as {@code serialized:<base64>} or {@code nexo:ruby_sword}, without
 *     surrounding whitespace, or {@code null} when the file names none
 * @param name the name as MiniMessage text, or {@code null} when the file names none
 * @param lore the lore lines as MiniMessage text, in file order
 * @param glow whether the item glints
 * @param itemModel the item model, or {@code null} when the file names none
 * @param customModelData the floats of the custom model data, in file order
 * @param enchantments the level of each enchantment by its key as written in lower case, such as
 *     {@code sharpness} or {@code minecraft:sharpness}, in file order. Every level is 1 or more.
 * @param trim the armour trim, or {@code null} when the file names none
 * @since 0.4.14
 */
public record ItemSpec(
    @Nullable String file,
    String path,
    @Nullable Material material,
    @Nullable String reference,
    @Nullable String name,
    List<String> lore,
    boolean glow,
    @Nullable NamespacedKey itemModel,
    List<Float> customModelData,
    Map<String, Integer> enchantments,
    @Nullable Trim trim) {

    /**
     * The keys of an armour trim, each as written in lower case, such as {@code quartz} and {@code sentry}.
     *
     * @param material the key of the trim material
     * @param pattern the key of the trim pattern
     * @since 0.4.14
     */
    public record Trim(String material, String pattern) {

        /**
         * @throws NullPointerException when {@code material} or {@code pattern} is {@code null}
         * @since 0.4.14
         */
        public Trim {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /**
     * Copies {@code lore}, {@code customModelData} and {@code enchantments}, which keeps its order.
     *
     * @throws NullPointerException when {@code path}, {@code lore}, {@code customModelData} or
     *     {@code enchantments} is {@code null} or holds a {@code null}
     * @since 0.4.14
     */
    public ItemSpec {
        Objects.requireNonNull(path, "path");
        lore = List.copyOf(lore);
        customModelData = List.copyOf(customModelData);
        Map<String, Integer> levels = new LinkedHashMap<>();
        enchantments.forEach((key, level) ->
            levels.put(Objects.requireNonNull(key, "enchantment"), Objects.requireNonNull(level, "level")));
        enchantments = Collections.unmodifiableMap(levels);
    }

    /**
     * A builder that starts from a new stack of {@link #material()}, or of {@code fallback} when the file names
     * no material, with every part of this record applied to it.
     *
     * <p>The name and each lore line go through {@code renderer} once per call and are set with
     * {@link ItemBuilder#name} and {@link ItemBuilder#lore}, which take the default italic styling off. Taffy
     * adds no tag resolver and no placeholder of its own: {@code renderer} decides both, and
     * {@code text::render} of a {@code Text} from {@code taffy-platform} renders MiniMessage with the
     * placeholders that class resolves. A glow is set with {@link ItemBuilder#glow}, the item model with
     * {@link ItemBuilder#itemModel}, the custom model data with {@link ItemBuilder#customModelData}, each
     * enchantment with {@link ItemBuilder#enchant} and the trim with {@link ItemBuilder#trim}. A part the file
     * does not name calls no setter. The caller may call further setters on the builder, such as
     * {@link ItemBuilder#amount} or {@link ItemBuilder#glowStyle}, before it builds.
     *
     * <p>Each of these is passed to {@code problems} as a {@link ConfigProblem.Severity#WARNING} with
     * {@link #file()} and {@link #path()}, and the build goes on without it:
     *
     * <ul>
     *   <li>a material that is not an item, such as {@code water}, or is a kind of air, in place of which
     *       {@code fallback} is used</li>
     *   <li>an enchantment whose key is not a valid namespaced key or names no enchantment of the server</li>
     *   <li>a trim whose material or pattern is not a valid namespaced key or names none of the server, in
     *       which case no trim is set</li>
     * </ul>
     *
     * <p>The method needs a running server, since it reads the item, enchantment and trim registries. It reads
     * nothing else and changes nothing, so it may be called on any thread, on Folia too.
     * {@link ItemBuilder#build()} of the result keeps its own rule.
     *
     * @param fallback the material of an item whose file names none or names one that cannot be used
     * @param renderer turns a MiniMessage text of the file into a component
     * @param logger the logger {@link ItemBuilder#customModelData} warns through
     * @param problems receives each problem on the calling thread
     * @throws NullPointerException when an argument is {@code null}
     * @since 0.4.14
     */
    public ItemBuilder overMaterial(
        Material fallback, Function<String, Component> renderer, Logger logger, Consumer<ConfigProblem> problems) {
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(problems, "problems");
        Material used = material == null ? fallback : material;
        if (!used.isItem() || used.isAir()) {
            report(problems, "names the material " + written(used) + ", which is not an item, so "
                + written(fallback) + " is used");
            used = fallback;
        }
        return apply(new ItemBuilder(used), renderer, logger, problems);
    }

    /**
     * A builder that starts from {@code base}, with every part of this record applied to it as
     * {@link #overMaterial} applies it. {@link #material()} is not used, and the stack keeps each property the
     * file does not name, as {@link ItemBuilder#ItemBuilder(ItemStack)} states.
     *
     * <p>The problems and the thread rule are those of {@link #overMaterial}, without the material.
     *
     * @param base the stack to start from, such as the item a reference stands for
     * @param renderer turns a MiniMessage text of the file into a component
     * @param logger the logger {@link ItemBuilder#customModelData} warns through
     * @param problems receives each problem on the calling thread
     * @throws NullPointerException when an argument is {@code null}
     * @since 0.4.14
     */
    public ItemBuilder overStack(
        ItemStack base, Function<String, Component> renderer, Logger logger, Consumer<ConfigProblem> problems) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(problems, "problems");
        return apply(new ItemBuilder(base), renderer, logger, problems);
    }

    private ItemBuilder apply(
        ItemBuilder builder, Function<String, Component> renderer, Logger logger, Consumer<ConfigProblem> problems) {
        if (name != null) {
            builder.name(renderer.apply(name));
        }
        if (!lore.isEmpty()) {
            builder.lore(lore.stream().map(renderer).toList());
        }
        if (glow) {
            builder.glow(true);
        }
        if (itemModel != null) {
            builder.itemModel(itemModel);
        }
        if (!customModelData.isEmpty()) {
            builder.customModelData(customModelData, logger);
        }
        enchantments.forEach((key, level) -> {
            Enchantment enchantment = find(RegistryKey.ENCHANTMENT, key);
            if (enchantment == null) {
                report(problems, "names the enchantment \"" + key + "\", which this server does not have, so it"
                    + " is skipped");
            } else {
                builder.enchant(enchantment, level);
            }
        });
        if (trim != null) {
            TrimMaterial trimMaterial = find(RegistryKey.TRIM_MATERIAL, trim.material());
            TrimPattern trimPattern = find(RegistryKey.TRIM_PATTERN, trim.pattern());
            if (trimMaterial == null) {
                report(problems, "names the trim material \"" + trim.material()
                    + "\", which this server does not have, so no trim is set");
            }
            if (trimPattern == null) {
                report(problems, "names the trim pattern \"" + trim.pattern()
                    + "\", which this server does not have, so no trim is set");
            }
            if (trimMaterial != null && trimPattern != null) {
                builder.trim(new ArmorTrim(trimMaterial, trimPattern));
            }
        }
        return builder;
    }

    private static <T extends Keyed> @Nullable T find(RegistryKey<T> registry, String text) {
        NamespacedKey key = NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(registry).get(key);
    }

    private void report(Consumer<ConfigProblem> problems, String message) {
        problems.accept(new ConfigProblem(file, path, ConfigProblem.Severity.WARNING, path + " " + message + ".",
            null));
    }

    private static String written(Material material) {
        return material.getKey().getKey();
    }
}
