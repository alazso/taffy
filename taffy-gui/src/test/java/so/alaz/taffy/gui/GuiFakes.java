package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import so.alaz.taffy.platform.EntityThread;
import so.alaz.taffy.platform.EntityUnavailableException;
import so.alaz.taffy.test.FakeItems;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Server-free stand-ins for the menu framework's seams.
final class GuiFakes {

    private GuiFakes() {
    }

    // A stack carrying the marker's key, as do its clones.
    static ItemStack marked(Material material, PreviewMarker marker) {
        ItemStack stack = FakeItems.item(material, 1);
        when(stack.getPersistentDataContainer()).thenReturn(new FakeData().put(marker.key(), (byte) 1));
        when(stack.clone()).thenAnswer(call -> marked(material, marker));
        return stack;
    }

    // A mocked inventory of the given size whose holder is not a menu.
    static Inventory foreign(int size) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(mock(InventoryHolder.class));
        when(inventory.getSize()).thenReturn(size);
        return inventory;
    }

    // Records the icon written to each slot and how many writes happened.
    static final class FakeCanvas implements Canvas {

        final Icon[] slots;
        int writes;

        FakeCanvas(int size) {
            slots = new Icon[size];
        }

        @Override
        public void put(int slot, Icon icon) {
            slots[slot] = icon;
            writes++;
        }
    }

    // A mocked inventory per create, answering its holder and size, with a FakeCanvas that clear() empties.
    static final class FakeInventories implements Inventories {

        final List<Inventory> created = new ArrayList<>();
        final List<Component> titles = new ArrayList<>();
        final Map<Inventory, FakeCanvas> canvases = new ConcurrentHashMap<>();

        @Override
        public Surface create(InventoryHolder holder, int size, Component title) {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder(false)).thenReturn(holder);
            when(inventory.getHolder()).thenReturn(holder);
            when(inventory.getSize()).thenReturn(size);
            FakeCanvas canvas = new FakeCanvas(size);
            doAnswer(call -> {
                Arrays.fill(canvas.slots, null);
                return null;
            }).when(inventory).clear();
            created.add(inventory);
            titles.add(title);
            canvases.put(inventory, canvas);
            return new Surface(inventory, canvas);
        }
    }

    // Tasks queue until drain runs them in order, including tasks they queue.
    static final class FakeTasks implements BiConsumer<Player, Runnable> {

        final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void accept(Player player, Runnable task) {
            queue.add(task);
        }

        void drain() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }
    }

    // Runs entity tasks inline for online players; offline players fail with EntityUnavailableException.
    static final class DirectEntity implements EntityThread {

        final Map<UUID, Player> online = new ConcurrentHashMap<>();

        @Override
        public <T> CompletableFuture<T> call(UUID player, Function<Player, T> task) {
            Player live = online.get(player);
            if (live == null) {
                return CompletableFuture.failedFuture(new EntityUnavailableException(player));
            }
            try {
                return CompletableFuture.completedFuture(task.apply(live));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    // A clock that stands still until advanced.
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
