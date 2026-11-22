package so.alaz.taffy.gui;

import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// Server-free stand-ins for the seams of the prompts.
final class PromptFakes {

    private PromptFakes() {
    }

    // Whether the player sees a view of their own inventory, as the harness gives one with no menu open.
    static boolean ownScreen(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder(false) == player;
    }

    // Records each delayed task and runs the ones of a delay when a test fires it.
    static final class ManualTimer implements BiConsumer<Runnable, Duration> {

        private record Waiting(Duration after, Runnable task) {
        }

        private final List<Waiting> waiting = new ArrayList<>();

        @Override
        public void accept(Runnable task, Duration after) {
            waiting.add(new Waiting(after, task));
        }

        List<Duration> waits() {
            return waiting.stream().map(Waiting::after).toList();
        }

        void fire(Duration after) {
            List<Waiting> due = waiting.stream().filter(entry -> entry.after().equals(after)).toList();
            waiting.removeAll(due);
            due.forEach(entry -> entry.task().run());
        }
    }

    // Records each link and runs its action when a test clicks it.
    static final class FakeLink implements CancelLink {

        final List<ClickEvent<?>> events = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        @Override
        public ClickEvent<?> link(Runnable action) {
            ClickEvent<?> event = ClickEvent.runCommand("/prompt-test " + actions.size());
            events.add(event);
            actions.add(action);
            return event;
        }

        void click(int index) {
            actions.get(index).run();
        }
    }

    // Records each dialog shown and each close. With failure set, show throws it and records nothing.
    static final class FakeDialogs implements Dialogs {

        record Shown(Player player, Form form) {
        }

        final List<Shown> shown = new ArrayList<>();
        final List<Player> closed = new ArrayList<>();
        @Nullable Throwable failure;

        @Override
        public void show(Player player, Form form) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException thrown) {
                throw thrown;
            }
            shown.add(new Shown(player, form));
        }

        @Override
        public void close(Player player) {
            closed.add(player);
        }

        Form last() {
            return shown.getLast().form();
        }

        // Presses the submit button of the dialog shown at index with the text in its field.
        void submit(int index, String text) {
            shown.get(index).form().submit().accept(text);
        }

        // Presses the cancel button of the dialog shown at index.
        void cancel(int index) {
            shown.get(index).form().cancel().run();
        }
    }
}
