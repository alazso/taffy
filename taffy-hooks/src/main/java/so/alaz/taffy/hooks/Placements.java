package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

// The hologram objects a backend placed, by name, so a call reaches only an object the backend placed. Where a
// hologram plugin lets another hologram replace one under the same name, such as one a server owner makes or one
// loaded from the plugin's files, the replaced object is handed to `displaced` to be hidden by object, never by name.
final class Placements<H> {

    private final Map<String, H> placed = new ConcurrentHashMap<>();
    private final Function<String, @Nullable H> lookup;
    private final Consumer<H> displaced;

    Placements(Function<String, @Nullable H> lookup, Consumer<H> displaced) {
        this.lookup = lookup;
        this.displaced = displaced;
    }

    void add(String name, H hologram) {
        placed.put(name, hologram);
    }

    // The hologram placed under the name while the plugin still holds that same object, else null.
    @Nullable H standing(String name) {
        H mine = placed.get(name);
        if (mine == null) {
            return null;
        }
        H held = lookup.apply(name);
        if (held == mine) {
            return mine;
        }
        if (placed.remove(name, mine) && held != null) {
            displaced.accept(mine);
        }
        return null;
    }

    void forget(String name, H hologram) {
        placed.remove(name, hologram);
    }
}
