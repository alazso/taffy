package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.FakeItems;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

final class StackPlanTest {

    private static final List<Component> EXTRA = List.of(Component.text("Click to claim"));

    @Test
    void aNamedButtonSetsItsItemNameAndLoreAndHidesTheDetailLines() {
        StackPlan plan = StackPlan.of(new Icon.Button(Material.EMERALD, 1, Component.text("Browse"),
            List.of(Component.text("line")), true), List.of(), 64);

        assertThat(plan).isEqualTo(new StackPlan(1, null, Component.text("Browse"), List.of(Component.text("line")),
            StackPlan.Tooltip.SHOWN, true, true));
        assertThat(plan.details()).isTrue();
    }

    @Test
    void aButtonWithoutANameHidesItsTooltip() {
        StackPlan plan = StackPlan.of(new Icon.Button(Material.STONE, 1, null, List.of(Component.text("x")), false),
            List.of(), 64);

        assertThat(plan.tooltip()).isEqualTo(StackPlan.Tooltip.HIDDEN);
        assertThat(plan.details()).isFalse();
        assertThat(plan.lore()).isNull();
        assertThat(plan.itemName()).isNull();
    }

    @Test
    void fillerHidesItsTooltip() {
        assertThat(StackPlan.of(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE), List.of(), 64))
            .isEqualTo(new StackPlan(1, null, null, null, StackPlan.Tooltip.HIDDEN, false, false));
    }

    @Test
    void amountsClampToOneAndTheStackMaximum() {
        assertThat(StackPlan.of(new Icon.Button(Material.RED_DYE, 64, null, List.of(), false), List.of(), 64)
            .amount()).isEqualTo(64);
        assertThat(StackPlan.of(new Icon.Button(Material.RED_DYE, 0, null, List.of(), false), List.of(), 64)
            .amount()).isEqualTo(1);
        assertThat(StackPlan.of(new Icon.Real(FakeItems.item(Material.DIAMOND_SWORD, 1), 5, EXTRA), List.of(), 1)
            .amount()).isEqualTo(1);
        assertThat(StackPlan.of(new Icon.Saved(Material.STONE, 500, null, null, List.of(), List.of(), false),
            List.of(), 99).amount()).isEqualTo(99);
    }

    @Test
    void aRealCopyKeepsItsNamesAndAddsItsLinesUnderABlankLine() {
        StackPlan plan = StackPlan.of(new Icon.Real(FakeItems.item(Material.DIAMOND, 3), 3, EXTRA),
            List.of(Component.text("Sharp")), 64);

        assertThat(plan.customName()).isNull();
        assertThat(plan.itemName()).isNull();
        assertThat(plan.lore()).containsExactly(Component.text("Sharp"), Component.empty(),
            Component.text("Click to claim"));
        assertThat(plan.tooltip()).isEqualTo(StackPlan.Tooltip.SHOWN);
        assertThat(plan.details()).isFalse();
    }

    @Test
    void aRealCopyWithoutExtraLinesKeepsItsLore() {
        assertThat(StackPlan.of(new Icon.Real(FakeItems.item(Material.DIAMOND, 1), 1, List.of()),
            List.of(Component.text("Sharp")), 64).lore()).isNull();
    }

    @Test
    void longLoreIsCutSoTheBlankLineAndExtraLinesFitIn256() {
        List<Component> own = IntStream.range(0, 300).mapToObj(line -> (Component) Component.text("own " + line))
            .toList();
        List<Component> extra = List.of(Component.text("a"), Component.text("b"));

        List<Component> merged = StackPlan.merge(own, extra);

        assertThat(merged).hasSize(StackPlan.MAX_LORE);
        assertThat(merged.get(252)).isEqualTo(Component.text("own 252"));
        assertThat(merged.subList(253, 256)).containsExactly(Component.empty(), Component.text("a"),
            Component.text("b"));
        assertThat(StackPlan.merge(own, List.of())).hasSize(StackPlan.MAX_LORE);
    }

    @Test
    void aSavedItemSetsItsStoredNamesLoreExtraLinesAndGlint() {
        StackPlan plan = StackPlan.of(new Icon.Saved(Material.PAPER, 2, Component.text("Named"),
            Component.text("Item"), List.of(Component.text("stored")), EXTRA, true), List.of(), 64);

        assertThat(plan).isEqualTo(new StackPlan(2, Component.text("Named"), Component.text("Item"),
            List.of(Component.text("stored"), Component.empty(), Component.text("Click to claim")),
            StackPlan.Tooltip.SHOWN, false, true));
    }
}
