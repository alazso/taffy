package so.alaz.taffy.hooks;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

// FancyHolograms renders MiniMessage and PlaceholderAPI for each viewer, but renders placeholders again only on a text
// update interval, which a hologram has none of by default. Its removeHologram also rewrites holograms.yml, so a
// removal while the server stops is left to FancyHolograms, which drops temporary holograms unsaved when it disables.
final class FancyHologramsBackend {

    static final int PLACEHOLDER_REFRESH_MILLIS = 1000;
    static final int NO_REFRESH = -1;

    private FancyHologramsBackend() {
    }

    static HologramBackend create() {
        ApiLink.staticMethod(FancyHologramsPlugin.class, "get", FancyHologramsPlugin.class);
        ApiLink.instanceMethod(FancyHologramsPlugin.class, "getHologramManager", HologramManager.class);
        ApiLink.instanceMethod(HologramManager.class, "getHologram", Optional.class, String.class);
        ApiLink.instanceMethod(HologramManager.class, "create", Hologram.class, HologramData.class);
        ApiLink.instanceMethod(HologramManager.class, "addHologram", void.class, Hologram.class);
        ApiLink.instanceMethod(HologramManager.class, "removeHologram", void.class, Hologram.class);
        ApiLink.constructor(TextHologramData.class, String.class, Location.class);
        ApiLink.instanceMethod(TextHologramData.class, "setText", TextHologramData.class, List.class);
        ApiLink.instanceMethod(TextHologramData.class, "setTextUpdateInterval", TextHologramData.class, int.class);
        ApiLink.instanceMethod(HologramData.class, "setPersistent", HologramData.class, boolean.class);
        ApiLink.instanceMethod(HologramData.class, "setLocation", HologramData.class, Location.class);
        ApiLink.instanceMethod(Hologram.class, "getData", HologramData.class);
        ApiLink.instanceMethod(Hologram.class, "queueUpdate", void.class);
        ApiLink.instanceMethod(Hologram.class, "getViewers", Set.class);
        ApiLink.instanceMethod(Hologram.class, "forceHideHologram", void.class, Player.class);
        return create(() -> FancyHologramsPlugin.get().getHologramManager());
    }

    static HologramBackend create(Supplier<HologramManager> managers) {
        Placements<Hologram> placements = new Placements<>(name -> managers.get().getHologram(name).orElse(null),
            FancyHologramsBackend::hide);
        return HologramBackend.of(
            name -> managers.get().getHologram(name).isPresent(),
            name -> placements.standing(name) != null,
            (name, location, lines) -> {
                HologramManager manager = managers.get();
                TextHologramData data = new TextHologramData(name, location);
                data.setPersistent(false);
                write(data, lines);
                Hologram hologram = manager.create(data);
                manager.addHologram(hologram);
                placements.add(name, hologram);
            },
            (name, lines) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    retext(hologram, lines);
                }
            },
            (name, location) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    relocate(hologram, location);
                }
            },
            name -> {
                Hologram hologram = placements.standing(name);
                if (hologram == null) {
                    return false;
                }
                if (!Bukkit.isStopping()) {
                    managers.get().removeHologram(hologram);
                }
                placements.forget(name, hologram);
                return true;
            });
    }

    // A hologram FancyHolograms no longer holds is no longer drawn by its visibility task, so its viewers keep it until
    // it is hidden from each of them.
    static void hide(Hologram hologram) {
        hide(hologram, Bukkit::getPlayer);
    }

    static void hide(Hologram hologram, Function<UUID, @Nullable Player> players) {
        for (UUID viewer : Set.copyOf(hologram.getViewers())) {
            Player player = players.apply(viewer);
            if (player != null) {
                hologram.forceHideHologram(player);
            }
        }
    }

    static void retext(Hologram hologram, List<String> lines) {
        if (hologram.getData() instanceof TextHologramData data) {
            write(data, lines);
        }
        hologram.queueUpdate();
    }

    static void relocate(Hologram hologram, Location location) {
        hologram.getData().setLocation(location);
        hologram.queueUpdate();
    }

    static void write(TextHologramData data, List<String> lines) {
        // TextHologramData keeps the list it is given and edits it in place.
        data.setText(new ArrayList<>(literalAmpersands(lines)));
        data.setTextUpdateInterval(refresh(lines));
    }

    // FancyHolograms reads & colour codes and &#RRGGBB hex colours in the raw line before MiniMessage, so a zero-width
    // non-joiner after every & keeps it from reading one. The line is otherwise passed unchanged, so FancyHolograms
    // still reads every tag it knows, MiniPlaceholders tags included. A line MiniMessage refuses, such as one holding a
    // section sign, fails here as it does on DecentHolograms and HologramLib.
    static List<String> literalAmpersands(List<String> lines) {
        MiniMessage miniMessage = MiniMessage.miniMessage();
        lines.forEach(miniMessage::deserialize);
        return lines.stream().map(line -> line.replace("&", "&" + PluginHolograms.ZERO_WIDTH_NON_JOINER)).toList();
    }

    static int refresh(List<String> lines) {
        for (String line : lines) {
            for (int i = line.indexOf('%'); i >= 0; i = line.indexOf('%', i + 1)) {
                if (!line.startsWith(PluginHolograms.ZERO_WIDTH_NON_JOINER, i + 1)) {
                    return PLACEHOLDER_REFRESH_MILLIS;
                }
            }
        }
        return NO_REFRESH;
    }
}
