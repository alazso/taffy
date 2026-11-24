package so.alaz.taffy.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static so.alaz.taffy.config.ConfigProblem.Severity.ERROR;
import static so.alaz.taffy.config.ConfigProblem.Severity.WARNING;

/** Reads item definitions with no server and no registry behind the Bukkit classes. */
final class ConfigReaderItemTest {

    private static final ItemKeys PROVOUCHERS = ItemKeys.defaults()
        .reference("custom")
        .name("display-name")
        .textBesideSection(true);

    private static final ItemKeys SLATE = ItemKeys.defaults()
        .reference(null)
        .referenceParts("provider", "providerItemId")
        .itemModel("customItemId")
        .customModelData("customModelData")
        .glow("glowing")
        .enchantments(null)
        .trim(null);

    private final List<String> warnings = new ArrayList<>();

    @Test
    void readsEveryPartWithTheDefaultKeys() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: diamond_sword
              reference: " nexo:frostbite "
              name: "<aqua>Frostbite"
              lore: ["<gray>Cold to the touch.", ""]
              glow: true
              item-model: "myserver:frostbite"
              custom-model-data: [1, 2.5]
              enchantments:
                Sharpness: 5
                minecraft:unbreaking: 3
              trim:
                material: Quartz
                pattern: minecraft:sentry
            """);

        ItemSpec spec = reader.item("item");

        assertThat(spec).isEqualTo(new ItemSpec(null, "item", Material.DIAMOND_SWORD, "nexo:frostbite",
            "<aqua>Frostbite", List.of("<gray>Cold to the touch.", ""), true,
            new NamespacedKey("myserver", "frostbite"), List.of(1f, 2.5f),
            Map.of("sharpness", 5, "minecraft:unbreaking", 3), new ItemSpec.Trim("quartz", "minecraft:sentry")));
        assertThat(spec.enchantments().keySet()).containsExactly("sharpness", "minecraft:unbreaking");
        assertThat(warnings).isEmpty();
        assertThat(reader.errors()).isEmpty();
        assertThat(reader.unreadKeys()).isEmpty();
    }

    @Test
    void readsTheMaterialInAnyCaseAndWithTheMinecraftNamespace() throws Exception {
        ConfigReader reader = reader("""
            upper: {material: PAPER}
            lower: {material: paper}
            spaced: {material: "  Paper "}
            namespaced: {material: "minecraft:paper"}
            shouted: {material: "MINECRAFT:PAPER"}
            """);

        for (String path : List.of("upper", "lower", "spaced", "namespaced", "shouted")) {
            assertThat(reader.item(path).material()).as(path).isEqualTo(Material.PAPER);
        }
        assertThat(reader.problems()).isEmpty();
    }

    @Test
    void anAbsentSectionGivesNullAndNoProblem() throws Exception {
        ConfigReader reader = reader("""
            display-name: "<gold>Starter"
            blank: ""
            """);

        assertThat(reader.item("item")).isNull();
        assertThat(reader.item("item", PROVOUCHERS)).isNull();
        assertThat(reader.item("blank")).isNull();
        assertThat(reader.problems()).isEmpty();
        assertThat(reader.readPaths()).containsExactly("item", "blank");
    }

    @Test
    void aSectionThatIsAScalarOrAListIsAnError() throws Exception {
        ConfigReader reader = reader("""
            scalar: paper
            list: [paper]
            """);

        assertThat(reader.item("scalar")).isNull();
        assertThat(reader.item("list")).isNull();

        assertThat(reader.errors()).containsExactly(
            "scalar is \"paper\"; expected a group of keys.",
            "list is [paper]; expected a group of keys.");
        assertThat(reader.problems()).extracting(ConfigProblem::path).containsExactly("scalar", "list");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anEmptySectionGivesASpecWithNothingSet() throws Exception {
        ConfigReader reader = reader("item: {}\n");

        assertThat(reader.item("item")).isEqualTo(
            new ItemSpec(null, "item", null, null, null, List.of(), false, null, List.of(), Map.of(), null));
        assertThat(reader.problems()).isEmpty();
    }

    @Test
    void anUnknownMaterialIsAnErrorAndGivesNull() throws Exception {
        ConfigReader reader = reader("""
            typo: {material: papr}
            legacy: {material: LEGACY_STONE}
            other: {material: "nexo:paper"}
            number: {material: 7}
            list: {material: [paper]}
            """);

        for (String path : List.of("typo", "legacy", "other", "number", "list")) {
            assertThat(reader.item(path)).as(path).isNull();
        }

        String expected = "; expected the name of an item such as paper or minecraft:paper.";
        assertThat(reader.errors()).containsExactly(
            "typo.material is \"papr\"" + expected,
            "legacy.material is \"LEGACY_STONE\"" + expected,
            "other.material is \"nexo:paper\"" + expected,
            "number.material is 7" + expected,
            "list.material is [paper]" + expected);
        assertThat(reader.problems()).extracting(ConfigProblem::severity).containsOnly(ERROR);
        assertThat(warnings).isEmpty();
    }

    @Test
    void anErrorStillReadsAndReportsTheOtherKeys() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: papr
              glow: maybe
              enchantments:
                sharpness: 0
            """);

        assertThat(reader.item("item")).isNull();

        assertThat(reader.errors()).hasSize(1);
        assertThat(warnings).containsExactly(
            "item.glow is \"maybe\"; expected true or false, so false is used.",
            "item.enchantments.sharpness is 0; expected a whole number of 1 or more, so the enchantment is"
                + " skipped.");
        assertThat(reader.unreadKeys()).isEmpty();
    }

    @Test
    void aBadLevelSkipsOnlyThatEnchantment() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: paper
              enchantments:
                sharpness: 5
                zero: 0
                negative: -2
                decimal: 2.5
                word: high
                huge: 3000000000
                group:
                  level: 3
            """);

        ItemSpec spec = reader.item("item");

        assertThat(spec.enchantments()).containsExactly(Map.entry("sharpness", 5));
        String expected = "; expected a whole number of 1 or more, so the enchantment is skipped.";
        assertThat(warnings).containsExactly(
            "item.enchantments.zero is 0" + expected,
            "item.enchantments.negative is -2" + expected,
            "item.enchantments.decimal is 2.5" + expected,
            "item.enchantments.word is \"high\"" + expected,
            "item.enchantments.huge is 3000000000" + expected,
            "item.enchantments.group is a group of keys" + expected);
        assertThat(reader.problems()).extracting(ConfigProblem::severity).containsOnly(WARNING);
        assertThat(reader.problems()).extracting(ConfigProblem::path).first().isEqualTo("item.enchantments.zero");
        assertThat(reader.errors()).isEmpty();
    }

    @Test
    void enchantmentsAndTrimThatAreNotGroupsOfKeysAreIgnored() throws Exception {
        ConfigReader reader = reader("""
            item:
              enchantments: sharpness
              trim: quartz
            """);

        ItemSpec spec = reader.item("item");

        assertThat(spec.enchantments()).isEmpty();
        assertThat(spec.trim()).isNull();
        assertThat(warnings).containsExactly(
            "item.enchantments should hold keys beneath it, so its value is ignored.",
            "item.trim should hold keys beneath it, so its value is ignored.");
    }

    @Test
    void aTrimNeedsBothKeys() throws Exception {
        ConfigReader reader = reader("""
            half:
              trim:
                material: quartz
            wrong:
              trim:
                material: quartz
                pattern: [sentry]
            empty:
              trim: {}
            """);

        assertThat(reader.item("half").trim()).isNull();
        assertThat(reader.item("wrong").trim()).isNull();
        assertThat(reader.item("empty").trim()).isNull();
        assertThat(warnings).containsExactly(
            "half.trim needs a material and a pattern, such as quartz and sentry, so no trim is set.",
            "wrong.trim needs a material and a pattern, such as quartz and sentry, so no trim is set.",
            "empty.trim needs a material and a pattern, such as quartz and sentry, so no trim is set.");
        assertThat(reader.unreadKeys()).isEmpty();
    }

    @Test
    void customModelDataIsOneNumberOrAListOfNumbers() throws Exception {
        ConfigReader reader = reader("""
            whole: {custom-model-data: 1001}
            decimal: {custom-model-data: 0.5}
            list: {custom-model-data: [1, x, 2.5, .inf, [3]]}
            text: {custom-model-data: many}
            """);

        assertThat(reader.item("whole").customModelData()).containsExactly(1001f);
        assertThat(reader.item("decimal").customModelData()).containsExactly(0.5f);
        assertThat(reader.item("list").customModelData()).containsExactly(1f, 2.5f);
        assertThat(reader.item("text").customModelData()).isEmpty();
        assertThat(warnings).containsExactly(
            "list.custom-model-data has \"x\", which is not a number, so it is skipped.",
            "list.custom-model-data has Infinity, which is not a number, so it is skipped.",
            "list.custom-model-data has [3], which is not a number, so it is skipped.",
            "text.custom-model-data is \"many\"; expected a number or a list of numbers such as [1, 2.5], so no"
                + " custom model data is set.");
        assertThat(reader.errors()).isEmpty();
    }

    @Test
    void aBadNameItemModelOrLoreIsAWarning() throws Exception {
        ConfigReader reader = reader("""
            item:
              name: [a, b]
              lore: one line
              item-model: "Not A Key"
            number:
              name: 7
              item-model: 7
            colon:
              item-model: ":"
            """);

        ItemSpec spec = reader.item("item");
        ItemSpec number = reader.item("number");
        assertThat(reader.item("colon").itemModel()).isNull();

        assertThat(spec.name()).isNull();
        assertThat(spec.lore()).isEmpty();
        assertThat(spec.itemModel()).isNull();
        assertThat(number.name()).isEqualTo("7");
        assertThat(number.itemModel()).isNull();
        assertThat(warnings).containsExactly(
            "item.name is [a, b]; expected a single text, so no name is set.",
            "item.lore is \"one line\"; expected a list such as [A, B], so [] is used.",
            "item.item-model is \"Not A Key\"; expected a key such as minecraft:diamond_sword, so no item model"
                + " is set.",
            "number.item-model is 7; expected a key such as minecraft:diamond_sword, so no item model is set.",
            "colon.item-model is \":\"; expected a key such as minecraft:diamond_sword, so no item model is"
                + " set.");
        assertThat(reader.errors()).isEmpty();
    }

    @Test
    void aReferenceThatIsNotASingleValueIsAnError() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: paper
              reference: [a, b]
            """);

        assertThat(reader.item("item")).isNull();
        assertThat(reader.errors()).containsExactly(
            "item.reference is [a, b]; expected an item reference such as serialized:<base64> or"
                + " nexo:ruby_sword.");
    }

    @Test
    void aKeyTheKeyNamesDoNotNameStaysUnread() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: paper
              damage: 3
              skull:
                source: name
            """);

        assertThat(reader.item("item")).isNotNull();
        assertThat(reader.problems()).isEmpty();
        assertThat(reader.unreadKeys()).containsExactly("item.damage", "item.skull.source");
    }

    @Test
    void aNullKeyNameIsNotRead() throws Exception {
        ConfigReader reader = reader("""
            item:
              material: paper
              reference: "nexo:ruby"
              name: N
              lore: [L]
              glow: true
              item-model: "a:b"
              custom-model-data: 1
              enchantments: {sharpness: 1}
              trim: {material: quartz, pattern: sentry}
            """);
        ItemKeys none = ItemKeys.defaults().material(null).reference(null).name(null).lore(null).glow(null)
            .itemModel(null).customModelData(null).enchantments(null).trim(null);

        assertThat(reader.item("item", none)).isEqualTo(
            new ItemSpec(null, "item", null, null, null, List.of(), false, null, List.of(), Map.of(), null));
        assertThat(reader.readPaths()).containsExactly("item");
        assertThat(reader.problems()).isEmpty();
    }

    @Test
    void readsTheKeysOfAProVouchersVoucherFile() throws Exception {
        ConfigReader reader = reader("""
            display-name: "<gradient:#FFD700:#FF8A00><b>Example Voucher</b></gradient>"
            lore:
              - "<gray>Right-click to redeem."
              - ""
            item:
              material: minecraft:paper
              glow: true
              custom-model-data: 1001
              enchantments:
                sharpness: 5
              trim:
                material: quartz
                pattern: sentry
              item-model: "minecraft:diamond_sword"
              custom: "itemsadder:pack:id"
              hide-tooltip: false
            cooldown: 0
            """);

        ItemSpec spec = reader.item("item", PROVOUCHERS);

        assertThat(spec).isEqualTo(new ItemSpec(null, "item", Material.PAPER, "itemsadder:pack:id",
            "<gradient:#FFD700:#FF8A00><b>Example Voucher</b></gradient>", List.of("<gray>Right-click to redeem.", ""),
            true, NamespacedKey.minecraft("diamond_sword"), List.of(1001f), Map.of("sharpness", 5),
            new ItemSpec.Trim("quartz", "sentry")));
        assertThat(reader.problems()).isEmpty();
        assertThat(reader.unreadKeys()).containsExactly("item.hide-tooltip", "cooldown");
    }

    @Test
    void readsTextBesideANestedSection() throws Exception {
        ConfigReader reader = reader("""
            vouchers:
              gold:
                display-name: Gold
                lore: [Shiny]
                item:
                  material: gold_ingot
                  display-name: ignored
            """);

        ItemSpec spec = reader.item("vouchers.gold.item", PROVOUCHERS);

        assertThat(spec.name()).isEqualTo("Gold");
        assertThat(spec.lore()).containsExactly("Shiny");
        assertThat(reader.unreadKeys()).containsExactly("vouchers.gold.item.display-name");
    }

    @Test
    void readsTheKeysOfASlateCrateFile() throws Exception {
        ConfigReader reader = reader("""
            name: Vote
            key:
              name: "<yellow>Vote Key</yellow>"
              lore: ["<gray>Opens the vote crate."]
              material: TRIPWIRE_HOOK
              customModelData: [1.5, 2]
              customItemId: "slate:vote_key"
              provider: Nexo
              providerItemId: vote_key
              glowing: true
            """);

        ItemSpec spec = reader.item("key", SLATE);

        assertThat(spec).isEqualTo(new ItemSpec(null, "key", Material.TRIPWIRE_HOOK, "nexo:vote_key",
            "<yellow>Vote Key</yellow>", List.of("<gray>Opens the vote crate."), true,
            new NamespacedKey("slate", "vote_key"), List.of(1.5f, 2f), Map.of(), null));
        assertThat(reader.problems()).isEmpty();
        assertThat(reader.unreadKeys()).containsExactly("name");
    }

    @Test
    void joinsTheTwoPartsOfAReference() throws Exception {
        ConfigReader reader = reader("""
            vanilla: {provider: vanilla}
            bare: {providerItemId: "oraxen:ruby"}
            prefixed: {provider: oraxen, providerItemId: "Oraxen:ruby"}
            whole: {reference: "serialized:AAAA", provider: nexo, providerItemId: ruby}
            list: {provider: [nexo], providerItemId: ruby}
            """);
        ItemKeys keys = ItemKeys.defaults().referenceParts("provider", "providerItemId");

        assertThat(reader.item("vanilla", keys).reference()).isNull();
        assertThat(reader.item("bare", keys).reference()).isEqualTo("oraxen:ruby");
        assertThat(reader.item("prefixed", keys).reference()).isEqualTo("Oraxen:ruby");
        assertThat(reader.item("whole", keys).reference()).isEqualTo("serialized:AAAA");
        assertThat(reader.unreadKeys()).containsExactly("list.provider", "list.providerItemId");
        assertThat(reader.item("list", keys)).isNull();
        assertThat(reader.errors()).containsExactly(
            "list.provider is [nexo]; expected the name of the plugin that provides the item, such as nexo.");
    }

    @Test
    void oneNullReferencePartTurnsBothOff() throws Exception {
        ConfigReader reader = reader("item: {provider: nexo, providerItemId: ruby}\n");

        assertThat(reader.item("item", ItemKeys.defaults().referenceParts("provider", null)).reference()).isNull();
        assertThat(reader.item("item", ItemKeys.defaults().referenceParts(null, "providerItemId")).reference())
            .isNull();
        assertThat(reader.unreadKeys()).containsExactly("item.provider", "item.providerItemId");
    }

    @Test
    void aSpecOfAListEntryCarriesTheFileAndTheEntryPath() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            rewards:
              - item:
                  material: paper
              - item:
                  material: papr
            """);
        ConfigReader reader = new ConfigReader(yaml, "crate.yml", warnings::add);
        List<ConfigReader> rewards = reader.sections("rewards");

        ItemSpec spec = rewards.get(0).item("item");

        assertThat(spec.file()).isEqualTo("crate.yml");
        assertThat(spec.path()).isEqualTo("rewards[0].item");
        assertThat(rewards.get(1).item("item")).isNull();
        assertThat(reader.problems()).containsExactly(new ConfigProblem("crate.yml", "rewards[1].item.material",
            ERROR, "rewards[1].item.material is \"papr\"; expected the name of an item such as paper or"
                + " minecraft:paper.", null));
    }

    @Test
    void aBlankKeyNameIsRefused() {
        assertThatThrownBy(() -> ItemKeys.defaults().material(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a key name must not be blank");
        assertThatThrownBy(() -> ItemKeys.defaults().referenceParts(null, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader("item: {}\n").item("item", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("keys");
    }

    @Test
    void aSpecCopiesItsCollectionsAndKeepsTheEnchantmentOrder() {
        List<String> lore = new ArrayList<>(List.of("a"));
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("unbreaking", 3);
        levels.put("sharpness", 5);

        ItemSpec spec = new ItemSpec(null, "item", null, null, null, lore, false, null, List.of(1f), levels, null);
        lore.add("b");
        levels.clear();

        assertThat(spec.lore()).containsExactly("a");
        assertThat(spec.enchantments().keySet()).containsExactly("unbreaking", "sharpness");
        assertThatThrownBy(() -> spec.enchantments().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ItemSpec(null, null, null, null, null, lore, false, null, List.of(), levels, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("path");
        assertThatThrownBy(() -> new ItemSpec.Trim("quartz", null)).isInstanceOf(NullPointerException.class);
    }

    private ConfigReader reader(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return new ConfigReader(yaml, warnings::add);
    }
}
