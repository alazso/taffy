package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
