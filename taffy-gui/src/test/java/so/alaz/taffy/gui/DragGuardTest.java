package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.FakeItems;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

final class DragGuardTest {

    @TempDir
    Path folder;

    @Test
    void everyDragInAMenuViewIsCancelled() {
        Sample sample = new Sample(folder);
        ItemStack cursor = FakeItems.item(Material.STONE, 4);

        assertThat(sample.viewer.drag(cursor, 10, 11).isCancelled()).isTrue();
        assertThat(sample.viewer.drag(cursor, 60, 61).isCancelled()).isTrue();
        assertThat(sample.viewer.drag(cursor, 20, 60).isCancelled()).isTrue();
        assertThat(sample.harness.log.lines()).isEmpty();
    }

    @Test
    void theHighestHandlerCancelsAgainADragAnotherPluginUncancelled() {
        Sample sample = new Sample(folder);
        InventoryDragEvent event = sample.viewer.drag(FakeItems.item(Material.STONE, 4), 10);

        event.setCancelled(false);
        sample.harness.listener.recancelDrag(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    void aDragOutsideMenusWithAnUnmarkedCursorIsLeftAlone() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        viewer.foreign(GuiFakes.foreign(27));

        InventoryDragEvent event = viewer.drag(FakeItems.item(Material.STONE, 4), 1, 2);
        harness.listener.recancelDrag(event);
        harness.drain();

        assertThat(event.isCancelled()).isFalse();
        assertThat(harness.log.lines()).isEmpty();
    }

    @Test
    void aDragOutsideMenusWithAMarkedCursorIsCancelledAndTheCursorClearedNextTick() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        FakeView view = viewer.foreign(GuiFakes.foreign(27));
        ItemStack copy = GuiFakes.marked(Material.DIAMOND, harness.marker);

        InventoryDragEvent event = viewer.drag(copy, 1, 2);
        view.setCursor(copy);

        assertThat(event.isCancelled()).isTrue();
        assertThat(harness.log.lines()).isEmpty();
        harness.drain();
        assertThat(view.getCursor()).isNull();
        assertThat(harness.log.lines()).containsExactly("WARNING preview.leak: Alex (" + viewer.id + ") held a menu"
            + " copy of DIAMOND as cursor; it was deleted.");
    }

    @Test
    void aMarkedCursorDragByAnEntityThatIsNoPlayerIsCancelledWithNothingQueued() {
        MenuHarness harness = new MenuHarness(folder);
        HumanEntity entity = mock(HumanEntity.class);
        FakeView view = new FakeView(GuiFakes.foreign(27), mock(PlayerInventory.class), entity);
        ItemStack copy = GuiFakes.marked(Material.DIAMOND, harness.marker);
        view.setCursor(copy);

        InventoryDragEvent event = new InventoryDragEvent(view, null, copy, false, Map.of(1, copy));
        harness.listener.onDrag(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(harness.tasks.queue).isEmpty();
        assertThat(view.getCursor()).isSameAs(copy);
        assertThat(harness.log.lines()).isEmpty();
    }

    @Test
    void aCursorNoLongerMarkedOnTheNextTickIsNotTouchedOrLogged() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        FakeView view = viewer.foreign(GuiFakes.foreign(27));
        ItemStack other = FakeItems.item(Material.STONE, 1);

        viewer.drag(GuiFakes.marked(Material.DIAMOND, harness.marker), 1);
        view.setCursor(other);
        harness.drain();

        assertThat(view.getCursor()).isSameAs(other);
        assertThat(harness.log.lines()).isEmpty();
    }

    @Test
    void aDragInAPromptsOwnViewIsLeftToThePrompt() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        FakeView view = viewer.foreign(GuiFakes.foreign(3));
        harness.prompts.views.add(view);

        InventoryDragEvent event = viewer.drag(GuiFakes.marked(Material.PAPER, harness.marker), 0);
        harness.drain();

        assertThat(event.isCancelled()).isFalse();
        assertThat(harness.log.lines()).isEmpty();
    }
}
