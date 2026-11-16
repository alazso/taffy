package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

// Builds the stack an Icon describes by writing its StackPlan through the item meta.
final class IconStacks {

    // The flags that hide the detail lines of a plan whose details() is true.
    @SuppressWarnings("deprecation")
    private static final ItemFlag[] DETAIL_FLAGS = {ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
        ItemFlag.HIDE_STORED_ENCHANTS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_UNBREAKABLE,
        ItemFlag.HIDE_ADDITIONAL_TOOLTIP};

    private IconStacks() {
    }

    // A Real is drawn on a clone of its base, which stays unchanged.
    static ItemStack stack(Icon icon) {
        ItemStack stack = switch (icon) {
            case Icon.Button button -> new ItemStack(button.material());
            case Icon.Filler filler -> new ItemStack(filler.material());
            case Icon.Real real -> real.base().clone();
            case Icon.Saved saved -> new ItemStack(saved.material());
        };
        List<Component> own = stack.lore();
        StackPlan plan = StackPlan.of(icon, own == null ? List.of() : own, stack.getMaxStackSize());
        stack.setAmount(plan.amount());
        if (writes(plan)) {
            stack.editMeta(meta -> write(meta, plan));
        }
        return stack;
    }

    // A stack of the material whose item name is the given text, and nothing else written.
    static ItemStack named(Material material, Component name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.itemName(name));
        return stack;
    }

    private static boolean writes(StackPlan plan) {
        return plan.customName() != null || plan.itemName() != null || plan.lore() != null
            || plan.tooltip() == StackPlan.Tooltip.HIDDEN || plan.details() || plan.glint();
    }

    private static void write(ItemMeta meta, StackPlan plan) {
        if (plan.customName() != null) {
            meta.displayName(plan.customName());
        }
        if (plan.itemName() != null) {
            meta.itemName(plan.itemName());
        }
        if (plan.lore() != null) {
            meta.lore(plan.lore());
        }
        if (plan.tooltip() == StackPlan.Tooltip.HIDDEN) {
            meta.setHideTooltip(true);
        } else if (plan.details()) {
            meta.addItemFlags(DETAIL_FLAGS);
        }
        if (plan.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
    }
}
