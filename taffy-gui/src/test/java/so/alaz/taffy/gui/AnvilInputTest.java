package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class AnvilInputTest {

    private static final Component QUESTION = Component.text("Price?");

    private final PreviewMarker marker = new PreviewMarker(new NamespacedKey("taffytest", "preview"));
    private final List<String> built = new ArrayList<>();
    private final Map<Player, AnvilView> anvils = new HashMap<>();
    private final Map<Player, InventoryView> showing = new HashMap<>();
    private final ItemStack paper = mock(ItemStack.class);
    private final AnvilInput input = new AnvilInput(marker, (player, question) -> {
        built.add(player.getName() + " " + MenuHarness.plain(question));
        return anvils.get(player);
    }, initial -> {
        built.add("paper " + initial);
        return paper;
    });

    // A player whose openInventory shows the view, as the server does, unless refuses is true.
    private Player player(String name, boolean refuses) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getOpenInventory()).thenAnswer(call -> showing.get(player));
        doAnswer(call -> {
            if (!refuses) {
                showing.put(player, call.getArgument(0));
            }
            return null;
        }).when(player).openInventory(any(InventoryView.class));
        doAnswer(call -> showing.remove(player)).when(player).closeInventory(any(InventoryCloseEvent.Reason.class));
        anvils.put(player, anvil(player));
        return player;
    }

    private static AnvilView anvil(HumanEntity owner) {
        AnvilView view = mock(AnvilView.class);
        AnvilInventory top = mock(AnvilInventory.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getPlayer()).thenReturn(owner);
        return view;
    }

    @Test
    void openShowsAnAnvilTitledWithTheQuestionWithTheMarkedStartingPaperAtNoCost() {
        Player alex = player("Alex", false);
        AnvilView view = anvils.get(alex);

        assertThat(input.open(alex, QUESTION, "10", 7)).isTrue();

        assertThat(built).containsExactly("Alex Price?", "paper 10");
        verify(view.getTopInventory()).setFirstItem(paper);
        verify(paper).editPersistentDataContainer(any());
        verify(view).setRepairCost(0);
        verify(alex).openInventory(view);
        assertThat(alex.getOpenInventory()).isSameAs(view);
        assertThat(input.showing(alex.getUniqueId(), 7)).isTrue();
        assertThat(input.showing(alex.getUniqueId(), 8)).isFalse();
        assertThat(input.owns(view)).isTrue();
        AnvilInput.Open open = input.find(view);
        assertThat(open).isEqualTo(new AnvilInput.Open(view, 7));
    }

    @Test
    void anAnvilTheServerRefusesToOpenLeavesNoEntryAndIsCleared() {
        Player alex = player("Alex", true);
        AnvilView view = anvils.get(alex);

        assertThat(input.open(alex, QUESTION, "", 3)).isFalse();

        verify(view.getTopInventory()).clear();
        assertThat(input.showing(alex.getUniqueId(), 3)).isFalse();
        assertThat(input.owns(view)).isFalse();
        assertThat(input.find(view)).isNull();
    }

    @Test
    void anotherViewOfTheSamePlayerIsNotOwned() {
        Player alex = player("Alex", false);
        input.open(alex, QUESTION, "10", 7);

        assertThat(input.owns(anvil(alex))).isFalse();
        assertThat(input.find(anvil(alex))).isNull();
        assertThat(input.owns(anvil(player("Sam", false)))).isFalse();
    }

    @Test
    void closeActsOnlyForTheCurrentTokenAndClearsTheAnvilBeforeClosingIt() {
        Player alex = player("Alex", false);
        AnvilView view = anvils.get(alex);
        input.open(alex, QUESTION, "10", 7);

        input.close(alex, 6);
        verify(view.getTopInventory(), never()).clear();
        verify(alex, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        assertThat(input.showing(alex.getUniqueId(), 7)).isTrue();

        input.close(alex, 7);
        verify(view.getTopInventory()).clear();
        verify(alex).closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(input.showing(alex.getUniqueId(), 7)).isFalse();
        assertThat(input.owns(view)).isFalse();

        input.close(alex, 7);
        verify(view.getTopInventory()).clear();
        verify(alex).closeInventory(any(InventoryCloseEvent.Reason.class));
    }

    @Test
    void closeClearsButDoesNotCloseForAnOfflinePlayerOrAPlayerWhoMovedOn() {
        Player alex = player("Alex", false);
        Player sam = player("Sam", false);
        input.open(alex, QUESTION, "10", 1);
        input.open(sam, QUESTION, "10", 2);
        when(alex.isOnline()).thenReturn(false);
        showing.put(sam, mock(InventoryView.class));

        input.close(alex, 1);
        input.close(sam, 2);

        verify(anvils.get(alex).getTopInventory()).clear();
        verify(anvils.get(sam).getTopInventory()).clear();
        verify(alex, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        verify(sam, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        assertThat(input.showing(alex.getUniqueId(), 1)).isFalse();
        assertThat(input.showing(sam.getUniqueId(), 2)).isFalse();
    }

    @Test
    void closedRemovesAndReturnsTheEntryOfTheClosingViewOnce() {
        Player alex = player("Alex", false);
        AnvilView view = anvils.get(alex);
        input.open(alex, QUESTION, "10", 7);

        assertThat(input.closed(anvil(alex))).isNull();
        assertThat(input.showing(alex.getUniqueId(), 7)).isTrue();
        assertThat(input.closed(view)).isEqualTo(new AnvilInput.Open(view, 7));
        assertThat(input.closed(view)).isNull();
        assertThat(input.showing(alex.getUniqueId(), 7)).isFalse();
        verify(view.getTopInventory(), never()).clear();
    }

    @Test
    void forgetDropsTheEntryWithoutTouchingTheAnvilOrThePlayer() {
        Player alex = player("Alex", false);
        AnvilView view = anvils.get(alex);
        input.open(alex, QUESTION, "10", 7);

        input.forget(alex.getUniqueId());
        input.forget(UUID.randomUUID());

        assertThat(input.owns(view)).isFalse();
        assertThat(input.showing(alex.getUniqueId(), 7)).isFalse();
        verify(view.getTopInventory(), never()).clear();
        verify(alex, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
    }

    @Test
    void shutdownClosesTheAnvilsOfPlayersTheThreadOwnsAndEmptiesTheRest() {
        Player alex = player("Alex", false);
        Player sam = player("Sam", false);
        input.open(alex, QUESTION, "10", 1);
        input.open(sam, QUESTION, "10", 2);

        input.shutdown(player -> player == alex);

        verify(anvils.get(alex).getTopInventory()).clear();
        verify(alex).closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        verify(anvils.get(sam).getTopInventory()).clear();
        verify(sam, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        assertThat(showing.get(sam)).isSameAs(anvils.get(sam));
        assertThat(input.showing(alex.getUniqueId(), 1)).isFalse();
        assertThat(input.showing(sam.getUniqueId(), 2)).isFalse();
    }

    @Test
    void shutdownEmptiesAnAnvilWhoseViewerIsNoPlayerWithoutAsking() {
        Player alex = player("Alex", false);
        AnvilView view = anvils.get(alex);
        input.open(alex, QUESTION, "10", 1);
        when(view.getPlayer()).thenReturn(mock(HumanEntity.class));
        List<Player> asked = new ArrayList<>();

        input.shutdown(player -> asked.add(player));

        assertThat(asked).isEmpty();
        assertThat(input.showing(alex.getUniqueId(), 1)).isFalse();
        verify(view.getTopInventory()).clear();
    }

    @Test
    void theStartingPaperCarriesTheTextAsACustomNameAndASpaceForNoText() {
        ItemMeta meta = mock(ItemMeta.class);
        List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            arguments.add(context.arguments());
            when(stack.editMeta(any())).thenAnswer(call -> {
                Consumer<ItemMeta> edit = call.getArgument(0);
                edit.accept(meta);
                return true;
            });
        })) {
            ItemStack typed = AnvilInput.paper("10");
            ItemStack empty = AnvilInput.paper("");

            assertThat(stacks.constructed()).containsExactly(typed, empty);
        }

        assertThat(arguments).containsExactly(List.of(Material.PAPER), List.of(Material.PAPER));
        verify(meta).displayName(Component.text("10"));
        verify(meta).displayName(Component.text(" "));
        verify(meta, never()).itemName(any());
    }

    @Test
    void thePublicSurfaceStartsWithNothingOpen() {
        AnvilInput fresh = new AnvilInput(marker);
        Player alex = player("Alex", false);

        assertThat(fresh.owns(anvils.get(alex))).isFalse();
        assertThat(fresh.showing(alex.getUniqueId(), 1)).isFalse();
        fresh.close(alex, 1);
        verifyNoInteractions(anvils.get(alex).getTopInventory());
    }
}
