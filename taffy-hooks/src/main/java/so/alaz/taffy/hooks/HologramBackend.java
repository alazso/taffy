package so.alaz.taffy.hooks;

import org.bukkit.Location;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

// The calls into one hologram plugin, by the full name the plugin stores. A call may throw whatever the plugin throws.
interface HologramBackend {

    // Whether any hologram, placed by this backend or not, holds the name.
    boolean taken(String name);

    // Whether the hologram this backend placed under the name still stands.
    boolean has(String name);

    void create(String name, Location location, List<String> lines);

    void update(String name, List<String> lines);

    void move(String name, Location location);

    // Whether the hologram plugin removed the hologram; false when the plugin refused.
    boolean remove(String name);

    @FunctionalInterface
    interface Creator {
        void create(String name, Location location, List<String> lines);
    }

    static HologramBackend of(Predicate<String> taken, Predicate<String> has, Creator create,
                              BiConsumer<String, List<String>> update, BiConsumer<String, Location> move,
                              Predicate<String> remove) {
        return new Calls(taken, has, create, update, move, remove);
    }

    record Calls(Predicate<String> taker, Predicate<String> holder, Creator creator,
                 BiConsumer<String, List<String>> updater, BiConsumer<String, Location> mover,
                 Predicate<String> remover) implements HologramBackend {

        @Override
        public boolean taken(String name) {
            return taker.test(name);
        }

        @Override
        public boolean has(String name) {
            return holder.test(name);
        }

        @Override
        public void create(String name, Location location, List<String> lines) {
            creator.create(name, location, lines);
        }

        @Override
        public void update(String name, List<String> lines) {
            updater.accept(name, lines);
        }

        @Override
        public void move(String name, Location location) {
            mover.accept(name, location);
        }

        @Override
        public boolean remove(String name) {
            return remover.test(name);
        }
    }
}
