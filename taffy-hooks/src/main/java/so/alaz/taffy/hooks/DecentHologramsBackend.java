package so.alaz.taffy.hooks;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.DisableCause;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;

import java.util.List;

// DHAPI and Hologram read DecentHolograms' running instance when they initialize, so neither initializes until a call
// runs after detection found DecentHolograms enabled. Linking their methods does not initialize them. A hologram is
// removed with destroy, which deletes no file, because DHAPI.removeHologram deletes the file of a hologram that
// replaced it under the same name. DecentHolograms anchors a hologram at its top line by default, so each hologram is
// anchored at its bottom line, where the other hologram plugins anchor theirs.
final class DecentHologramsBackend {

    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);

    // DecentHolograms reads & colour codes and &#RRGGBB, <#RRGGBB>, {#RRGGBB} and #RRGGBB hex colours, not MiniMessage.
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&')
        .hexCharacter('#')
        .hexColors()
        .build();

    // A zero-width space after every literal & and # keeps DecentHolograms from reading a colour code or a hex colour
    // in the text, including one that continues in the next component.
    private static final TextReplacementConfig LITERAL_CODES = TextReplacementConfig.builder()
        .match("[&#]")
        .replacement(match -> match.content(match.content() + ZERO_WIDTH_SPACE))
        .build();

    private DecentHologramsBackend() {
    }

    static HologramBackend create() {
        ApiLink.staticMethod(DHAPI.class, "getHologram", Hologram.class, String.class);
        ApiLink.staticMethod(DHAPI.class, "createHologram", Hologram.class, String.class, Location.class,
            boolean.class, List.class);
        ApiLink.staticMethod(DHAPI.class, "setHologramLines", void.class, Hologram.class, List.class);
        ApiLink.staticMethod(DHAPI.class, "moveHologram", void.class, Hologram.class, Location.class);
        ApiLink.instanceMethod(Hologram.class, "destroy", void.class);
        ApiLink.instanceMethod(Hologram.class, "disable", void.class, DisableCause.class);
        ApiLink.instanceMethod(Hologram.class, "setDownOrigin", void.class, boolean.class);
        ApiLink.instanceMethod(Hologram.class, "realignLines", void.class);
        Placements<Hologram> placements = new Placements<>(name -> DHAPI.getHologram(name),
            displaced -> displaced.disable(DisableCause.API));
        return HologramBackend.of(
            name -> DHAPI.getHologram(name) != null,
            name -> placements.standing(name) != null,
            (name, location, lines) -> {
                Hologram hologram = DHAPI.createHologram(name, location, false, legacy(lines));
                hologram.setDownOrigin(true);
                hologram.realignLines();
                placements.add(name, hologram);
            },
            (name, lines) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    DHAPI.setHologramLines(hologram, legacy(lines));
                }
            },
            (name, location) -> {
                Hologram hologram = placements.standing(name);
                if (hologram != null) {
                    DHAPI.moveHologram(hologram, location);
                }
            },
            name -> {
                Hologram hologram = placements.standing(name);
                if (hologram == null) {
                    return false;
                }
                hologram.destroy();
                placements.forget(name, hologram);
                return true;
            });
    }

    static List<String> legacy(List<String> lines) {
        MiniMessage miniMessage = MiniMessage.miniMessage();
        return lines.stream()
            .map(line -> {
                Component text = miniMessage.deserialize(line).replaceText(LITERAL_CODES);
                return LEGACY.serialize(text);
            })
            .toList();
    }
}
