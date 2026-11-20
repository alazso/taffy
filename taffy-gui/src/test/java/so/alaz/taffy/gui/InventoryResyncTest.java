package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

final class InventoryResyncTest {

    private static final int BUTTON = 10;
    private static final int FILLER = 20;
    private static final int BOTTOM = 54 + 5;

    @TempDir
    Path folder;

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"SHIFT_LEFT", "SHIFT_RIGHT"})
    void aCancelledShiftClickInAMenuViewSendsTheInventoryAgainOnTheNextTick(ClickType shift) {
        Sample sample = new Sample(folder);

        InventoryClickEvent fromBottom = sample.viewer.click(BOTTOM, shift, InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertThat(fromBottom.isCancelled()).isTrue();
        verify(sample.viewer.player, never()).updateInventory();
        sample.harness.drain();
        verify(sample.viewer.player).updateInventory();

        sample.viewer.click(FILLER, shift, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        sample.viewer.click(BUTTON, shift, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        sample.harness.drain();

        assertThat(sample.acts).hasValue(1);
        verify(sample.viewer.player, times(3)).updateInventory();
    }

    @ParameterizedTest
    @EnumSource(value = ClickType.class, mode = EnumSource.Mode.EXCLUDE, names = {"SHIFT_LEFT", "SHIFT_RIGHT"})
    void aCancelledClickThatIsNoShiftClickSendsNothing(ClickType click) {
        Sample sample = new Sample(folder);

        sample.viewer.click(BOTTOM, click);
        sample.viewer.click(BUTTON, click);
        sample.harness.drain();

        verify(sample.viewer.player, never()).updateInventory();
    }

    @Test
    void aShiftClickOutsideAMenuSendsNothing() {
        Sample sample = new Sample(folder);
        sample.viewer.foreign(GuiFakes.foreign(27));

        InventoryClickEvent event = sample.viewer.click(3, ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);
        sample.harness.drain();

        assertThat(event.isCancelled()).isFalse();
        verify(sample.viewer.player, never()).updateInventory();
    }

    @Test
    void aShiftClickByAnotherPlayerInTheViewersMenuSendsThatPlayersInventoryAgain() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer admin = sample.harness.viewer("Admin");
        FakeView shared = new FakeView(sample.viewer.current().getTopInventory(), admin.inventory, admin.player);

        admin.clickOn(shared, BOTTOM, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1);
        sample.harness.drain();

        verify(admin.player).updateInventory();
        verify(sample.viewer.player, never()).updateInventory();
    }

    @ParameterizedTest
    @EnumSource(value = InventoryCloseEvent.Reason.class, mode = EnumSource.Mode.EXCLUDE, names = "OPEN_NEW")
    void aClosedMenuSendsTheViewersInventoryAgainOnTheNextTick(InventoryCloseEvent.Reason reason) {
        Sample sample = new Sample(folder);

        sample.viewer.player.closeInventory(reason);

        assertThat(sample.menu.closes).containsExactly(true);
        verify(sample.viewer.player, never()).updateInventory();
        sample.harness.drain();
        verify(sample.viewer.player).updateInventory();
    }

    @Test
    void aMenuThatAnotherInventoryReplacesSendsNothing() {
        Sample sample = new Sample(folder);

        sample.harness.open(sample.viewer, Sample.KIND.id());
        sample.harness.drain();

        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.OPEN_NEW);
        verify(sample.viewer.player, never()).updateInventory();
    }

    @Test
    void withTwoMenusOverOneViewerTheInventoryIsSentOncePerShiftClickAndClose() {
        Sample sample = new Sample(folder);
        sample.harness.another();

        sample.viewer.click(BOTTOM, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        sample.harness.drain();

        verify(sample.viewer.player).updateInventory();
        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        sample.harness.drain();
        verify(sample.viewer.player, times(2)).updateInventory();
    }

    @Test
    void shutdownSendsTheInventoryAgainOnlyToTheViewersWhoseMenuItClosed() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer elsewhere = sample.harness.viewer("Sam");
        sample.harness.open(elsewhere, Sample.KIND.id());
        sample.harness.owned = player -> player != elsewhere.player;

        sample.harness.menus.shutdown();

        verify(sample.viewer.player).updateInventory();
        verify(elsewhere.player, never()).updateInventory();
        assertThat(sample.harness.tasks.queue).isEmpty();
    }

    @Test
    void shutdownSendsTheInventoryAgainWhenNoCloseEventArrives() {
        Sample sample = new Sample(folder);
        sample.harness.listening = false;

        sample.harness.menus.shutdown();

        verify(sample.viewer.player).updateInventory();
    }
}
