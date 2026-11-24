package so.alaz.taffy.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads an {@link ItemSpec} out of a group of keys through a {@link ConfigReader}. */
final class ItemSpecReader {

    private static final String MINECRAFT = "minecraft:";

    private ItemSpecReader() {
    }

    static @Nullable ItemSpec read(ConfigReader reader, String path, ItemKeys keys) {
        Object raw = reader.value(path);
        if (blank(raw)) {
            return null;
        }
        if (!(raw instanceof ConfigurationSection)) {
            reader.error(path, "is " + ConfigReader.describe(raw) + "; expected a group of keys", null);
            return null;
        }
        int errors = reader.errors().size();
        String inside = path + ".";
        String text = keys.textBesideSection ? path.substring(0, path.lastIndexOf('.') + 1) : inside;
        Material material = keys.material == null ? null : material(reader, inside + keys.material);
        String reference = reference(reader, inside, keys);
        String name = keys.name == null ? null : name(reader, text + keys.name);
        List<String> lore = keys.lore == null ? List.of() : reader.strings(text + keys.lore, List.of());
        boolean glow = keys.glow != null && reader.bool(inside + keys.glow, false);
        NamespacedKey itemModel = keys.itemModel == null ? null : itemModel(reader, inside + keys.itemModel);
        List<Float> customModelData = keys.customModelData == null
            ? List.of() : customModelData(reader, inside + keys.customModelData);
        Map<String, Integer> enchantments = keys.enchantments == null
            ? Map.of() : enchantments(reader, inside + keys.enchantments);
        ItemSpec.Trim trim = keys.trim == null ? null : trim(reader, inside + keys.trim);
        if (reader.errors().size() > errors) {
            return null;
        }
        return new ItemSpec(reader.source(), reader.at(path), material, reference, name, lore, glow, itemModel,
            customModelData, enchantments, trim);
    }

    private static boolean blank(@Nullable Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static @Nullable Material material(ConfigReader reader, String path) {
        Object value = reader.value(path);
        if (blank(value)) {
            return null;
        }
        Material material = value instanceof String text ? material(text.trim()) : null;
        if (material == null) {
            reader.error(path, "is " + ConfigReader.describe(value)
                + "; expected the name of an item such as paper or minecraft:paper", null);
        }
        return material;
    }

    private static @Nullable Material material(String text) {
        String name = text.regionMatches(true, 0, MINECRAFT, 0, MINECRAFT.length())
            ? text.substring(MINECRAFT.length()) : text;
        Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        return material == null || material.isLegacy() ? null : material;
    }

    private static @Nullable String reference(ConfigReader reader, String inside, ItemKeys keys) {
        String whole = keys.reference == null ? null : trimmed(reader.strictText(inside + keys.reference, null,
            "an item reference such as serialized:<base64> or nexo:ruby_sword"));
        if (keys.referenceProvider == null || keys.referenceId == null) {
            return whole;
        }
        String provider = trimmed(reader.strictText(inside + keys.referenceProvider, null,
            "the name of the plugin that provides the item, such as nexo"));
        String id = trimmed(reader.strictText(inside + keys.referenceId, null,
            "the id of the item in the plugin that provides it"));
        if (whole != null || id == null) {
            return whole;
        }
        String prefix = provider == null ? null : provider.toLowerCase(Locale.ROOT) + ":";
        return prefix == null || id.regionMatches(true, 0, prefix, 0, prefix.length()) ? id : prefix + id;
    }

    private static @Nullable String trimmed(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static @Nullable String name(ConfigReader reader, String path) {
        Object value = reader.value(path);
        if (blank(value)) {
            return null;
        }
        String text = ConfigReader.singleText(value);
        if (text == null) {
            reader.warn(path, "is " + ConfigReader.describe(value) + "; expected a single text, so no name is set",
                null);
        }
        return text;
    }

    private static @Nullable NamespacedKey itemModel(ConfigReader reader, String path) {
        Object value = reader.value(path);
        if (blank(value)) {
            return null;
        }
        NamespacedKey key = value instanceof String text ? NamespacedKey.fromString(text.trim()) : null;
        if (key == null || key.getKey().isEmpty()) {
            reader.warn(path, "is " + ConfigReader.describe(value)
                + "; expected a key such as minecraft:diamond_sword, so no item model is set", null);
            return null;
        }
        return key;
    }

    private static List<Float> customModelData(ConfigReader reader, String path) {
        Object value = reader.value(path);
        if (blank(value)) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Float> floats = new ArrayList<>();
            for (Object entry : list) {
                Float number = finiteFloat(entry);
                if (number == null) {
                    reader.warn(path, "has " + (entry == null ? "an empty entry" : ConfigReader.describe(entry))
                        + ", which is not a number, so it is skipped", null);
                } else {
                    floats.add(number);
                }
            }
            return floats;
        }
        Float number = finiteFloat(value);
        if (number == null) {
            reader.warn(path, "is " + ConfigReader.describe(value)
                + "; expected a number or a list of numbers such as [1, 2.5], so no custom model data is set", null);
            return List.of();
        }
        return List.of(number);
    }

    private static @Nullable Float finiteFloat(@Nullable Object value) {
        return value instanceof Number number && Float.isFinite(number.floatValue()) ? number.floatValue() : null;
    }

    private static Map<String, Integer> enchantments(ConfigReader reader, String path) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String key : reader.children(path)) {
            String levelPath = path + "." + key;
            Object value = reader.value(levelPath);
            if (value instanceof Integer level && level >= 1) {
                levels.put(key.toLowerCase(Locale.ROOT), level);
            } else {
                reader.warn(levelPath, "is " + ConfigReader.describe(value)
                    + "; expected a whole number of 1 or more, so the enchantment is skipped", null);
            }
        }
        return levels;
    }

    private static ItemSpec.@Nullable Trim trim(ConfigReader reader, String path) {
        if (reader.children(path).isEmpty() && !reader.isSection(path)) {
            return null;
        }
        String material = key(reader.value(path + ".material"));
        String pattern = key(reader.value(path + ".pattern"));
        if (material == null || pattern == null) {
            reader.warn(path, "needs a material and a pattern, such as quartz and sentry, so no trim is set", null);
            return null;
        }
        return new ItemSpec.Trim(material, pattern);
    }

    private static @Nullable String key(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim().toLowerCase(Locale.ROOT) : null;
    }
}
