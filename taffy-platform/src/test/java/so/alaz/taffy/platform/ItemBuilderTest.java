package so.alaz.taffy.platform;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@SuppressWarnings("deprecation")
class ItemBuilderTest {

    private static final ItemBuilder.ModelData NO_MODEL_DATA = new ItemBuilder.ModelData((item, values) -> {
        throw new AssertionError("custom model data was written");
    });

    // Enchantment, DataComponentTypes and Registry read their constants from the server registries when the
    // classes load, and a mock of Enchantment or Registry loads its class.
    @BeforeAll
    static void loadRegistryConstants() {
        try (MockedStatic<RegistryAccess> access = mockStatic(RegistryAccess.class)) {
            RegistryAccess registries = mock(RegistryAccess.class, call -> mock(Registry.class, lookup -> {
                if (!lookup.getMethod().getName().equals("getOrThrow")) {
                    return null;
                }
                return call.getArgument(0) == RegistryKey.DATA_COMPONENT_TYPE
                    ? mock(DataComponentType.Valued.class,
                        withSettings().extraInterfaces(DataComponentType.NonValued.class))
                    : mock(Enchantment.class);
            }));
            access.when(RegistryAccess::registryAccess).thenReturn(registries);

            assertThat(Enchantment.UNBREAKING).isNotNull().isNotSameAs(Enchantment.MENDING);
            assertThat(DataComponentTypes.CUSTOM_MODEL_DATA).isNotNull().isNotSameAs(DataComponentTypes.ITEM_MODEL);
        }
    }

    @Nested
    class Build {

        private ItemMeta meta = mock(ItemMeta.class);
        private final Material type = mock();
        private final List<List<?>> constructorArguments = new ArrayList<>();
        private MockedConstruction<ItemStack> stacks;

        // Every new ItemStack on this thread is a mock whose editMeta runs the edit against the shared meta.
        @BeforeEach
        void interceptNewStacks() {
            stacks = mockConstruction(ItemStack.class, (stack, context) -> {
                constructorArguments.add(context.arguments());
                when(stack.getType()).thenReturn(type);
                when(stack.editMeta(any())).thenAnswer(call -> {
                    Consumer<ItemMeta> edit = call.getArgument(0);
                    edit.accept(meta);
                    return true;
                });
            });
        }

        @AfterEach
        void close() {
            stacks.close();
        }

        @Test
        void materialAndAmountReachTheStackThatIsReturned() {
            ItemStack built = new ItemBuilder(Material.DIAMOND).amount(5).build();

            assertThat(stacks.constructed()).containsExactly(built);
            assertThat(constructorArguments).containsExactly(List.of(Material.DIAMOND, 5));
        }

        @Test
        void theAmountIsOneUnlessSet() {
            new ItemBuilder(Material.STONE).build();

            assertThat(constructorArguments).containsExactly(List.of(Material.STONE, 1));
        }

        @Test
        void nothingIsSetOnTheMetaWhenNameLoreAndGlowAreUnset() {
            ItemStack built = new ItemBuilder(Material.STONE).build();

            verify(built).editMeta(any());
            verifyNoInteractions(meta);
        }

        @Test
        void theNameArrivesWithItalicOff() {
            new ItemBuilder(Material.STONE).name(Component.text("Gem", NamedTextColor.AQUA)).build();

            verify(meta).displayName(Component.text("Gem", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            verifyNoMoreInteractions(meta);
        }

        @Test
        void aNameSetToItalicLosesIt() {
            new ItemBuilder(Material.STONE).name(Component.text("Gem").decorate(TextDecoration.ITALIC)).build();

            verify(meta).displayName(Component.text("Gem").decoration(TextDecoration.ITALIC, false));
        }

        @Test
        void everyLoreLineArrivesWithItalicOffAndInOrder() {
            new ItemBuilder(Material.STONE)
                .lore(List.of(Component.text("first"), Component.text("second", NamedTextColor.GRAY)))
                .build();

            verify(meta).lore(List.of(
                Component.text("first").decoration(TextDecoration.ITALIC, false),
                Component.text("second", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            verifyNoMoreInteractions(meta);
        }

        @Test
        void anEmptyLoreIsNotSet() {
            new ItemBuilder(Material.STONE).lore(List.of()).build();

            verifyNoInteractions(meta);
        }

        @Test
        void glowTurnsTheGlintOverrideOn() {
            new ItemBuilder(Material.STONE).glow(true).build();

            verify(meta).setEnchantmentGlintOverride(true);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void glowTurnedOffAgainLeavesTheGlintAlone() {
            new ItemBuilder(Material.STONE).glow(true).glow(false).build();

            verifyNoInteractions(meta);
        }

        @Test
        void theGlintOverrideIsTheStyleNamedFirst() {
            new ItemBuilder(Material.STONE).glow(true).glowStyle(ItemBuilder.GlowStyle.GLINT_OVERRIDE).build();

            verify(meta).setEnchantmentGlintOverride(true);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void theHiddenEnchantmentStyleAddsUnbreakingOneAndHidesEnchantments() {
            new ItemBuilder(Material.STONE).glow(true).glowStyle(ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT).build();

            verify(meta).hasEnchants();
            verify(meta).addEnchant(Enchantment.UNBREAKING, 1, true);
            verify(meta).addItemFlags(ItemFlag.HIDE_ENCHANTS);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void theHiddenEnchantmentStyleLeavesAnItemWithEnchantmentsAsItIs() {
            when(meta.hasEnchants()).thenReturn(true);

            new ItemBuilder(Material.STONE).glow(true).glowStyle(ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT).build();

            verify(meta).hasEnchants();
            verifyNoMoreInteractions(meta);
        }

        @Test
        void aGlowStyleWithoutGlowSetsNothing() {
            new ItemBuilder(Material.STONE).glowStyle(ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT).build();

            verifyNoInteractions(meta);
        }

        @Test
        void enchantmentsAreAddedAtTheirLevelBeforeTheGlow() {
            when(meta.hasEnchants()).thenReturn(true);

            new ItemBuilder(Material.STONE)
                .glow(true)
                .glowStyle(ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT)
                .enchant(Enchantment.SHARPNESS, 3)
                .enchant(Enchantment.MENDING, 40)
                .enchant(Enchantment.SHARPNESS, 7)
                .build();

            InOrder order = inOrder(meta);
            order.verify(meta).addEnchant(Enchantment.SHARPNESS, 7, true);
            order.verify(meta).addEnchant(Enchantment.MENDING, 40, true);
            order.verify(meta).hasEnchants();
            verify(meta, times(2)).addEnchant(any(), anyInt(), eq(true));
        }

        @Test
        void theItemModelUnbreakableAndHiddenTooltipReachTheMeta() {
            NamespacedKey model = new NamespacedKey("shop", "ticket");

            new ItemBuilder(Material.STONE).itemModel(model).unbreakable(true).hideTooltip(true).build();

            verify(meta).setItemModel(model);
            verify(meta).setUnbreakable(true);
            verify(meta).setHideTooltip(true);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void unbreakableAndHiddenTooltipSetToFalseAreWrittenAsFalse() {
            new ItemBuilder(Material.STONE).unbreakable(false).hideTooltip(false).build();

            verify(meta).setUnbreakable(false);
            verify(meta).setHideTooltip(false);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void damageReachesAnItemWhoseTypeHasAMaxDurability() {
            Damageable damageable = mock();
            meta = damageable;
            when(type.getMaxDurability()).thenReturn((short) 1561);

            new ItemBuilder(Material.DIAMOND_SWORD).damage(12).build();

            verify(damageable).setDamage(12);
        }

        @Test
        void damageReachesAnItemThatCarriesAMaxDamageComponent() {
            Damageable damageable = mock();
            meta = damageable;
            when(damageable.hasMaxDamage()).thenReturn(true);

            new ItemBuilder(Material.STONE).damage(12).build();

            verify(damageable).setDamage(12);
        }

        @Test
        void damageIsNotSetOnAnItemThatCannotBeDamaged() {
            Damageable damageable = mock();
            meta = damageable;

            new ItemBuilder(Material.STONE).damage(3).build();
            new ItemBuilder(Material.STONE).damage(0).build();

            verify(damageable, never()).setDamage(anyInt());
        }

        @Test
        void theTrimReachesArmour() {
            ArmorMeta armor = mock();
            ArmorTrim trim = mock();
            meta = armor;

            new ItemBuilder(Material.IRON_CHESTPLATE).trim(trim).build();

            verify(armor).setTrim(trim);
            verifyNoMoreInteractions(armor);
        }

        @Test
        void theColourReachesLeatherArmourPotionsAndMaps() {
            Color color = Color.fromRGB(0x33AA77);
            LeatherArmorMeta leather = mock();
            PotionMeta potion = mock();
            MapMeta map = mock();

            meta = leather;
            new ItemBuilder(Material.LEATHER_BOOTS).color(color).build();
            meta = potion;
            new ItemBuilder(Material.POTION).color(color).build();
            meta = map;
            new ItemBuilder(Material.FILLED_MAP).color(color).build();

            verify(leather).setColor(color);
            verify(potion).setColor(color);
            verify(map).setColor(color);
            verifyNoMoreInteractions(leather, potion, map);
        }

        @Test
        void damageTrimAndColourAreIgnoredByAnItemWithoutThem() {
            new ItemBuilder(Material.STONE).damage(3).trim(mock()).color(Color.RED).build();

            verifyNoInteractions(meta);
        }

        @Test
        void tagsAreWrittenToTheContainerInCallOrder() {
            PersistentDataContainer container = mock();
            when(meta.getPersistentDataContainer()).thenReturn(container);
            PdcKey<String, String> owner = new PdcKey<>(new NamespacedKey("shop", "owner"), PersistentDataType.STRING);
            PdcKey<Integer, Integer> uses = new PdcKey<>(new NamespacedKey("shop", "uses"), PersistentDataType.INTEGER);

            new ItemBuilder(Material.STONE).tag(owner, "Ann").tag(uses, 3).tag(owner, "Bo").build();

            InOrder order = inOrder(container);
            order.verify(container).set(owner.key(), PersistentDataType.STRING, "Ann");
            order.verify(container).set(uses.key(), PersistentDataType.INTEGER, 3);
            order.verify(container).set(owner.key(), PersistentDataType.STRING, "Bo");
            verifyNoMoreInteractions(container);
        }

        @Test
        void customModelDataGoesToTheComponentAfterTheMetaIsWritten() {
            List<Object> calls = new ArrayList<>();
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                verify(item).editMeta(any());
                calls.add(item);
                calls.add(values);
            });
            Logger logger = mock();

            ItemStack built = new ItemBuilder(Material.STONE, modelData)
                .name(Component.text("Gem"))
                .customModelData(List.of(1.5f, 2f), logger)
                .build();

            assertThat(calls).containsExactly(built, List.of(1.5f, 2f));
            verify(built).editMeta(any());
            verify(meta, never()).setCustomModelData(any());
            verifyNoInteractions(logger);
        }

        @Test
        void aBuilderMadeWithoutAModelDataWritesTheFloatsAsTheCustomModelDataComponent() {
            CustomModelData.Builder component = mock();
            CustomModelData data = mock();
            when(component.addFloats(List.of(1.5f, 2f))).thenReturn(component);
            when(component.build()).thenReturn(data);
            Logger logger = mock();

            try (MockedStatic<CustomModelData> factory = mockStatic(CustomModelData.class)) {
                factory.when(CustomModelData::customModelData).thenReturn(component);

                ItemStack built = new ItemBuilder(Material.STONE).customModelData(List.of(1.5f, 2f), logger).build();

                verify(built).setData(DataComponentTypes.CUSTOM_MODEL_DATA, data);
            }
            verifyNoInteractions(meta, logger);
        }

        @Test
        void anEmptyCustomModelDataListIsNotSet() {
            Logger logger = mock();

            new ItemBuilder(Material.STONE, NO_MODEL_DATA).customModelData(List.of(), logger).build();

            verifyNoInteractions(meta, logger);
        }

        @Test
        void theListGivenToCustomModelDataIsCopied() {
            List<List<Float>> written = new ArrayList<>();
            List<Float> values = new ArrayList<>(List.of(4f));
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, list) -> written.add(list));
            ItemBuilder builder = new ItemBuilder(Material.STONE, modelData).customModelData(values, mock());
            values.add(5f);

            builder.build();

            assertThat(written).containsExactly(List.of(4f));
        }

        @Test
        void oneWholeNumberFallsBackToTheMetaWhenTheComponentCannotBeLinked() {
            LinkageError error = new NoClassDefFoundError("io/papermc/paper/datacomponent/DataComponentTypes");
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw error;
            });
            Logger logger = mock();

            ItemStack built = new ItemBuilder(Material.STONE, modelData).customModelData(List.of(7f), logger).build();

            verify(built, times(2)).editMeta(any());
            verify(meta).setCustomModelData(7);
            verifyNoMoreInteractions(meta);
            verify(logger).log(eq(Level.WARNING), anyString(), same(error));
        }

        @Test
        void theSmallestAndTheLargestWholeNumberAFloatHoldsInAnIntFallBackToTheMeta() {
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw new NoSuchMethodError("setData");
            });

            new ItemBuilder(Material.STONE, modelData).customModelData(List.of(-2147483648f), mock()).build();
            new ItemBuilder(Material.STONE, modelData).customModelData(List.of(2147483520f), mock()).build();

            verify(meta).setCustomModelData(Integer.MIN_VALUE);
            verify(meta).setCustomModelData(2147483520);
            verifyNoMoreInteractions(meta);
        }

        @Test
        void anyOtherListIsLeftOutWhenTheComponentCannotBeLinked() {
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw new NoSuchMethodError("setData");
            });
            Logger logger = mock();

            for (List<Float> values : List.of(List.of(1.5f), List.of(1f, 2f), List.of(Float.NaN), List.of(1e20f),
                List.of(2147483648f), List.of(-2147483904f), List.of(Float.POSITIVE_INFINITY),
                List.of(Float.NEGATIVE_INFINITY))) {
                new ItemBuilder(Material.STONE, modelData).customModelData(values, logger).build();
            }

            verifyNoInteractions(meta);
            verify(logger).log(eq(Level.WARNING), anyString(), any(NoSuchMethodError.class));
            verifyNoMoreInteractions(logger);
        }

        @Test
        void theWarningIsLoggedOnceForManyBuilds() {
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw new NoClassDefFoundError("gone");
            });
            Logger logger = mock();

            new ItemBuilder(Material.STONE, modelData).customModelData(List.of(7f), logger).build();
            new ItemBuilder(Material.STONE, modelData).customModelData(List.of(8f), logger).build();

            verify(meta).setCustomModelData(7);
            verify(meta).setCustomModelData(8);
            verify(logger).log(eq(Level.WARNING), anyString(), any(Throwable.class));
        }

        @Test
        void whatTheComponentThrowsOtherThanALinkageErrorLeavesBuild() {
            IllegalArgumentException refusal = new IllegalArgumentException("bad floats");
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw refusal;
            });
            Logger logger = mock();
            ItemBuilder builder = new ItemBuilder(Material.STONE, modelData).customModelData(List.of(7f), logger);

            assertThatThrownBy(builder::build).isSameAs(refusal);
            verifyNoInteractions(meta, logger);
        }
    }

    @Nested
    class BuildFromAStack {

        private final ItemMeta meta = mock(ItemMeta.class);
        private final ItemStack base = mock(ItemStack.class);
        private final ItemStack copy = mock(ItemStack.class);

        @BeforeEach
        void cloneGivesTheCopy() {
            when(base.clone()).thenReturn(copy);
            when(copy.editMeta(any())).thenAnswer(call -> {
                Consumer<ItemMeta> edit = call.getArgument(0);
                edit.accept(meta);
                return true;
            });
        }

        @Test
        void theCopyIsReturnedWithNothingSetWhenNoSetterWasCalled() {
            try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class)) {
                ItemStack built = new ItemBuilder(base).build();

                assertThat(built).isSameAs(copy);
                assertThat(stacks.constructed()).isEmpty();
            }
            verify(copy).editMeta(any());
            verifyNoMoreInteractions(copy);
            verifyNoInteractions(meta);
        }

        @Test
        void buildOnlyClonesTheStackItWasGiven() {
            PersistentDataContainer container = mock();
            when(meta.getPersistentDataContainer()).thenReturn(container);
            List<ItemStack> written = new ArrayList<>();
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> written.add(item));

            new ItemBuilder(base, modelData)
                .amount(3)
                .name(Component.text("Gem"))
                .lore(List.of(Component.text("line")))
                .glow(true)
                .itemModel(new NamespacedKey("shop", "ticket"))
                .customModelData(List.of(4f), mock())
                .enchant(Enchantment.SHARPNESS, 2)
                .unbreakable(true)
                .damage(1)
                .trim(mock())
                .hideTooltip(true)
                .color(Color.RED)
                .tag(new PdcKey<>(new NamespacedKey("shop", "owner"), PersistentDataType.STRING), "Ann")
                .build();

            verify(base).clone();
            verifyNoMoreInteractions(base);
            assertThat(written).containsExactly(copy);
            verify(copy).setAmount(3);
            verify(meta).setUnbreakable(true);
        }

        @Test
        void theLinkageFallbackWritesToTheCopy() {
            ItemBuilder.ModelData modelData = new ItemBuilder.ModelData((item, values) -> {
                throw new NoClassDefFoundError("gone");
            });

            new ItemBuilder(base, modelData).customModelData(List.of(7f), mock()).build();

            verify(base).clone();
            verifyNoMoreInteractions(base);
            verify(copy, times(2)).editMeta(any());
            verify(meta).setCustomModelData(7);
        }

        @Test
        void theAmountIsSetOnTheCopyWhenSet() {
            new ItemBuilder(base).amount(3).build();

            verify(copy).setAmount(3);
        }

        @Test
        void aNameReplacesOnlyTheName() {
            new ItemBuilder(base).name(Component.text("Gem")).build();

            verify(meta).displayName(Component.text("Gem").decoration(TextDecoration.ITALIC, false));
            verifyNoMoreInteractions(meta);
        }

        @Test
        void aLoreReplacesOnlyTheLoreAndAnEmptyOneKeepsIt() {
            new ItemBuilder(base).lore(List.of()).build();
            verifyNoInteractions(meta);

            new ItemBuilder(base).lore(List.of(Component.text("line"))).build();

            verify(meta).lore(List.of(Component.text("line").decoration(TextDecoration.ITALIC, false)));
            verifyNoMoreInteractions(meta);
        }

        @Test
        void glowSetToFalseTakesNoGlintOffTheStack() {
            new ItemBuilder(base).glow(false).build();

            verifyNoInteractions(meta);
        }

        @Test
        void everyBuildClonesTheStackAgain() {
            ItemStack second = mock();
            when(base.clone()).thenReturn(copy, second);
            ItemBuilder builder = new ItemBuilder(base).amount(2);

            assertThat(builder.build()).isSameAs(copy);
            assertThat(builder.build()).isSameAs(second);

            verify(base, times(2)).clone();
            verifyNoMoreInteractions(base);
        }

        @Test
        void aNullStackThrowsWhenTheBuilderIsMade() {
            assertThatThrownBy(() -> new ItemBuilder((ItemStack) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("base");
        }
    }

    @Test
    void applyGlowSetsOnlyTheGlintOverride() {
        ItemMeta meta = mock(ItemMeta.class);

        ItemBuilder.applyGlow(meta);

        verify(meta).setEnchantmentGlintOverride(true);
        verifyNoMoreInteractions(meta);
    }

    @Test
    void applyGlowWithTheGlintOverrideStyleSetsOnlyTheGlintOverride() {
        ItemMeta meta = mock(ItemMeta.class);

        ItemBuilder.applyGlow(meta, ItemBuilder.GlowStyle.GLINT_OVERRIDE);

        verify(meta).setEnchantmentGlintOverride(true);
        verifyNoMoreInteractions(meta);
    }

    @Test
    void applyGlowWithTheHiddenEnchantmentStyleSkipsAnItemWithEnchantments() {
        ItemMeta plain = mock(ItemMeta.class);
        ItemMeta enchanted = mock(ItemMeta.class);
        when(enchanted.hasEnchants()).thenReturn(true);

        ItemBuilder.applyGlow(plain, ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT);
        ItemBuilder.applyGlow(enchanted, ItemBuilder.GlowStyle.HIDDEN_ENCHANTMENT);

        verify(plain).addEnchant(Enchantment.UNBREAKING, 1, true);
        verify(plain).addItemFlags(ItemFlag.HIDE_ENCHANTS);
        verify(plain, never()).setEnchantmentGlintOverride(any());
        verify(enchanted).hasEnchants();
        verifyNoMoreInteractions(enchanted);
    }

    @Test
    void applyGlowWithoutAStyleThrowsAndLeavesTheMetaAlone() {
        ItemMeta meta = mock(ItemMeta.class);

        assertThatThrownBy(() -> ItemBuilder.applyGlow(meta, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("style");
        verifyNoInteractions(meta);
    }

    @Test
    void aReferenceIsSerializedOnlyWhenItStartsWithThePrefix() {
        assertThat(ItemBuilder.isSerialized("serialized:AQID/w==")).isTrue();
        assertThat(ItemBuilder.isSerialized("serialized:")).isTrue();
        assertThat(ItemBuilder.isSerialized("DIAMOND")).isFalse();
        assertThat(ItemBuilder.isSerialized("Serialized:AQID/w==")).isFalse();
        assertThat(ItemBuilder.isSerialized(" serialized:AQID/w==")).isFalse();
        assertThat(ItemBuilder.isSerialized("")).isFalse();
    }

    @Test
    void serializeIsThePrefixAndTheItemsBytesInBase64() {
        ItemStack item = mock(ItemStack.class);
        when(item.serializeAsBytes()).thenReturn(new byte[] {1, 2, 3, -1});

        assertThat(ItemBuilder.serialize(item)).isEqualTo("serialized:AQID/w==");
    }

    @Test
    void serializeLetsWhatTheServerThrowsForAnEmptyStackThrough() {
        ItemStack empty = mock(ItemStack.class);
        IllegalArgumentException refusal = new IllegalArgumentException("air cannot be serialized");
        when(empty.serializeAsBytes()).thenThrow(refusal);

        assertThatThrownBy(() -> ItemBuilder.serialize(empty)).isSameAs(refusal);
    }

    @Test
    void serializeThrowsForANullItem() {
        assertThatThrownBy(() -> ItemBuilder.serialize(null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class Deserialize {

        private final ItemStack item = mock(ItemStack.class);
        private MockedStatic<ItemStack> stacks;

        @BeforeEach
        void interceptDeserializeBytes() {
            stacks = mockStatic(ItemStack.class);
        }

        @AfterEach
        void close() {
            stacks.close();
        }

        @Test
        void theDecodedBytesAreTurnedBackIntoTheItem() {
            stacks.when(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serialized:AQID/w==")).isSameAs(item);
        }

        @Test
        void aReferenceThatIsNotBase64IsNull() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serialized:not base64!")).isNull();
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void bytesTheServerRejectsAreNull() {
            stacks.when(() -> ItemStack.deserializeBytes(any()))
                .thenThrow(new IllegalArgumentException("Newer version! Server downgrades are not supported!"));

            assertThat(ItemBuilder.deserialize("serialized:AQID/w==")).isNull();
            stacks.verify(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1}));
        }

        @Test
        void aReferenceShorterThanThePrefixIsNull() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serial")).isNull();
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void aNullReferenceIsNull() {
            assertThat(ItemBuilder.deserialize(null)).isNull();
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void thePrefixIsSkippedByLengthWithoutBeingChecked() {
            stacks.when(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserialize("SERIALIZED:AQID/w==")).isSameAs(item);
        }
    }

    @Nested
    class DeserializeOrThrow {

        private final ItemStack item = mock(ItemStack.class);
        private MockedStatic<ItemStack> stacks;

        @BeforeEach
        void interceptDeserializeBytes() {
            stacks = mockStatic(ItemStack.class);
        }

        @AfterEach
        void close() {
            stacks.close();
        }

        @Test
        void theDecodedBytesAreTurnedBackIntoTheItem() {
            stacks.when(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserializeOrThrow("serialized:AQID/w==")).isSameAs(item);
        }

        @Test
        void aReferenceWithoutThePrefixThrowsWithNoCause() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            for (String reference : List.of("DIAMOND", "serial", "", "SERIALIZED:AQID/w==")) {
                assertThatThrownBy(() -> ItemBuilder.deserializeOrThrow(reference))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Item reference does not start with 'serialized:'")
                    .hasNoCause();
            }
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void invalidBase64ThrowsWithTheDecodersExceptionAsCause() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            assertThatThrownBy(() -> ItemBuilder.deserializeOrThrow("serialized:not base64!"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Item reference holds invalid base64 after 'serialized:': ")
                .hasCauseExactlyInstanceOf(IllegalArgumentException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).endsWith(thrown.getCause().getMessage()));
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void bytesTheServerRefusesThrowWithTheServersExceptionAsCause() {
            RuntimeException refusal = new IllegalStateException("Newer version! Server downgrades are not supported!");
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenThrow(refusal);

            assertThatThrownBy(() -> ItemBuilder.deserializeOrThrow("serialized:AQID/w=="))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("The server refused the bytes of the item reference, which are corrupt or were written"
                    + " by a newer data version: Newer version! Server downgrades are not supported!")
                .hasCause(refusal);
            stacks.verify(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1}));
        }

        @Test
        void aNullReferenceThrowsNullPointerException() {
            assertThatThrownBy(() -> ItemBuilder.deserializeOrThrow(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class DeserializeAnyOrThrow {

        private final ItemStack item = mock(ItemStack.class);
        private MockedStatic<ItemStack> stacks;

        @BeforeEach
        void interceptDeserializeBytes() {
            stacks = mockStatic(ItemStack.class);
        }

        @AfterEach
        void close() {
            stacks.close();
        }

        @Test
        void bareBase64IsTurnedBackIntoTheItem() {
            stacks.when(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserializeAnyOrThrow("AQID/w==")).isSameAs(item);
        }

        @Test
        void aPrefixedReferenceIsReadAsDeserializeOrThrowReadsIt() {
            stacks.when(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserializeAnyOrThrow("serialized:AQID/w==")).isSameAs(item);
            assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow("serialized:not base64!"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Item reference holds invalid base64 after 'serialized:': ")
                .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aReferenceThatIsNeitherFormThrowsWithTheDecodersExceptionAsCause() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            for (String reference : List.of("not base64!", "SERIALIZED:AQID/w==", "nexo:ruby", "A")) {
                assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow(reference))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Item reference does not start with 'serialized:' and is not base64: ")
                    .hasCauseExactlyInstanceOf(IllegalArgumentException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage()).endsWith(thrown.getCause().getMessage()));
            }
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void aMaterialNameThatIsValidBase64IsNeverDecoded() {
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);

            for (String name : List.of("DIRT", "dirt", "DIAMOND", "Sand", "BOOK")) {
                assertThatCode(() -> Base64.getDecoder().decode(name)).as(name).doesNotThrowAnyException();
                assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow(name))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Item reference does not start with 'serialized:' and is the material name '"
                        + name + "'")
                    .hasNoCause();
            }
            stacks.verify(() -> ItemStack.deserializeBytes(any()), never());
        }

        @Test
        void aMaterialNameThatIsNotBase64ThrowsAsAMaterialName() {
            assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow("minecraft:oak_log"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Item reference does not start with 'serialized:' and is the material name"
                    + " 'minecraft:oak_log'")
                .hasNoCause();
        }

        @Test
        void bareBytesTheServerRefusesThrowWithTheServersExceptionAsCause() {
            RuntimeException refusal = new IllegalStateException("Not in GZIP format");
            stacks.when(() -> ItemStack.deserializeBytes(any())).thenThrow(refusal);

            assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow("AQID/w=="))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("The server refused the bytes of the item reference, which are corrupt or were written"
                    + " by a newer data version: Not in GZIP format")
                .hasCause(refusal);
            stacks.verify(() -> ItemStack.deserializeBytes(new byte[] {1, 2, 3, -1}));
        }

        @Test
        void aNullReferenceThrowsNullPointerException() {
            assertThatThrownBy(() -> ItemBuilder.deserializeAnyOrThrow(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
