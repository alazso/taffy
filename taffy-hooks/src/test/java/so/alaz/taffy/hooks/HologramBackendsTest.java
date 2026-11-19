package so.alaz.taffy.hooks;

import com.maximde.hologramlib.hologram.HologramManager;
import com.maximde.hologramlib.hologram.TextHologram;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHolograms;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.thenextlvl.hologram.Hologram;
import net.thenextlvl.hologram.line.TextHologramLine;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class HologramBackendsTest {

    private final World world = mock(World.class);
    private final Location here = new Location(world, 1, 64, 1);

    @Test
    void everyBackendLinksAgainstTheApiItCalls() {
        assertThat(DecentHologramsBackend.create()).isNotNull();
        assertThat(FancyHologramsBackend.create()).isNotNull();
        assertThat(NextLvlHologramsBackend.create()).isNotNull();
        assertThat(HologramLibBackend.create(mock(org.bukkit.plugin.Plugin.class))).isNotNull();
    }

    @Test
    void decentHologramsGetsLegacyColourCodesWithHexColours() {
        assertThat(DecentHologramsBackend.legacy(List.of("<gold>Daily crate", "<#ff8800>Hex", "%slate_keys% left",
            "<red><bold>Open", "plain", ""))).containsExactly("&6Daily crate", "&#ff8800Hex", "%slate_keys% left",
            "&c&lOpen", "plain", "");
    }

    @Test
    void decentHologramsAnchorsANewHologramAtItsBottomLine() {
        try (MockedStatic<DecentHologramsAPI> running = mockStatic(DecentHologramsAPI.class);
             MockedStatic<DHAPI> dhapi = mockStatic(DHAPI.class)) {
            running.when(DecentHologramsAPI::get).thenReturn(mock(DecentHolograms.class));
            eu.decentsoftware.holograms.api.holograms.Hologram hologram =
                mock(eu.decentsoftware.holograms.api.holograms.Hologram.class);
            dhapi.when(() -> DHAPI.createHologram(eq("slate_a"), same(here), eq(false), anyList()))
                .thenReturn(hologram);
            dhapi.when(() -> DHAPI.getHologram("slate_a")).thenReturn(hologram);
            HologramBackend backend = DecentHologramsBackend.create();

            backend.create("slate_a", here, List.of("<gold>Crate"));

            InOrder order = inOrder(DHAPI.class, hologram);
            order.verify(dhapi, () -> DHAPI.createHologram("slate_a", here, false, List.of("&6Crate")));
            order.verify(hologram).setDownOrigin(true);
            order.verify(hologram).realignLines();
            assertThat(backend.has("slate_a")).isTrue();
        }
    }

    @Test
    void decentHologramsShowsALiteralAmpersandOrHashAsWritten() {
        Pattern solid = Pattern.compile("[<{]#([A-Fa-f0-9]{6})[}>]|[&\u00a7]?#([A-Fa-f0-9]{6})");
        Pattern code = Pattern.compile("&[0-9a-fk-orxA-FK-ORX]");
        String zwnj = PluginHolograms.ZERO_WIDTH_NON_JOINER;
        assertThat(zwnj.codePoints().toArray()).containsExactly(0x200C);

        List<String> lines = DecentHologramsBackend.legacy(List.of("Q&A board", "Ticket #abcdef", "R&D",
            "\\<#abcdef>x", "{#abcdef}", "&<bold>a</bold>", "#<hover:show_text:'x'>abcdef</hover>"));

        assertThat(lines).containsExactly("Q&" + zwnj + "A board", "Ticket #" + zwnj + "abcdef", "R&" + zwnj + "D",
            "<#" + zwnj + "abcdef>x", "{#" + zwnj + "abcdef}", "&" + zwnj + "&la", "#" + zwnj + "abcdef");
        for (String line : lines) {
            assertThat(solid.matcher(line).find()).as(line).isFalse();
            assertThat(code.matcher(line.replace("&l", "")).find()).as(line).isFalse();
        }
        assertThat(DecentHologramsBackend.legacy(List.of("<#ff8800>Hex"))).containsExactly("&#ff8800Hex");
    }

    @Test
    void fancyHologramsRefreshesPlaceholdersOnlyForLinesThatHaveOne() {
        assertThat(FancyHologramsBackend.refresh(List.of("<gold>Crate", "%player_name%")))
            .isEqualTo(FancyHologramsBackend.PLACEHOLDER_REFRESH_MILLIS);
        assertThat(FancyHologramsBackend.refresh(List.of("<gold>Crate"))).isEqualTo(FancyHologramsBackend.NO_REFRESH);
        assertThat(FancyHologramsBackend.refresh(List.of())).isEqualTo(FancyHologramsBackend.NO_REFRESH);
    }

    @Test
    void fancyHologramsShowsALiteralAmpersandAsWritten() {
        TextHologramData data = mock(TextHologramData.class);
        String zwnj = PluginHolograms.ZERO_WIDTH_NON_JOINER;

        FancyHologramsBackend.write(data, List.of("Fish&chips", "<gold>R&D", "&6Old &#abcdef", "<gold>Crate",
            "%slate_keys% & more", ""));

        verify(data).setText(List.of("Fish&" + zwnj + "chips", "<gold>R&" + zwnj + "D",
            "&" + zwnj + "6Old &" + zwnj + "#abcdef", "<gold>Crate", "%slate_keys% &" + zwnj + " more", ""));
        verify(data).setTextUpdateInterval(FancyHologramsBackend.PLACEHOLDER_REFRESH_MILLIS);
    }

    @Test
    void fancyHologramsGetsTagsAndPlaceholdersAsWritten() {
        TextHologramData data = mock(TextHologramData.class);
        List<String> lines = List.of("<gradient:gold:red>%player_name%</gradient>", "<player_name>",
            "<sound:entity.experience_orb.pickup>Crate", "<rainbow>Keys</rainbow>");

        FancyHologramsBackend.write(data, lines);

        verify(data).setText(lines);
    }

    @Test
    void fancyHologramsShowsALiteralAmpersandInsideAGradientAsWritten() {
        TextHologramData data = mock(TextHologramData.class);
        String zwnj = PluginHolograms.ZERO_WIDTH_NON_JOINER;

        FancyHologramsBackend.write(data, List.of("<gradient:gold:red>Fish&chips</gradient>"));

        verify(data).setText(List.of("<gradient:gold:red>Fish&" + zwnj + "chips</gradient>"));
    }

    @Test
    void fancyHologramsRefusesALineMiniMessageRefuses() {
        TextHologramData data = mock(TextHologramData.class);

        assertThatThrownBy(() -> FancyHologramsBackend.write(data, List.of("<gold>Crate", "§cCrate")))
            .isInstanceOf(RuntimeException.class);

        verify(data, never()).setText(anyList());
    }

    @Test
    void literalTextShowsAsWrittenWhateverItHolds() {
        String zwnj = PluginHolograms.ZERO_WIDTH_NON_JOINER;
        TagResolver resolver = TagResolver.resolver(TagResolver.standard(), Placeholder.unparsed("player_name", "x"));

        for (String input : List.of("<red>x</red>", "<sound:entity.ender_dragon.death>", "<particle:FLAME>",
            "&cR&D \u00a7c", "%player_name%", "{player}", "\\<b>\\", "<player_name>", "plain", "")) {
            String literal = HologramProvider.literal(input);
            String shown = PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(literal, resolver));

            assertThat(shown.replace(zwnj, "")).as(input).isEqualTo(input);
            for (int i = 0; i < literal.length(); i++) {
                if ("<&%{\u00a7".indexOf(literal.charAt(i)) >= 0) {
                    assertThat(literal.startsWith(zwnj, i + 1)).as(literal).isTrue();
                }
            }
        }
        assertThat(HologramProvider.literal("\\")).isEqualTo("\\\\");
        assertThatThrownBy(() -> HologramProvider.literal(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void literalTextReachesNoHologramPluginAsACodeOrAPlaceholder() {
        String line = DecentHologramsBackend.legacy(List.of(HologramProvider.literal("%p_x% {player} <#abcdef>")))
            .getFirst();

        assertThat(line).doesNotContain("%p", "{p", "<#");
        assertThat(FancyHologramsBackend.refresh(List.of(HologramProvider.literal("100%"))))
            .isEqualTo(FancyHologramsBackend.NO_REFRESH);
        assertThat(FancyHologramsBackend.refresh(List.of(HologramProvider.literal("100%") + " %player_name%")))
            .isEqualTo(FancyHologramsBackend.PLACEHOLDER_REFRESH_MILLIS);
    }

    @Test
    void fancyHologramsGetsAnEditableCopyOfTheLinesAndAnUpdate() {
        de.oliver.fancyholograms.api.hologram.Hologram hologram =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        TextHologramData data = mock(TextHologramData.class);
        when(hologram.getData()).thenReturn(data);
        List<String> lines = List.of("<gold>Crate", "%slate_keys%");

        FancyHologramsBackend.retext(hologram, lines);

        InOrder order = inOrder(data, hologram);
        order.verify(data).setText(eq(new ArrayList<>(lines)));
        order.verify(data).setTextUpdateInterval(FancyHologramsBackend.PLACEHOLDER_REFRESH_MILLIS);
        order.verify(hologram).queueUpdate();
    }

    @Test
    void fancyHologramsLeavesTheTextOfAHologramThatIsNotTextAndMovesByItsData() {
        de.oliver.fancyholograms.api.hologram.Hologram hologram =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        HologramData data = mock(HologramData.class);
        when(hologram.getData()).thenReturn(data);

        FancyHologramsBackend.retext(hologram, List.of("x"));
        FancyHologramsBackend.relocate(hologram, here);

        verify(data).setLocation(here);
        verify(hologram, org.mockito.Mockito.times(2)).queueUpdate();
    }

    /** A Holograms hologram whose lines are the strings in {@code shown}. */
    private static Hologram nextLvl(List<String> shown, boolean textLines) {
        Hologram hologram = mock(Hologram.class);
        when(hologram.getLineCount()).thenAnswer(call -> shown.size());
        when(hologram.getLine(anyInt(), eq(TextHologramLine.class))).thenAnswer(call -> {
            int index = call.getArgument(0);
            return textLines ? Optional.of(line(shown, index)) : Optional.empty();
        });
        when(hologram.removeLine(anyInt())).thenAnswer(call -> {
            shown.remove((int) call.getArgument(0));
            return true;
        });
        when(hologram.addTextLine()).thenAnswer(call -> {
            shown.add("");
            return line(shown, shown.size() - 1);
        });
        when(hologram.setTextLine(anyInt())).thenAnswer(call -> {
            int index = call.getArgument(0);
            shown.set(index, "");
            return line(shown, index);
        });
        return hologram;
    }

    private static TextHologramLine line(List<String> shown, int index) {
        return mock(TextHologramLine.class, call -> {
            if (call.getMethod().getName().equals("setUnparsedText")) {
                shown.set(index, call.getArgument(0));
                return true;
            }
            throw new UnsupportedOperationException(call.getMethod().getName());
        });
    }

    @Test
    void hologramsRewritesTheLinesThatStayAndAddsOrRemovesTheRest() {
        List<String> shown = new ArrayList<>(List.of("a", "b"));
        Hologram hologram = nextLvl(shown, true);

        NextLvlHologramsBackend.setLines(hologram, List.of("one", "two", "three"));
        assertThat(shown).containsExactly("one", "two", "three");
        NextLvlHologramsBackend.setLines(hologram, List.of("solo"));
        assertThat(shown).containsExactly("solo");
        NextLvlHologramsBackend.setLines(hologram, List.of());
        assertThat(shown).isEmpty();
        verify(hologram, never()).setTextLine(anyInt());
    }

    @Test
    void hologramsReplacesALineThatIsNotTextInPlace() {
        List<String> shown = new ArrayList<>(List.of("item", "block"));
        Hologram hologram = nextLvl(shown, false);

        NextLvlHologramsBackend.setLines(hologram, List.of("one"));

        assertThat(shown).containsExactly("one");
        verify(hologram).setTextLine(0);
        verify(hologram, never()).clearLines();
    }

    @Test
    void hologramsAddsLinesFromAnIndex() {
        List<String> shown = new ArrayList<>();
        Hologram hologram = nextLvl(shown, true);

        NextLvlHologramsBackend.addLines(hologram, List.of("skip", "one", "two"), 1);

        assertThat(shown).containsExactly("one", "two");
    }

    @Test
    void hologramLibJoinsTheLinesIntoOneText() {
        assertThat(HologramLibBackend.text(List.of("<gold>Crate", "line two"))).isEqualTo("<gold>Crate\nline two");
        assertThat(HologramLibBackend.text(List.of())).isEmpty();
    }

    @Test
    void hologramLibOptsEveryHologramIntoPlaceholderApi() {
        TextHologram hologram = mock(TextHologram.class);

        HologramLibBackend.prepare(hologram, "<gold>%player_name%");

        verify(hologram).setPlaceholderApiEnabled(true);
        verify(hologram).setMiniMessageText("<gold>%player_name%");
    }

    @Test
    void hologramLibSendsNewTextOnlyOnceTheHologramHasSpawned() {
        TextHologram waiting = mock(TextHologram.class);
        TextHologram spawned = mock(TextHologram.class);
        when(spawned.getLocation()).thenReturn(here);

        HologramLibBackend.retext(waiting, "new");
        HologramLibBackend.retext(spawned, "new");

        verify(waiting).setMiniMessageText("new");
        verify(waiting, never()).update();
        verify(spawned).setMiniMessageText("new");
        verify(spawned).update();
    }

    @Test
    void hologramLibTeleportsASpawnedHologram() {
        HologramManager manager = mock(HologramManager.class);
        TextHologram spawned = mock(TextHologram.class);
        when(spawned.getLocation()).thenReturn(here);
        Location there = new Location(world, 5, 70, 5);

        HologramLibBackend.relocate(() -> manager, "slate_a", spawned, there, task -> {
            throw new AssertionError("nothing waits for a spawned hologram");
        });

        verify(spawned).teleport(there);
        verifyNoMoreInteractions(manager);
    }

    @Test
    void hologramLibMovesAHologramThatHasNotSpawnedOnceItHas() {
        HologramManager manager = mock(HologramManager.class);
        TextHologram waiting = mock(TextHologram.class);
        Location there = new Location(world, 5, 70, 5);
        List<Runnable> later = new ArrayList<>();
        when(manager.getHologram("slate_a")).thenReturn(Optional.of(waiting));

        HologramLibBackend.relocate(() -> manager, "slate_a", waiting, there, later::add);
        verify(waiting, never()).teleport(there);
        when(waiting.getLocation()).thenReturn(here);
        later.getFirst().run();

        verify(waiting).teleport(there);
        verify(manager, never()).remove("slate_a");
    }

    @Test
    void hologramLibMovesNothingLaterWhenTheHologramWasRemovedOrReplaced() {
        HologramManager manager = mock(HologramManager.class);
        TextHologram waiting = mock(TextHologram.class);
        TextHologram replacement = mock(TextHologram.class);
        when(replacement.getLocation()).thenReturn(here);
        List<Runnable> later = new ArrayList<>();

        HologramLibBackend.relocate(() -> manager, "slate_a", waiting, here, later::add);
        HologramLibBackend.relocate(() -> manager, "slate_a", waiting, here, later::add);
        when(manager.getHologram("slate_a")).thenReturn(Optional.empty());
        later.get(0).run();
        when(manager.getHologram("slate_a")).thenReturn(Optional.of(replacement));
        later.get(1).run();

        verify(waiting, never()).teleport(any());
        verify(replacement, never()).teleport(any());
    }

    @Test
    @SuppressWarnings("deprecation")
    void hologramLibKillsAHologramThatHasNotSpawnedAfterItsSpawn() {
        HologramManager manager = mock(HologramManager.class);
        TextHologram waiting = mock(TextHologram.class);
        List<Runnable> later = new ArrayList<>();
        when(manager.remove("slate_a")).thenReturn(true);

        assertThat(HologramLibBackend.remove(manager, "slate_a", waiting, later::add)).isTrue();
        verify(waiting, never()).kill();
        assertThat(later).hasSize(1);
        later.getFirst().run();

        verify(waiting).kill();
        verify(manager, never()).remove(any(com.maximde.hologramlib.hologram.Hologram.class));
    }

    @Test
    @SuppressWarnings("deprecation")
    void hologramLibRemovesASpawnedHologramAtOnce() {
        HologramManager manager = mock(HologramManager.class);
        TextHologram spawned = mock(TextHologram.class);
        when(spawned.getLocation()).thenReturn(here);
        when(manager.remove("slate_a")).thenReturn(true);

        assertThat(HologramLibBackend.remove(manager, "slate_a", spawned, task -> {
            throw new AssertionError("nothing waits for a spawned hologram");
        })).isTrue();
        assertThat(HologramLibBackend.remove(manager, "slate_b", spawned, task -> {
            throw new AssertionError("nothing waits for a spawned hologram");
        })).isFalse();
        verify(spawned, never()).kill();
    }

    @Test
    void hologramLibRefusesTextMiniMessageRejectsBeforeBuildingAHologram() {
        assertThat(HologramLibBackend.parsed("<gold>Crate")).isEqualTo("<gold>Crate");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HologramLibBackend.parsed("\u00a7cCrate"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void placementsReachOnlyTheObjectPlacedWhileThePluginStillHoldsIt() {
        Map<String, Object> held = new HashMap<>();
        List<Object> displaced = new ArrayList<>();
        Placements<Object> placements = new Placements<>(held::get, displaced::add);
        Object mine = new Object();
        Object replacement = new Object();

        assertThat(placements.standing("a")).isNull();
        held.put("a", mine);
        assertThat(placements.standing("a")).isNull();
        placements.add("a", mine);
        assertThat(placements.standing("a")).isSameAs(mine);
        held.put("a", replacement);
        assertThat(placements.standing("a")).isNull();
        assertThat(displaced).containsExactly(mine);
        held.put("a", mine);
        assertThat(placements.standing("a")).isNull();
        assertThat(displaced).containsExactly(mine);

        placements.add("b", mine);
        held.put("b", mine);
        placements.forget("b", replacement);
        assertThat(placements.standing("b")).isSameAs(mine);
        placements.forget("b", mine);
        assertThat(placements.standing("b")).isNull();

        placements.add("c", mine);
        assertThat(placements.standing("c")).isNull();
        assertThat(displaced).containsExactly(mine);
    }

    @Test
    void fancyHologramsHidesADisplacedHologramFromEachViewerOnline() {
        de.oliver.fancyholograms.api.hologram.Hologram hologram =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        java.util.UUID online = java.util.UUID.randomUUID();
        java.util.UUID offline = java.util.UUID.randomUUID();
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(hologram.getViewers()).thenReturn(java.util.Set.of(online, offline));

        FancyHologramsBackend.hide(hologram, viewer -> viewer.equals(online) ? player : null);

        verify(hologram).forceHideHologram(player);
        verify(hologram, org.mockito.Mockito.times(1)).forceHideHologram(any());
    }
}
