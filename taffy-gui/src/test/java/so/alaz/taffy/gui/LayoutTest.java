package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class LayoutTest {

    private static ItemLayout item(String id, @Nullable String action, Integer... slots) {
        return new ItemLayout(id, List.of(slots), Material.STONE, action, null, true, null, null);
    }

    private static MenuLayout menu(int rows, Map<String, ItemLayout> items, @Nullable PagerLayout pager) {
        return new MenuLayout("entries", rows, null, pager, items, null);
    }

    @Test
    void rowsAreOneToSix() {
        assertThat(menu(1, Map.of(), null).size()).isEqualTo(9);
        assertThat(menu(6, Map.of(), null).size()).isEqualTo(54);
        assertThatThrownBy(() -> menu(0, Map.of(), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the entries menu has 1 to 6 rows, not 0");
        assertThatThrownBy(() -> menu(7, Map.of(), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the entries menu has 1 to 6 rows, not 7");
    }

    @Test
    void anItemSlotOutsideTheMenuIsRefused() {
        assertThat(menu(3, Map.of("last", item("last", null, 26)), null).items()).containsKey("last");
        assertThatThrownBy(() -> menu(3, Map.of("late", item("late", null, 4, 27)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item late slot 27 is outside the entries menu of 27 slots");
        assertThatThrownBy(() -> menu(3, Map.of("low", item("low", null, -1)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item low slot -1 is outside the entries menu of 27 slots");
    }

    @Test
    void aListSlotOutsideTheMenuIsRefused() {
        PagerLayout pager = new PagerLayout(List.of(9, 10, 45), null, null);

        assertThat(menu(6, Map.of(), pager).pager()).isEqualTo(pager);
        assertThatThrownBy(() -> menu(5, Map.of(), pager)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("list slot 45 is outside the entries menu of 45 slots");
    }

    @Test
    void itemsKeepTheirOrderAndTheItemsOfAnActionFollowIt() {
        Map<String, ItemLayout> items = new LinkedHashMap<>();
        items.put("second", item("second", "browse", 2));
        items.put("frame", item("frame", null, 0, 8));
        items.put("first", item("first", "browse", 1));
        items.put("exit", item("exit", "close", 8));

        MenuLayout layout = menu(1, items, null);

        assertThat(layout.items().keySet()).containsExactly("second", "frame", "first", "exit");
        assertThat(layout.items("browse")).extracting(ItemLayout::id).containsExactly("second", "first");
        assertThat(layout.items("close")).extracting(ItemLayout::id).containsExactly("exit");
        assertThat(layout.items("refresh")).isEmpty();
    }

    @Test
    void layoutsCopyWhatTheyAreGiven() {
        List<Integer> slots = new ArrayList<>(List.of(1, 2));
        List<String> lore = new ArrayList<>(List.of("<gray>one"));
        ItemLayout item = new ItemLayout("pick", slots, Material.STONE, "browse", "menus.pick", true, "<red>Pick",
            lore);
        Map<String, ItemLayout> items = new LinkedHashMap<>(Map.of("pick", item));
        List<Integer> listSlots = new ArrayList<>(List.of(9, 10));
        PagerLayout pager = new PagerLayout(listSlots, 9, Material.BARRIER);
        MenuLayout layout = new MenuLayout("entries", 2, Material.BLACK_STAINED_GLASS_PANE, pager, items, "<b>Title");

        slots.add(3);
        lore.add("two");
        items.clear();
        listSlots.add(11);

        assertThat(item.slots()).containsExactly(1, 2);
        assertThat(item.lore()).containsExactly("<gray>one");
        assertThat(pager.slots()).containsExactly(9, 10);
        assertThat(layout.items()).containsOnlyKeys("pick");
        assertThatThrownBy(() -> layout.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> item.slots().add(4)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(layout.filler()).isEqualTo(Material.BLACK_STAINED_GLASS_PANE);
        assertThat(layout.title()).isEqualTo("<b>Title");
    }

    @Test
    void anItemNeedsAnIdAMaterialAndASlot() {
        assertThatThrownBy(() -> new ItemLayout(null, List.of(1), Material.STONE, null, null, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new ItemLayout("pick", List.of(1), null, null, null, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessage("material");
        assertThatThrownBy(() -> new ItemLayout("pick", List.of(), Material.STONE, null, null, true, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("item pick fills no slot");
        assertThat(new ItemLayout("pick", List.of(1), Material.STONE, null, null, true, null, null).lore()).isNull();
    }

    @Test
    void enabledGivesTheSameItemShownOrHidden() {
        ItemLayout item = new ItemLayout("pick", List.of(1), Material.STONE, "browse", "menus.pick", true, "Pick",
            List.of("one"));

        ItemLayout hidden = item.enabled(false);

        assertThat(hidden.enabled()).isFalse();
        assertThat(hidden).usingRecursiveComparison().ignoringFields("enabled").isEqualTo(item);
        assertThat(hidden.enabled(true)).isEqualTo(item);
    }

    @Test
    void aListFillsAtLeastOneSlotEachFromZeroTo53AndNoneTwice() {
        assertThat(new PagerLayout(List.of(0, 53), null, null).slots()).containsExactly(0, 53);
        assertThatThrownBy(() -> new PagerLayout(List.of(), null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a list fills at least one slot");
        assertThatThrownBy(() -> new PagerLayout(List.of(9, 54), null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot 54 is not a slot from 0 to 53");
        assertThatThrownBy(() -> new PagerLayout(List.of(-1), null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot -1 is not a slot from 0 to 53");
        assertThatThrownBy(() -> new PagerLayout(List.of(9, 10, 9), null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot 9 is listed twice");
        assertThatThrownBy(() -> new PagerLayout(Arrays.asList(9, null), null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theEmptySlotIsOneOfTheListSlots() {
        assertThat(new PagerLayout(List.of(9, 10, 11), 10, null).emptySlot()).isEqualTo(10);
        assertThat(new PagerLayout(List.of(9, 10, 11), null, Material.PAPER).emptySlot()).isNull();
        assertThatThrownBy(() -> new PagerLayout(List.of(9, 10, 11), 22, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("empty slot 22 is not one of the list slots");
    }

    @Test
    void aMenuNeedsAnIdAndItsItems() {
        assertThatThrownBy(() -> new MenuLayout(null, 1, null, null, Map.of(), null))
            .isInstanceOf(NullPointerException.class).hasMessage("menu");
        assertThatThrownBy(() -> new MenuLayout("entries", 1, null, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eachItemIsKeyedByItsOwnId() {
        Map<String, ItemLayout> unkeyed = new HashMap<>();
        unkeyed.put(null, item("pick", null, 1));
        Map<String, ItemLayout> empty = new HashMap<>();
        empty.put("pick", null);

        assertThatThrownBy(() -> menu(1, Map.of("other", item("pick", null, 1)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item pick is under the key other in the entries menu");
        assertThatThrownBy(() -> menu(1, unkeyed, null)).isInstanceOf(NullPointerException.class)
            .hasMessage("item key");
        assertThatThrownBy(() -> menu(1, empty, null)).isInstanceOf(NullPointerException.class)
            .hasMessage("item pick");
    }

    @Test
    void layoutsAreValues() {
        MenuLayout layout = new MenuLayout("entries", 2, null, new PagerLayout(List.of(9), 9, null),
            Map.of("pick", item("pick", "browse", 1)), null);
        MenuLayout same = new MenuLayout("entries", 2, null, new PagerLayout(List.of(9), 9, null),
            Map.of("pick", item("pick", "browse", 1)), null);

        assertThat(layout).isEqualTo(same).hasSameHashCodeAs(same);
    }
}
