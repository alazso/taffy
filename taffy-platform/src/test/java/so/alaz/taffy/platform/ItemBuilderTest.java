package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.UnsafeValues;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ItemBuilderTest {

    @Nested
    class Build {

        private final ItemMeta meta = mock(ItemMeta.class);
        private final List<List<?>> constructorArguments = new ArrayList<>();
        private MockedConstruction<ItemStack> stacks;

        // Every new ItemStack on this thread is a mock whose editMeta runs the edit against the shared meta.
        @BeforeEach
        void interceptNewStacks() {
            stacks = mockConstruction(ItemStack.class, (stack, context) -> {
                constructorArguments.add(context.arguments());
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
    }

    @Test
    void applyGlowSetsOnlyTheGlintOverride() {
        ItemMeta meta = mock(ItemMeta.class);

        ItemBuilder.applyGlow(meta);

        verify(meta).setEnchantmentGlintOverride(true);
        verifyNoMoreInteractions(meta);
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

    @Nested
    @SuppressWarnings("deprecation")
    class Deserialize {

        private final UnsafeValues unsafe = mock(UnsafeValues.class);
        private final ItemStack item = mock(ItemStack.class);
        private MockedStatic<Bukkit> bukkit;

        @BeforeEach
        void server() {
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
        }

        @AfterEach
        void close() {
            bukkit.close();
        }

        @Test
        void theDecodedBytesAreTurnedBackIntoTheItem() {
            when(unsafe.deserializeItem(new byte[] {1, 2, 3, -1})).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serialized:AQID/w==")).isSameAs(item);
        }

        @Test
        void aReferenceThatIsNotBase64IsNull() {
            when(unsafe.deserializeItem(any())).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serialized:not base64!")).isNull();
            verify(unsafe, never()).deserializeItem(any());
        }

        @Test
        void bytesTheServerRejectsAreNull() {
            when(unsafe.deserializeItem(any())).thenThrow(new IllegalArgumentException("Newer version! Server downgrades are not supported!"));

            assertThat(ItemBuilder.deserialize("serialized:AQID/w==")).isNull();
            verify(unsafe).deserializeItem(new byte[] {1, 2, 3, -1});
        }

        @Test
        void aReferenceShorterThanThePrefixIsNull() {
            when(unsafe.deserializeItem(any())).thenReturn(item);

            assertThat(ItemBuilder.deserialize("serial")).isNull();
            verify(unsafe, never()).deserializeItem(any());
        }
    }
}
