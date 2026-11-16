package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class IconStacksTest {

    private static final Component NAME = Component.text("Browse");
    private static final List<Component> LORE = List.of(Component.text("first"), Component.text("second"));

    private final ItemMeta meta = mock(ItemMeta.class);
    private final List<List<?>> constructorArguments = new ArrayList<>();
    private MockedConstruction<ItemStack> stacks;

    // Every new ItemStack on this thread is a mock with a maximum stack size of 16 whose editMeta runs the edit
    // against the shared meta.
    @BeforeEach
    void interceptNewStacks() {
        stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            constructorArguments.add(context.arguments());
            when(stack.getMaxStackSize()).thenReturn(16);
            when(stack.lore()).thenReturn(null);
            answerEditMeta(stack);
        });
    }

    @AfterEach
    void close() {
        stacks.close();
    }

    @Test
    void aButtonStartsFromANewStackOfItsMaterialWithItsAmountClampedToTheStackMaximum() {
        ItemStack small = IconStacks.stack(new Icon.Button(Material.EMERALD, 5, null, List.of(), false));
        ItemStack large = IconStacks.stack(new Icon.Button(Material.EMERALD, 40, null, List.of(), false));

        assertThat(stacks.constructed()).containsExactly(small, large);
        assertThat(constructorArguments).containsExactly(List.of(Material.EMERALD), List.of(Material.EMERALD));
        verify(small).setAmount(5);
        verify(large).setAmount(16);
    }

    @Test
    void aNamedButtonWritesItsItemNameAndLoreAndNoCustomName() {
        IconStacks.stack(new Icon.Button(Material.EMERALD, 1, NAME, LORE, false));

        verify(meta).itemName(NAME);
        verify(meta).lore(LORE);
        verify(meta, never()).displayName(any());
    }

    @Test
    @SuppressWarnings("deprecation")
    void aNamedButtonHidesTheDetailLinesWithTheSevenFlags() {
        IconStacks.stack(new Icon.Button(Material.DIAMOND_SWORD, 1, NAME, List.of(), false));

        verify(meta).addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS,
            ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        verify(meta, never()).setHideTooltip(anyBoolean());
        verify(meta, never()).lore(any());
    }

    @Test
    void aButtonWithoutANameHidesItsWholeTooltipAndWritesNoText() {
        IconStacks.stack(new Icon.Button(Material.STONE, 1, null, LORE, false));

        verify(meta).setHideTooltip(true);
        verifyNoMoreInteractions(meta);
    }

    @Test
    void aFillerHidesItsTooltipAndShowsOne() {
        ItemStack stack = IconStacks.stack(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE));

        assertThat(constructorArguments).containsExactly(List.of(Material.GRAY_STAINED_GLASS_PANE));
        verify(stack).setAmount(1);
        verify(meta).setHideTooltip(true);
        verifyNoMoreInteractions(meta);
    }

    @Test
    void glintTurnsTheGlintOverrideOn() {
        IconStacks.stack(new Icon.Button(Material.STONE, 1, NAME, List.of(), true));

        verify(meta).setEnchantmentGlintOverride(true);
    }

    @Test
    void withoutGlintTheGlintOverrideIsLeftAlone() {
        IconStacks.stack(new Icon.Button(Material.STONE, 1, NAME, List.of(), false));

        verify(meta, never()).setEnchantmentGlintOverride(any());
    }

    @Test
    void aSavedItemWritesItsCustomNameItsItemNameAndItsMergedLore() {
        ItemStack stack = IconStacks.stack(new Icon.Saved(Material.PAPER, 3, Component.text("Renamed"),
            Component.text("Paper"), List.of(Component.text("stored")), List.of(Component.text("extra")), true));

        assertThat(constructorArguments).containsExactly(List.of(Material.PAPER));
        verify(stack).setAmount(3);
        verify(meta).displayName(Component.text("Renamed"));
        verify(meta).itemName(Component.text("Paper"));
        verify(meta).lore(List.of(Component.text("stored"), Component.empty(), Component.text("extra")));
        verify(meta).setEnchantmentGlintOverride(true);
        verify(meta, never()).setHideTooltip(anyBoolean());
        verify(meta, never()).addItemFlags(any(ItemFlag[].class));
    }

    @Test
    void aRealIconIsDrawnOnACloneThatKeepsItsLoreAndTheBaseIsLeftUntouched() {
        ItemStack base = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(base.clone()).thenReturn(copy);
        when(copy.lore()).thenReturn(List.of(Component.text("Sharp")));
        when(copy.getMaxStackSize()).thenReturn(64);
        answerEditMeta(copy);

        ItemStack drawn = IconStacks.stack(new Icon.Real(base, 7, List.of(Component.text("Click to claim"))));

        assertThat(drawn).isSameAs(copy);
        assertThat(stacks.constructed()).isEmpty();
        verify(copy).setAmount(7);
        verify(meta).lore(List.of(Component.text("Sharp"), Component.empty(), Component.text("Click to claim")));
        verifyNoMoreInteractions(meta);
        verify(base).clone();
        verifyNoMoreInteractions(base);
    }

    @Test
    void aRealIconWithoutExtraLinesLeavesTheMetaUnreadAndAnItemWithoutLoreStillGetsTheExtraLines() {
        ItemStack bare = real(null);

        ItemStack drawn = IconStacks.stack(new Icon.Real(bare, 1, List.of()));
        verify(drawn).setAmount(1);
        verify(drawn, never()).editMeta(any());
        verifyNoInteractions(meta);

        IconStacks.stack(new Icon.Real(real(null), 1, List.of(Component.text("extra"))));
        verify(meta).lore(List.of(Component.empty(), Component.text("extra")));
    }

    @Test
    void namedWritesOnlyTheItemName() {
        ItemStack stack = IconStacks.named(Material.PAPER, Component.text("Type here"));

        assertThat(stacks.constructed()).containsExactly(stack);
        assertThat(constructorArguments).containsExactly(List.of(Material.PAPER));
        verify(meta).itemName(Component.text("Type here"));
        verifyNoMoreInteractions(meta);
        verify(stack, never()).setAmount(anyInt());
    }

    private ItemStack real(@Nullable List<Component> lore) {
        ItemStack base = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(base.clone()).thenReturn(copy);
        when(copy.lore()).thenReturn(lore);
        when(copy.getMaxStackSize()).thenReturn(64);
        answerEditMeta(copy);
        return base;
    }

    private void answerEditMeta(ItemStack stack) {
        when(stack.editMeta(any())).thenAnswer(call -> {
            Consumer<ItemMeta> edit = call.getArgument(0);
            edit.accept(meta);
            return true;
        });
    }
}
