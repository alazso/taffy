package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.FakeItems;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

final class IconTest {

    @Test
    void iconsCopyTheirLinesWhenBuilt() {
        List<Component> lines = new ArrayList<>(List.of(Component.text("one")));
        Icon.Button button = new Icon.Button(Material.EMERALD, 1, Component.text("Browse"), lines, false);
        Icon.Real real = new Icon.Real(FakeItems.item(Material.DIAMOND, 1), 1, lines);
        Icon.Saved saved = new Icon.Saved(Material.PAPER, 1, null, null, lines, lines, false);

        lines.add(Component.text("two"));

        assertThat(button.lore()).containsExactly(Component.text("one"));
        assertThat(real.extraLore()).containsExactly(Component.text("one"));
        assertThat(saved.lore()).containsExactly(Component.text("one"));
        assertThat(saved.extraLore()).containsExactly(Component.text("one"));
        assertThatThrownBy(() -> button.lore().add(Component.empty()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void iconsAreValues() {
        ItemStack base = FakeItems.item(Material.DIAMOND, 1);

        assertThat(new Icon.Button(Material.STONE, 2, Component.text("x"), List.of(), true))
            .isEqualTo(new Icon.Button(Material.STONE, 2, Component.text("x"), List.of(), true))
            .hasSameHashCodeAs(new Icon.Button(Material.STONE, 2, Component.text("x"), List.of(), true))
            .isNotEqualTo(new Icon.Button(Material.STONE, 2, Component.text("x"), List.of(), false));
        assertThat(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE))
            .isEqualTo(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE))
            .hasSameHashCodeAs(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE))
            .isNotEqualTo(new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE));
        assertThat(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE).material())
            .isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(new Icon.Real(base, 1, List.of())).isEqualTo(new Icon.Real(base, 1, List.of()))
            .hasSameHashCodeAs(new Icon.Real(base, 1, List.of()))
            .isNotEqualTo(new Icon.Real(base, 2, List.of()));
        assertThat(new Icon.Saved(Material.PAPER, 1, Component.text("a"), null, List.of(), List.of(), false))
            .isEqualTo(new Icon.Saved(Material.PAPER, 1, Component.text("a"), null, List.of(), List.of(), false))
            .hasSameHashCodeAs(new Icon.Saved(Material.PAPER, 1, Component.text("a"), null, List.of(), List.of(),
                false))
            .isNotEqualTo(new Icon.Saved(Material.PAPER, 1, null, null, List.of(), List.of(), false));
        assertThat(new Icon.Filler(Material.STONE)).hasToString("Filler[material=STONE]");
    }

    @Test
    void aRealIconLeavesItsBaseUntouched() {
        ItemStack base = FakeItems.item(Material.DIAMOND, 3);

        Icon.Real real = new Icon.Real(base, 5, List.of(Component.text("extra")));
        StackPlan.of(real, List.of(), 64);

        assertThat(real.base()).isSameAs(base);
        verifyNoInteractions(base);
    }

    @Test
    void anIconNeedsItsItem() {
        assertThatThrownBy(() -> new Icon.Button(null, 1, null, List.of(), false))
            .isInstanceOf(NullPointerException.class).hasMessage("material");
        assertThatThrownBy(() -> new Icon.Filler(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("material");
        assertThatThrownBy(() -> new Icon.Real(null, 1, List.of())).isInstanceOf(NullPointerException.class)
            .hasMessage("base");
        assertThatThrownBy(() -> new Icon.Saved(null, 1, null, null, List.of(), List.of(), false))
            .isInstanceOf(NullPointerException.class).hasMessage("material");
    }
}
