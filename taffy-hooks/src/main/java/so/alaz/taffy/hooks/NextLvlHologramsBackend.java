package so.alaz.taffy.hooks;

import net.thenextlvl.hologram.Hologram;
import net.thenextlvl.hologram.line.TextHologramLine;
import org.bukkit.Location;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// Holograms by TheNextLvl. Its HologramProvider.instance() caches the plugin's bound instance when first called, so
// it is called only after detection found the plugin enabled. Its names are unique and never replaced.
final class NextLvlHologramsBackend {

    private NextLvlHologramsBackend() {
    }

    static HologramBackend create() {
        Class<net.thenextlvl.hologram.HologramProvider> api = net.thenextlvl.hologram.HologramProvider.class;
        ApiLink.staticMethod(api, "instance", api);
        ApiLink.instanceMethod(api, "hasHologram", boolean.class, String.class);
        ApiLink.instanceMethod(api, "getHologram", Optional.class, String.class);
        ApiLink.instanceMethod(api, "spawnHologram", Hologram.class, String.class, Location.class, Consumer.class);
        ApiLink.instanceMethod(api, "deleteHologram", boolean.class, Hologram.class);
        ApiLink.instanceMethod(Hologram.class, "setPersistent", boolean.class, boolean.class);
        ApiLink.instanceMethod(Hologram.class, "getLineCount", int.class);
        ApiLink.instanceMethod(Hologram.class, "getLine", Optional.class, int.class, Class.class);
        ApiLink.instanceMethod(Hologram.class, "removeLine", boolean.class, int.class);
        ApiLink.instanceMethod(Hologram.class, "setTextLine", TextHologramLine.class, int.class);
        ApiLink.instanceMethod(Hologram.class, "addTextLine", TextHologramLine.class);
        ApiLink.instanceMethod(Hologram.class, "teleportAsync", CompletableFuture.class, Location.class);
        ApiLink.instanceMethod(TextHologramLine.class, "setUnparsedText", boolean.class, String.class);
        Placements<Hologram> placements = new Placements<>(name -> instance().getHologram(name).orElse(null),
            displaced -> { });
        return HologramBackend.of(
            name -> instance().hasHologram(name),
            name -> placements.standing(name) != null,
            (name, location, lines) -> placements.add(name, instance().spawnHologram(name, location, hologram -> {
                hologram.setPersistent(false);
                addLines(hologram, lines, 0);
            })),
            (name, lines) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    setLines(hologram, lines);
                }
            },
            (name, location) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    hologram.teleportAsync(location);
                }
            },
            name -> {
                Hologram hologram = placements.standing(name);
                if (hologram == null || !instance().deleteHologram(hologram)) {
                    return false;
                }
                placements.forget(name, hologram);
                return true;
            });
    }

    private static net.thenextlvl.hologram.HologramProvider instance() {
        return net.thenextlvl.hologram.HologramProvider.instance();
    }

    // Rewrites the text of the lines that stay, so only added or removed lines are spawned or despawned.
    static void setLines(Hologram hologram, List<String> lines) {
        int kept = Math.min(hologram.getLineCount(), lines.size());
        for (int i = 0; i < kept; i++) {
            Optional<TextHologramLine> line = hologram.getLine(i, TextHologramLine.class);
            (line.isPresent() ? line.get() : hologram.setTextLine(i)).setUnparsedText(lines.get(i));
        }
        for (int last = hologram.getLineCount() - 1; last >= lines.size(); last--) {
            hologram.removeLine(last);
        }
        addLines(hologram, lines, kept);
    }

    static void addLines(Hologram hologram, List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            hologram.addTextLine().setUnparsedText(lines.get(i));
        }
    }
}
