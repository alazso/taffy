package so.alaz.taffy.gui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// A view of a top inventory over a player's 36 storage slots, numbered as a chest view numbers them: raw slots past
// the top run over storage rows 9 to 35, then the hotbar 0 to 8. getType and getMenuType need a server and throw.
@SuppressWarnings({"deprecation", "removal"})
final class FakeView implements InventoryView {

    private final Inventory top;
    private final Inventory bottom;
    private final HumanEntity player;
    private @Nullable ItemStack cursor;
    final List<String> calls = new ArrayList<>();

    FakeView(Inventory top, Inventory bottom, HumanEntity player) {
        this.top = top;
        this.bottom = bottom;
        this.player = player;
    }

    @Override
    public Inventory getTopInventory() {
        return top;
    }

    @Override
    public Inventory getBottomInventory() {
        return bottom;
    }

    @Override
    public HumanEntity getPlayer() {
        return player;
    }

    @Override
    public InventoryType getType() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItem(int slot, @Nullable ItemStack item) {
        Inventory inventory = getInventory(slot);
        if (inventory != null) {
            inventory.setItem(convertSlot(slot), item);
        }
    }

    @Override
    public @Nullable ItemStack getItem(int slot) {
        Inventory inventory = getInventory(slot);
        return inventory == null ? null : inventory.getItem(convertSlot(slot));
    }

    @Override
    public void setCursor(@Nullable ItemStack item) {
        cursor = item;
    }

    @Override
    public @Nullable ItemStack getCursor() {
        return cursor;
    }

    @Override
    public @Nullable Inventory getInventory(int rawSlot) {
        if (rawSlot < 0) {
            return null;
        }
        return rawSlot < top.getSize() ? top : bottom;
    }

    @Override
    public int convertSlot(int rawSlot) {
        int size = top.getSize();
        if (rawSlot < size) {
            return rawSlot;
        }
        int bottomSlot = rawSlot - size;
        return bottomSlot < 27 ? bottomSlot + 9 : bottomSlot - 27;
    }

    @Override
    public InventoryType.SlotType getSlotType(int slot) {
        if (slot < 0) {
            return InventoryType.SlotType.OUTSIDE;
        }
        if (slot >= top.getSize() && convertSlot(slot) < 9) {
            return InventoryType.SlotType.QUICKBAR;
        }
        return InventoryType.SlotType.CONTAINER;
    }

    @Override
    public void open() {
        calls.add("open");
    }

    @Override
    public void close() {
        calls.add("close");
    }

    @Override
    public int countSlots() {
        return top.getSize() + 36;
    }

    @Override
    public boolean setProperty(Property prop, int value) {
        calls.add("property");
        return false;
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public String getOriginalTitle() {
        return "";
    }

    @Override
    public void setTitle(String title) {
        calls.add("title");
    }

    @Override
    public @Nullable MenuType getMenuType() {
        throw new UnsupportedOperationException();
    }
}
