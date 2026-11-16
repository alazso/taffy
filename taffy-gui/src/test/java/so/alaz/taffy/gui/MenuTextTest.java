package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class MenuTextTest {

    private static final String ENGLISH = """
        gui.home.title: "<gold>Home"
        gui.home.browse.name: "<green>Browse"
        gui.home.browse.lore: ["<gray>See what is on offer."]
        gui.home.history.lore: ["<gray>Everything you did,", "<gray>newest first."]
        gui.home.inbox.waiting: "<yellow><count> waiting"
        gui.home.inbox.ready: "{count} ready"
        gui.footer.close.name: "<red>Close"
        gui.common.loading.name: "<gray>Loading..."
        """;

    private static final String LOADING = "gui.common.loading.name";

    private static final MenuKind HOME = MenuKind.of("home", false, "browse", "history", "inbox", MenuKind.CLOSE);

    private static final ItemLayout PLAIN = new ItemLayout("browse", List.of(10), Material.EMERALD, "browse", null,
        true, null, null);

    @TempDir
    Path folder;

    private MenuText text;

    @BeforeEach
    void load() throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/de.yml"), """
            gui.home.browse.name: "<green>Stoebern"
            gui.footer.close.name: "<red>Schliessen"
            """);
        text = new MenuText(Messages.load(folder,
            () -> new ByteArrayInputStream(ENGLISH.getBytes(StandardCharsets.UTF_8)), new CapturedLog().logger));
    }

    @Test
    void nameAndLoreComeFromTheMenusActionKeys() {
        List<String> bases = HOME.textBases("browse");

        assertThat(plain(text.name(Locale.ENGLISH, PLAIN, bases))).isEqualTo("Browse");
        assertThat(text.lore(Locale.ENGLISH, PLAIN, bases)).extracting(MenuTextTest::plain)
            .containsExactly("See what is on offer.");
        assertThat(plain(text.name(Locale.GERMAN, PLAIN, bases))).isEqualTo("Stoebern");
    }

    @Test
    void aFooterActionFallsBackToTheFooterKeys() {
        ItemLayout close = new ItemLayout("close", List.of(53), Material.BARRIER, "close", null, true, null, null);
        List<String> bases = HOME.textBases("close");

        assertThat(bases).containsExactly("gui.home.close", "gui.footer.close");
        assertThat(plain(text.name(Locale.ENGLISH, close, bases))).isEqualTo("Close");
        assertThat(plain(text.name(Locale.GERMAN, close, bases))).isEqualTo("Schliessen");
        assertThat(text.lore(Locale.ENGLISH, close, bases)).isEmpty();
    }

    @Test
    void aLayoutNameAndLoreReplaceTheLangTextInEveryLanguage() {
        ItemLayout named = new ItemLayout("browse", List.of(10), Material.EMERALD, "browse", null, true,
            "<gold>Shop <count>", List.of("first <count>", "<red>second"));
        List<String> bases = HOME.textBases("browse");

        for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            assertThat(plain(text.name(locale, named, bases, Placeholder.unparsed("count", "2"))))
                .isEqualTo("Shop 2");
            List<Component> lore = text.lore(locale, named, bases, Placeholder.unparsed("count", "2"));
            assertThat(lore).extracting(MenuTextTest::plain).containsExactly("first 2", "second");
            assertThat(lore.get(1).color()).isEqualTo(NamedTextColor.RED);
        }
    }

    @Test
    void namesAreNotItalicAndLoreIsGrayAndNotItalicUnlessColoured() {
        ItemLayout named = new ItemLayout("x", List.of(0), Material.STONE, null, null, true, "Name",
            List.of("plain", "<red>red", "<italic>leaning"));

        Component name = text.name(Locale.ENGLISH, named, List.of());
        List<Component> lore = text.lore(Locale.ENGLISH, named, List.of());

        assertThat(name.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(lore.getFirst().color()).isEqualTo(NamedTextColor.GRAY);
        assertThat(lore.getFirst().decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(lore.get(1).color()).isEqualTo(NamedTextColor.RED);
        Component leaning = lore.get(2);
        assertThat(leaning.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE
            || leaning.children().stream().anyMatch(child -> child.decoration(TextDecoration.ITALIC)
            == TextDecoration.State.TRUE)).isTrue();
    }

    @Test
    void anItemWithoutKeyOrNameHasNoNameAndNoLore() {
        ItemLayout bare = new ItemLayout("x", List.of(0), Material.STONE, null, null, true, null, null);

        assertThat(text.name(Locale.ENGLISH, bare, List.of())).isNull();
        assertThat(text.lore(Locale.ENGLISH, bare, List.of())).isEmpty();
        assertThat(text.lore(Locale.ENGLISH, bare, List.of("gui.home.nothing"))).isEmpty();
    }

    @Test
    void aNameKeyNoBaseHoldsShowsTheMarkerOfTheLastBase() {
        ItemLayout bare = new ItemLayout("x", List.of(0), Material.STONE, null, null, true, null, null);

        Component name = text.name(Locale.ENGLISH, bare, List.of("gui.home.nothing", "gui.footer.nothing"));

        assertThat(plain(name)).isEqualTo("missing:gui.footer.nothing.name");
        assertThat(name.color()).isEqualTo(NamedTextColor.RED);
        assertThat(name.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    void sectionSignCodesInLayoutTextAreConvertedAsInLangText() {
        MenuLayout layout = new MenuLayout("legacy", 1, null, null, Map.of(), "\u00a76Shop <count>");
        ItemLayout item = new ItemLayout("x", List.of(0), Material.STONE, null, null, true, "\u00a7cName",
            List.of("\u00a7aLine"));
        TagResolver count = Placeholder.unparsed("count", "3");

        assertThat(text.title(Locale.ENGLISH, layout, "gui.legacy.title", count))
            .isEqualTo(MiniMessage.miniMessage().deserialize("<gold>Shop <count>", count));
        assertThat(text.name(Locale.ENGLISH, item, List.of())).isEqualTo(MiniMessage.miniMessage()
            .deserialize("<red>Name").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        assertThat(text.lore(Locale.ENGLISH, item, List.of())).containsExactly(MiniMessage.miniMessage()
            .deserialize("<green>Line").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            .colorIfAbsent(NamedTextColor.GRAY));
    }

    @Test
    void placeholdersFillLangTextAndAListKeyGivesOneComponentPerLine() {
        assertThat(plain(text.line(Locale.ENGLISH, "gui.home.inbox.waiting",
            Placeholder.unparsed("count", "7")))).isEqualTo("7 waiting");
        assertThat(text.lines(Locale.ENGLISH, "gui.home.history.lore")).extracting(MenuTextTest::plain)
            .containsExactly("Everything you did,", "newest first.");
        assertThat(plain(text.label(Locale.ENGLISH, LOADING))).isEqualTo("Loading...");
        assertThat(text.label(Locale.ENGLISH, LOADING).decoration(TextDecoration.ITALIC))
            .isEqualTo(TextDecoration.State.FALSE);
        assertThat(text.has(Locale.GERMAN, "gui.home.browse.name")).isTrue();
        assertThat(text.has(Locale.ENGLISH, "gui.home.nothing.name")).isFalse();
    }

    @Test
    void aTitleInTheLayoutWinsOverTheLangTitle() {
        MenuLayout plain = new MenuLayout("home", 6, null, null, Map.of(), null);
        MenuLayout titled = new MenuLayout("home", 6, null, null, Map.of(), "<red>Shop <count>");

        assertThat(plain(text.title(Locale.ENGLISH, plain, "gui.home.title"))).isEqualTo("Home");
        assertThat(plain(text.title(Locale.ENGLISH, titled, "gui.home.title", Placeholder.unparsed("count", "1"))))
            .isEqualTo("Shop 1");
    }

    @Test
    void bracesFillLangTextButNotTextWrittenInTheLayout() {
        ItemLayout named = new ItemLayout("inbox", List.of(0), Material.CHEST, "inbox", null, true, "{count} in",
            List.of("{count} out"));

        assertThat(plain(text.line(Locale.ENGLISH, "gui.home.inbox.ready", Placeholder.unparsed("count", "3"))))
            .isEqualTo("3 ready");
        assertThat(plain(text.name(Locale.ENGLISH, named, List.of(), Placeholder.unparsed("count", "3"))))
            .isEqualTo("{count} in");
        assertThat(text.lore(Locale.ENGLISH, named, List.of(), Placeholder.unparsed("count", "3")))
            .extracting(MenuTextTest::plain).containsExactly("{count} out");
    }

    @Test
    void messagesGivesTheMessagesTheTextReads() {
        assertThat(plain(text.messages().render(Locale.ENGLISH, "gui.home.title"))).isEqualTo("Home");
    }

    private static @Nullable String plain(@Nullable Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
