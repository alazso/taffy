package so.alaz.taffy.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import so.alaz.taffy.platform.ItemBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static so.alaz.taffy.config.ConfigProblem.Severity.WARNING;

/** Builds from an {@link ItemSpec} against mocked registries and a mocked {@link ItemBuilder}. */
final class ItemSpecTest {

    private static final Function<String, Component> RENDERER = text -> Component.text("rendered " + text);
    private static final Logger LOGGER = Logger.getLogger(ItemSpecTest.class.getName());

    private final Material paper = material("paper", true, false);
    private final Material stick = material("stick", true, false);
    private final List<ConfigProblem> problems = new ArrayList<>();
    private final List<List<?>> constructorArguments = new ArrayList<>();
    private final Registry<Enchantment> enchantments = mock();
    private final Registry<TrimMaterial> trimMaterials = mock();
    private final Registry<TrimPattern> trimPatterns = mock();
    private final Enchantment sharpness = mock();
    private final TrimMaterial quartz = mock();
    private final TrimPattern sentry = mock();
    private MockedStatic<RegistryAccess> access;
    private MockedConstruction<ItemBuilder> builders;

    // Enchantment, TrimMaterial and TrimPattern read their constants from the server registries when the classes
    // load, and a mock of one loads its class.
    @BeforeAll
    static void loadRegistryConstants() {
        try (MockedStatic<RegistryAccess> access = mockStatic(RegistryAccess.class)) {
            RegistryAccess registries = mock(RegistryAccess.class, call -> mock(Registry.class, lookup -> {
                if (!lookup.getMethod().getName().equals("getOrThrow")) {
                    return null;
                }
                Object registry = call.getArgument(0);
                return registry == RegistryKey.TRIM_MATERIAL ? mock(TrimMaterial.class)
                    : registry == RegistryKey.TRIM_PATTERN ? mock(TrimPattern.class) : mock(Enchantment.class);
            }));
            access.when(RegistryAccess::registryAccess).thenReturn(registries);

            assertThat(Enchantment.UNBREAKING).isNotNull();
            assertThat(TrimMaterial.QUARTZ).isNotNull();
            assertThat(TrimPattern.SENTRY).isNotNull();
        }
    }

    @BeforeEach
    void serveRegistries() {
        RegistryAccess registries = mock();
        when(registries.getRegistry(RegistryKey.ENCHANTMENT)).thenReturn(enchantments);
        when(registries.getRegistry(RegistryKey.TRIM_MATERIAL)).thenReturn(trimMaterials);
        when(registries.getRegistry(RegistryKey.TRIM_PATTERN)).thenReturn(trimPatterns);
        when(enchantments.get(NamespacedKey.minecraft("sharpness"))).thenReturn(sharpness);
        when(trimMaterials.get(NamespacedKey.minecraft("quartz"))).thenReturn(quartz);
        when(trimPatterns.get(NamespacedKey.minecraft("sentry"))).thenReturn(sentry);
        access = mockStatic(RegistryAccess.class);
        access.when(RegistryAccess::registryAccess).thenReturn(registries);
        builders = mockConstruction(ItemBuilder.class,
            (builder, context) -> constructorArguments.add(context.arguments()));
    }

    @AfterEach
    void close() {
        builders.close();
        access.close();
    }

    @Test
    void appliesEveryPartOverTheMaterialOfTheFile() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("sharpness", 5);
        levels.put("minecraft:sharpness", 2);
        ItemSpec spec = new ItemSpec("crate.yml", "key", stick, "nexo:ruby", "<red>Name", List.of("one", "two"), true,
            new NamespacedKey("slate", "vote_key"), List.of(1.5f, 2f), levels, new ItemSpec.Trim("quartz", "sentry"));

        ItemBuilder builder = spec.overMaterial(paper, RENDERER, LOGGER, problems::add);

        assertThat(builder).isSameAs(builders.constructed().getFirst());
        assertThat(constructorArguments).containsExactly(List.of(stick));
        verify(builder).name(Component.text("rendered <red>Name"));
        verify(builder).lore(List.of(Component.text("rendered one"), Component.text("rendered two")));
        verify(builder).glow(true);
        verify(builder).itemModel(new NamespacedKey("slate", "vote_key"));
        verify(builder).customModelData(List.of(1.5f, 2f), LOGGER);
        verify(builder).enchant(sharpness, 5);
        verify(builder).enchant(sharpness, 2);
        verify(builder).trim(new ArmorTrim(quartz, sentry));
        verifyNoMoreInteractions(builder);
        assertThat(problems).isEmpty();
    }

    @Test
    void aSpecWithNothingSetCallsNoSetter() {
        ItemBuilder builder = empty("item").overMaterial(paper, RENDERER, LOGGER, problems::add);

        assertThat(constructorArguments).containsExactly(List.of(paper));
        verifyNoMoreInteractions(builder);
        access.verifyNoInteractions();
        assertThat(problems).isEmpty();
    }

    @Test
    void aMaterialThatIsNoItemOrIsAirIsAProblemAndTheFallbackIsUsed() {
        Material water = material("water", false, false);
        Material air = material("cave_air", true, true);

        with("item", water, Map.of(), null).overMaterial(paper, RENDERER, LOGGER, problems::add);
        with("other", air, Map.of(), null).overMaterial(paper, RENDERER, LOGGER, problems::add);

        assertThat(constructorArguments).containsExactly(List.of(paper), List.of(paper));
        assertThat(problems).containsExactly(
            new ConfigProblem("crate.yml", "item", WARNING,
                "item names the material water, which is not an item, so paper is used.", null),
            new ConfigProblem("crate.yml", "other", WARNING,
                "other names the material cave_air, which is not an item, so paper is used.", null));
    }

    @Test
    void anUnknownEnchantmentIsAProblemAndIsSkipped() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("sharpnes", 5);
        levels.put("not a key", 1);
        levels.put("SHARPNESS", 3);

        ItemBuilder builder = with("rewards[1].item", paper, levels, null)
            .overMaterial(paper, RENDERER, LOGGER, problems::add);

        verify(builder).enchant(sharpness, 3);
        verifyNoMoreInteractions(builder);
        assertThat(problems).containsExactly(
            new ConfigProblem("crate.yml", "rewards[1].item", WARNING, "rewards[1].item names the enchantment"
                + " \"sharpnes\", which this server does not have, so it is skipped.", null),
            new ConfigProblem("crate.yml", "rewards[1].item", WARNING, "rewards[1].item names the enchantment"
                + " \"not a key\", which this server does not have, so it is skipped.", null));
    }

    @Test
    void anUnknownTrimKeyIsAProblemAndNoTrimIsSet() {
        ItemBuilder material = with("a", paper, Map.of(), new ItemSpec.Trim("quarts", "sentry"))
            .overMaterial(paper, RENDERER, LOGGER, problems::add);
        ItemBuilder pattern = with("b", paper, Map.of(), new ItemSpec.Trim("quartz", "sentri"))
            .overMaterial(paper, RENDERER, LOGGER, problems::add);
        ItemBuilder both = with("c", paper, Map.of(), new ItemSpec.Trim("x y", "sentri"))
            .overMaterial(paper, RENDERER, LOGGER, problems::add);

        verifyNoMoreInteractions(material, pattern, both);
        assertThat(problems).extracting(ConfigProblem::message).containsExactly(
            "a names the trim material \"quarts\", which this server does not have, so no trim is set.",
            "b names the trim pattern \"sentri\", which this server does not have, so no trim is set.",
            "c names the trim material \"x y\", which this server does not have, so no trim is set.",
            "c names the trim pattern \"sentri\", which this server does not have, so no trim is set.");
    }

    @Test
    void overStackStartsFromTheBaseAndIgnoresTheMaterial() {
        ItemStack base = mock();
        Material water = material("water", false, false);
        ItemSpec spec = new ItemSpec(null, "item", water, "nexo:ruby", "Name", List.of(), false, null, List.of(),
            Map.of("sharpness", 1, "nope", 1), null);

        ItemBuilder builder = spec.overStack(base, RENDERER, LOGGER, problems::add);

        assertThat(constructorArguments).containsExactly(List.of(base));
        verify(builder).name(Component.text("rendered Name"));
        verify(builder).enchant(sharpness, 1);
        verifyNoMoreInteractions(builder);
        assertThat(problems).containsExactly(new ConfigProblem(null, "item", WARNING,
            "item names the enchantment \"nope\", which this server does not have, so it is skipped.", null));
    }

    @Test
    void aNullArgumentIsRefused() {
        ItemSpec spec = empty("item");
        ItemStack base = mock();

        assertThatThrownBy(() -> spec.overMaterial(null, RENDERER, LOGGER, problems::add)).hasMessage("fallback");
        assertThatThrownBy(() -> spec.overMaterial(paper, null, LOGGER, problems::add)).hasMessage("renderer");
        assertThatThrownBy(() -> spec.overMaterial(paper, RENDERER, null, problems::add)).hasMessage("logger");
        assertThatThrownBy(() -> spec.overMaterial(paper, RENDERER, LOGGER, null)).hasMessage("problems");
        assertThatThrownBy(() -> spec.overStack(null, RENDERER, LOGGER, problems::add)).hasMessage("base");
        assertThatThrownBy(() -> spec.overStack(base, null, LOGGER, problems::add)).hasMessage("renderer");
        assertThatThrownBy(() -> spec.overStack(base, RENDERER, null, problems::add)).hasMessage("logger");
        assertThatThrownBy(() -> spec.overStack(base, RENDERER, LOGGER, null)).hasMessage("problems");
        assertThat(constructorArguments).isEmpty();
    }

    private static ItemSpec empty(String path) {
        return new ItemSpec(null, path, null, null, null, List.of(), false, null, List.of(), Map.of(), null);
    }

    private static ItemSpec with(
        String path, Material material, Map<String, Integer> levels, ItemSpec.Trim trim) {
        return new ItemSpec("crate.yml", path, material, null, null, List.of(), false, null, List.of(), levels, trim);
    }

    private static Material material(String key, boolean item, boolean air) {
        Material material = mock();
        when(material.isItem()).thenReturn(item);
        when(material.isAir()).thenReturn(air);
        when(material.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return material;
    }
}
