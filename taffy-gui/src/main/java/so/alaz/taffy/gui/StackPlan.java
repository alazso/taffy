package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// What the menu inventory writes on the stack an Icon draws. A null name or lore leaves that part as the stack has it.
// details tells whether a shown tooltip leaves out the item's attribute, enchantment and similar detail lines, so
// only the icon's own text shows.
record StackPlan(int amount, @Nullable Component customName, @Nullable Component itemName,
                 @Nullable List<Component> lore, Tooltip tooltip, boolean details, boolean glint) {

    // The most lore lines a client shows.
    static final int MAX_LORE = 256;
    static final int MAX_AMOUNT = 99;

    enum Tooltip {
        SHOWN,
        HIDDEN
    }

    StackPlan {
        lore = lore == null ? null : List.copyOf(lore);
    }

    // existingLore is the lore of the stack the icon starts from (a Real copy's own lore) and maxStack its maximum
    // stack size.
    static StackPlan of(Icon icon, List<Component> existingLore, int maxStack) {
        return switch (icon) {
            case Icon.Button button -> new StackPlan(amount(button.amount(), maxStack), null, button.name(),
                button.name() == null || button.lore().isEmpty() ? null : button.lore(),
                button.name() == null ? Tooltip.HIDDEN : Tooltip.SHOWN, button.name() != null, button.glint());
            case Icon.Filler ignored -> new StackPlan(1, null, null, null, Tooltip.HIDDEN, false, false);
            case Icon.Real real -> new StackPlan(amount(real.amount(), maxStack), null, null,
                real.extraLore().isEmpty() ? null : merge(existingLore, real.extraLore()), Tooltip.SHOWN, false, false);
            case Icon.Saved saved -> new StackPlan(amount(saved.amount(), maxStack), saved.customName(),
                saved.itemName(), merge(saved.lore(), saved.extraLore()), Tooltip.SHOWN, false, saved.glint());
        };
    }

    // The item's own lines, cut so the blank line and every extra line fit within MAX_LORE, then a blank line and
    // the extra lines. Without extra lines the item's lines stay as they are.
    static List<Component> merge(List<Component> own, List<Component> extra) {
        if (extra.isEmpty()) {
            return List.copyOf(own.subList(0, Math.min(own.size(), MAX_LORE)));
        }
        int room = Math.max(0, MAX_LORE - extra.size() - 1);
        List<Component> lines = new ArrayList<>(own.subList(0, Math.min(own.size(), room)));
        lines.add(Component.empty());
        lines.addAll(extra);
        return List.copyOf(lines.subList(0, Math.min(lines.size(), MAX_LORE)));
    }

    // 1 to the smaller of maxStack and MAX_AMOUNT.
    static int amount(int requested, int maxStack) {
        return Math.clamp(requested, 1, Math.max(1, Math.min(maxStack, MAX_AMOUNT)));
    }
}
