package so.alaz.taffy.gui;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

final class ListenerPriorityTest {

    @Test
    void clicksAndDragsAreCancelledFirstAndAgainLastAndClosesAreWatchedAtMonitor() throws NoSuchMethodException {
        assertHandler("onClick", InventoryClickEvent.class, EventPriority.LOWEST);
        assertHandler("recancelClick", InventoryClickEvent.class, EventPriority.HIGHEST);
        assertHandler("onDrag", InventoryDragEvent.class, EventPriority.LOWEST);
        assertHandler("recancelDrag", InventoryDragEvent.class, EventPriority.HIGHEST);
        assertHandler("onClose", InventoryCloseEvent.class, EventPriority.MONITOR);
        assertHandler("onQuit", PlayerQuitEvent.class, EventPriority.MONITOR);
    }

    @Test
    void everyHandlerIsOneOfThese() {
        assertThat(Arrays.stream(MenuListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class)).map(Method::getName))
            .containsExactlyInAnyOrder("onClick", "recancelClick", "onDrag", "recancelDrag", "onClose", "onQuit");
    }

    private static void assertHandler(String name, Class<? extends Event> event, EventPriority priority)
        throws NoSuchMethodException {
        EventHandler handler = MenuListener.class.getDeclaredMethod(name, event).getAnnotation(EventHandler.class);
        assertThat(handler).as(name).isNotNull();
        assertThat(handler.priority()).as(name).isEqualTo(priority);
        assertThat(handler.ignoreCancelled()).as(name).isFalse();
    }
}
