package so.alaz.taffy.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

final class PromptListenerPriorityTest {

    @Test
    @SuppressWarnings("deprecation")
    void theChatPromptReadsFirstAndAlsoReadsCancelledLines() throws NoSuchMethodException {
        assertHandler(PaperPromptListener.class, "onChat", AsyncChatEvent.class, EventPriority.LOWEST);
        assertHandler(PaperPromptListener.class, "onLegacyChat", AsyncPlayerChatEvent.class, EventPriority.LOWEST);
    }

    @Test
    void anvilClicksAndDragsAreCancelledFirstAndAgainLastAndClosesAreWatchedAtMonitor()
        throws NoSuchMethodException {
        assertHandler(PaperPromptListener.class, "onAnvilClick", InventoryClickEvent.class, EventPriority.LOWEST);
        assertHandler(PaperPromptListener.class, "recancelAnvilClick", InventoryClickEvent.class,
            EventPriority.HIGHEST);
        assertHandler(PaperPromptListener.class, "onAnvilDrag", InventoryDragEvent.class, EventPriority.LOWEST);
        assertHandler(PaperPromptListener.class, "recancelAnvilDrag", InventoryDragEvent.class,
            EventPriority.HIGHEST);
        assertHandler(PaperPromptListener.class, "onAnvilPrepare", PrepareAnvilEvent.class, EventPriority.HIGHEST);
        assertHandler(PaperPromptListener.class, "onAnvilClose", InventoryCloseEvent.class, EventPriority.MONITOR);
    }

    @Test
    void theOneListenerHoldsTheTwoChatHandlersAndTheSixAnvilHandlers() {
        assertThat(handlers(PaperPromptListener.class)).containsExactlyInAnyOrder("onChat", "onLegacyChat",
            "onAnvilClick", "recancelAnvilClick", "onAnvilDrag", "recancelAnvilDrag", "onAnvilPrepare",
            "onAnvilClose");
    }

    private static Iterable<String> handlers(Class<?> listener) {
        return Arrays.stream(listener.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(
            EventHandler.class)).map(Method::getName).toList();
    }

    private static void assertHandler(Class<?> listener, String name, Class<? extends Event> event,
                                      EventPriority priority) throws NoSuchMethodException {
        EventHandler handler = listener.getDeclaredMethod(name, event).getAnnotation(EventHandler.class);
        assertThat(handler).as(name).isNotNull();
        assertThat(handler.priority()).as(name).isEqualTo(priority);
        assertThat(handler.ignoreCancelled()).as(name).isFalse();
    }
}
