package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Paper builds each handler's executor with EventExecutor.create, which makes the method accessible and calls it
// through a hidden class. This runs that path over the package-private listener classes.
final class PaperRegistrationTest {

    @TempDir
    Path folder;

    @Test
    void theListenerClassesAreNotPublicAndEveryHandlerIsPublic() {
        for (Class<? extends Listener> listener : List.of(MenuListener.class, PaperPromptListener.class)) {
            assertThat(Modifier.isPublic(listener.getModifiers())).as(listener.getSimpleName()).isFalse();
            assertThat(handlers(listener)).isNotEmpty()
                .allMatch(method -> Modifier.isPublic(method.getModifiers()));
        }
    }

    @Test
    void paperBuildsAnExecutorForEveryHandler() {
        for (Class<? extends Listener> listener : List.of(MenuListener.class, PaperPromptListener.class)) {
            for (Method method : handlers(listener)) {
                assertThat(EventExecutor.create(method, method.getParameterTypes()[0].asSubclass(Event.class)))
                    .as(listener.getSimpleName() + "." + method.getName()).isNotNull();
            }
        }
    }

    @Test
    void theExecutorPaperBuildsReachesTheHandler() throws Exception {
        Sample sample = new Sample(folder);
        sample.harness.menus.memory().put(sample.viewer.id, "sample", "tab");
        Method onQuit = MenuListener.class.getDeclaredMethod("onQuit", PlayerQuitEvent.class);
        EventExecutor executor = EventExecutor.create(onQuit, PlayerQuitEvent.class);

        executor.execute(sample.harness.listener, new PlayerQuitEvent(sample.viewer.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(sample.harness.menus.memory().get(sample.viewer.id, "sample", String.class)).isNull();
        assertThat(sample.harness.prompts.quit).containsExactly(sample.viewer.id);
    }

    private static List<Method> handlers(Class<?> listener) {
        return Arrays.stream(listener.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(
            EventHandler.class)).toList();
    }
}
