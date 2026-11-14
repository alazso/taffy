package so.alaz.taffy.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static so.alaz.taffy.test.FakeItems.item;

class ItemsTest {

    private final World world = mock(World.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Player player = mock(Player.class);
    private final Location feet = new Location(world, 10.5, 64, -3.5);

    @BeforeEach
    void placeThePlayer() {
        when(player.getInventory()).thenReturn(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(feet);
    }

    @Test
    void anItemThatFitsIsAddedAndNothingIsDropped() {
        ItemStack item = item("diamond", 5);
        when(inventory.addItem(item)).thenReturn(new HashMap<>());

        Items.giveOrDrop(player, item);

        verify(inventory).addItem(item);
        verifyNoInteractions(world);
    }

    @Test
    void whatDoesNotFitIsDroppedAtThePlayersLocation() {
        ItemStack item = item("diamond", 70);
        ItemStack leftover = item("diamond", 6);
        when(inventory.addItem(item)).thenReturn(new HashMap<>(Map.of(0, leftover)));

        Items.giveOrDrop(player, item);

        verify(inventory).addItem(item);
        verify(world).dropItemNaturally(feet, leftover);
        verifyNoMoreInteractions(world);
    }
}
