package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class LayoutTest {

    private static ItemLayout item(String id, @Nullable String action, Integer... slots) {
        return ItemLayout.of(id, List.of(slots), Material.STONE).action(action);
    }

    private static MenuLayout menu(int rows, List<ItemLayout> items, @Nullable PagerLayout pager) {
        return MenuLayout.of("entries", rows).pager(pager).items(items);
    }

    @Test
    void rowsAreOneToSix() {
        assertThat(menu(1, List.of(), null).size()).isEqualTo(9);
        assertThat(menu(6, List.of(), null).size()).isEqualTo(54);
        assertThatThrownBy(() -> menu(0, List.of(), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the entries menu has 1 to 6 rows, not 0");
        assertThatThrownBy(() -> menu(7, List.of(), null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the entries menu has 1 to 6 rows, not 7");
    }

    @Test
    void ofGivesALayoutWithNothingButItsMenuAndRows() {
        MenuLayout layout = MenuLayout.of("entries", 3);

        assertThat(layout.menu()).isEqualTo("entries");
        assertThat(layout.rows()).isEqualTo(3);
        assertThat(layout.filler()).isNull();
        assertThat(layout.pager()).isNull();
        assertThat(layout.items()).isEmpty();
        assertThat(layout.title()).isNull();
    }

    @Test
    void anItemSlotOutsideTheMenuIsRefused() {
        assertThat(menu(3, List.of(item("last", null, 26)), null).items()).containsKey("last");
        assertThatThrownBy(() -> menu(3, List.of(item("late", null, 4, 27)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item late slot 27 is outside the entries menu of 27 slots");
        assertThatThrownBy(() -> menu(3, List.of(item("low", null, -1)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item low slot -1 is outside the entries menu of 27 slots");
        assertThatThrownBy(() -> MenuLayout.of("entries", 3).item(item("late", null, 27)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item late slot 27 is outside the entries menu of 27 slots");
    }

    @Test
    void aListSlotOutsideTheMenuIsRefused() {
        PagerLayout pager = PagerLayout.of(List.of(9, 10, 45));

        assertThat(menu(6, List.of(), pager).pager()).isEqualTo(pager);
        assertThatThrownBy(() -> menu(5, List.of(), pager)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("list slot 45 is outside the entries menu of 45 slots");
    }

    @Test
    void itemsKeepTheirOrderAndTheItemsOfAnActionFollowIt() {
        MenuLayout layout = menu(1, List.of(item("second", "browse", 2), item("frame", null, 0, 8),
            item("first", "browse", 1), item("exit", "close", 8)), null);

        assertThat(layout.items().keySet()).containsExactly("second", "frame", "first", "exit");
        assertThat(layout.items("browse")).extracting(ItemLayout::id).containsExactly("second", "first");
        assertThat(layout.items("close")).extracting(ItemLayout::id).containsExactly("exit");
        assertThat(layout.items("refresh")).isEmpty();
    }

    @Test
    void layoutsCopyWhatTheyAreGiven() {
        List<Integer> slots = new ArrayList<>(List.of(1, 2));
        List<String> lore = new ArrayList<>(List.of("<gray>one"));
        ItemLayout item = ItemLayout.of("pick", slots, Material.STONE).action("browse").permission("menus.pick")
            .name("<red>Pick").lore(lore);
        List<ItemLayout> items = new ArrayList<>(List.of(item));
        List<Integer> listSlots = new ArrayList<>(List.of(9, 10));
        PagerLayout pager = PagerLayout.of(listSlots).emptySlot(9).emptyMaterial(Material.BARRIER);
        MenuLayout layout = MenuLayout.of("entries", 2).filler(Material.BLACK_STAINED_GLASS_PANE).pager(pager)
            .items(items).title("<b>Title");

        slots.add(3);
        lore.add("two");
        items.clear();
        listSlots.add(11);

        assertThat(item.slots()).containsExactly(1, 2);
        assertThat(item.lore()).containsExactly("<gray>one");
        assertThat(pager.slots()).containsExactly(9, 10);
        assertThat(layout.items()).containsOnlyKeys("pick");
        assertThatThrownBy(() -> layout.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> layout.item(item("more", null, 3)).items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> item.slots().add(4)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pager.slots().add(11)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(layout.filler()).isEqualTo(Material.BLACK_STAINED_GLASS_PANE);
        assertThat(layout.title()).isEqualTo("<b>Title");
    }

    @Test
    void ofGivesAShownItemWithNoOptionalText() {
        ItemLayout item = ItemLayout.of("pick", List.of(1, 2), Material.STONE);

        assertThat(item.id()).isEqualTo("pick");
        assertThat(item.slots()).containsExactly(1, 2);
        assertThat(item.material()).isEqualTo(Material.STONE);
        assertThat(item.enabled()).isTrue();
        assertThat(item.action()).isNull();
        assertThat(item.permission()).isNull();
        assertThat(item.name()).isNull();
        assertThat(item.lore()).isNull();
    }

    @Test
    void ofNeedsAnIdAMaterialAndASlot() {
        assertThatThrownBy(() -> ItemLayout.of(null, List.of(1), Material.STONE))
            .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> ItemLayout.of("pick", List.of(1), null))
            .isInstanceOf(NullPointerException.class).hasMessage("material");
        assertThatThrownBy(() -> ItemLayout.of("pick", List.of(), Material.STONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("item pick fills no slot");
    }

    @Test
    void eachItemMethodReplacesOnlyItsOwnValue() {
        ItemLayout item = ItemLayout.of("pick", List.of(1), Material.STONE).action("browse")
            .permission("menus.pick").enabled(false).name("Pick").lore(List.of("one"));

        ItemLayout acted = item.action("close");
        ItemLayout guarded = item.permission("menus.other");
        ItemLayout named = item.name("Other");
        ItemLayout described = item.lore(List.of("two", "three"));

        assertThat(acted.action()).isEqualTo("close");
        assertThat(acted.action("browse")).isEqualTo(item);
        assertThat(guarded.permission()).isEqualTo("menus.other");
        assertThat(guarded.permission("menus.pick")).isEqualTo(item);
        assertThat(named.name()).isEqualTo("Other");
        assertThat(named.name("Pick")).isEqualTo(item);
        assertThat(described.lore()).containsExactly("two", "three");
        assertThat(described.lore(List.of("one"))).isEqualTo(item);
        assertThat(item.action(null).action()).isNull();
        assertThat(item.permission(null).permission()).isNull();
        assertThat(item.name(null).name()).isNull();
        assertThat(item.action()).isEqualTo("browse");
        assertThat(item.permission()).isEqualTo("menus.pick");
        assertThat(item.enabled()).isFalse();
        assertThat(item.name()).isEqualTo("Pick");
        assertThat(item.lore()).containsExactly("one");
    }

    @Test
    void loreCopiesTheListAndNullKeepsItNull() {
        List<String> lore = new ArrayList<>(List.of("one"));

        ItemLayout item = ItemLayout.of("pick", List.of(1), Material.STONE).lore(lore);
        lore.add("two");

        assertThat(item.lore()).containsExactly("one");
        assertThatThrownBy(() -> item.lore().add("three")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(item.lore(null).lore()).isNull();
        assertThatThrownBy(() -> item.lore(Arrays.asList("one", null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void enabledGivesTheSameItemShownOrHidden() {
        ItemLayout item = ItemLayout.of("pick", List.of(1), Material.STONE).action("browse")
            .permission("menus.pick").name("Pick").lore(List.of("one"));

        ItemLayout hidden = item.enabled(false);

        assertThat(hidden.enabled()).isFalse();
        assertThat(hidden).isNotEqualTo(item);
        assertThat(hidden.enabled(true)).isEqualTo(item);
    }

    @Test
    void aListFillsAtLeastOneSlotEachFromZeroTo53AndNoneTwice() {
        assertThat(PagerLayout.of(List.of(0, 53)).slots()).containsExactly(0, 53);
        assertThatThrownBy(() -> PagerLayout.of(List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a list fills at least one slot");
        assertThatThrownBy(() -> PagerLayout.of(List.of(9, 54)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot 54 is not a slot from 0 to 53");
        assertThatThrownBy(() -> PagerLayout.of(List.of(-1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot -1 is not a slot from 0 to 53");
        assertThatThrownBy(() -> PagerLayout.of(List.of(9, 10, 9)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list slot 9 is listed twice");
        assertThatThrownBy(() -> PagerLayout.of(Arrays.asList(9, null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PagerLayout.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofGivesAListWithNoEmptyState() {
        PagerLayout pager = PagerLayout.of(List.of(9, 10, 11));

        assertThat(pager.emptySlot()).isNull();
        assertThat(pager.emptyMaterial()).isNull();
    }

    @Test
    void theEmptySlotIsOneOfTheListSlots() {
        PagerLayout pager = PagerLayout.of(List.of(9, 10, 11));

        assertThat(pager.emptySlot(10).emptySlot()).isEqualTo(10);
        assertThat(pager.emptySlot(10).emptySlot(null).emptySlot()).isNull();
        assertThat(pager.emptyMaterial(Material.PAPER).emptySlot()).isNull();
        assertThatThrownBy(() -> pager.emptySlot(22))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("empty slot 22 is not one of the list slots");
    }

    @Test
    void eachListMethodReplacesOnlyItsOwnValue() {
        PagerLayout pager = PagerLayout.of(List.of(9, 10, 11)).emptySlot(10).emptyMaterial(Material.BARRIER);

        assertThat(pager.emptySlot(9).emptyMaterial()).isEqualTo(Material.BARRIER);
        assertThat(pager.emptyMaterial(null).emptySlot()).isEqualTo(10);
        assertThat(pager.emptyMaterial(null).emptyMaterial()).isNull();
        assertThat(pager.emptySlot()).isEqualTo(10);
        assertThat(pager.emptyMaterial()).isEqualTo(Material.BARRIER);
        assertThat(pager.slots()).containsExactly(9, 10, 11);
    }

    @Test
    void aMenuNeedsAnIdAndItsItems() {
        assertThatThrownBy(() -> MenuLayout.of(null, 1)).isInstanceOf(NullPointerException.class).hasMessage("menu");
        assertThatThrownBy(() -> MenuLayout.of("entries", 1).items((List<ItemLayout>) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MenuLayout.of("entries", 1).items(Arrays.asList(item("pick", null, 1), null)))
            .isInstanceOf(NullPointerException.class).hasMessage("item");
        assertThatThrownBy(() -> MenuLayout.of("entries", 1).item(null))
            .isInstanceOf(NullPointerException.class).hasMessage("item");
    }

    @Test
    void eachItemIsKeyedByItsOwnIdAndNoIdIsGivenTwice() {
        MenuLayout layout = menu(1, List.of(item("pick", null, 1), item("exit", "close", 8)), null);

        assertThat(layout.items()).containsOnlyKeys("pick", "exit");
        assertThat(layout.items().get("pick")).isEqualTo(item("pick", null, 1));
        assertThatThrownBy(() -> menu(1, List.of(item("pick", null, 1), item("pick", "close", 2)), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("item pick is in the entries menu twice");
    }

    @Test
    void itemAddsAnItemLastOrReplacesTheItemOfTheSameIdInItsPlace() {
        MenuLayout layout = menu(1, List.of(item("pick", null, 1), item("exit", "close", 8)), null);

        MenuLayout added = layout.item(item("more", null, 2));
        MenuLayout replaced = added.item(item("pick", "browse", 3));

        assertThat(layout.items().keySet()).containsExactly("pick", "exit");
        assertThat(added.items().keySet()).containsExactly("pick", "exit", "more");
        assertThat(replaced.items().keySet()).containsExactly("pick", "exit", "more");
        assertThat(replaced.items().get("pick")).isEqualTo(item("pick", "browse", 3));
        assertThat(added.items().get("pick")).isEqualTo(item("pick", null, 1));
    }

    @Test
    void eachLayoutMethodReplacesOnlyItsOwnValue() {
        PagerLayout pager = PagerLayout.of(List.of(9));
        MenuLayout layout = MenuLayout.of("entries", 2).filler(Material.STONE).pager(pager)
            .items(List.of(item("pick", null, 1))).title("Title");

        assertThat(layout.filler(null).filler()).isNull();
        assertThat(layout.filler(null).filler(Material.STONE)).isEqualTo(layout);
        assertThat(layout.pager(null).pager()).isNull();
        assertThat(layout.pager(null).pager(pager)).isEqualTo(layout);
        assertThat(layout.items(List.of()).items()).isEmpty();
        assertThat(layout.items(List.of()).item(item("pick", null, 1))).isEqualTo(layout);
        assertThat(layout.title(null).title()).isNull();
        assertThat(layout.title(null).title("Title")).isEqualTo(layout);
        assertThat(layout.menu()).isEqualTo("entries");
        assertThat(layout.rows()).isEqualTo(2);
    }

    @Test
    void layoutsAreValues() {
        MenuLayout layout = MenuLayout.of("entries", 2).pager(PagerLayout.of(List.of(9)).emptySlot(9))
            .item(item("pick", "browse", 1));
        MenuLayout same = MenuLayout.of("entries", 2).pager(PagerLayout.of(List.of(9)).emptySlot(9))
            .item(item("pick", "browse", 1));

        assertThat(layout).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(same.title("Other"));
        assertThat(layout.pager()).isEqualTo(same.pager()).hasSameHashCodeAs(same.pager())
            .isNotEqualTo(PagerLayout.of(List.of(9)));
        assertThat(item("pick", "browse", 1)).isEqualTo(item("pick", "browse", 1))
            .hasSameHashCodeAs(item("pick", "browse", 1)).isNotEqualTo(item("pick", "close", 1));
        assertThat(layout).hasToString("MenuLayout[menu=entries, rows=2, filler=null, "
            + "pager=PagerLayout[slots=[9], emptySlot=9, emptyMaterial=null], items={pick=ItemLayout[id=pick, "
            + "slots=[1], material=STONE, action=browse, permission=null, enabled=true, name=null, lore=null]}, "
            + "title=null]");
    }

    @Test
    void noLayoutTypeIsARecordOrHasAPublicConstructor() {
        assertThat(Stream.of(ItemLayout.class, MenuLayout.class, PagerLayout.class, Menus.Parts.class))
            .allSatisfy(type -> {
                assertThat(type.isRecord()).isFalse();
                assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
                assertThat(Arrays.stream(type.getDeclaredConstructors()).map(c -> c.getModifiers()))
                    .allMatch(Modifier::isPrivate);
            });
    }
}
