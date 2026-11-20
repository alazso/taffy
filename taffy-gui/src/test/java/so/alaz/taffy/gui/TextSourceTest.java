package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class TextSourceTest {

    // Renders a key as "<language>:<key>", so a test reads which key and locale the menus asked for.
    private static final TextSource KEYS = (locale, key, placeholders) ->
        Component.text(locale.getLanguage() + ":" + key);

    @TempDir
    Path folder;

    private static String plain(Component text) {
        return MenuHarness.plain(text);
    }

    @Test
    void aFunctionHoldsEveryKeyAndGivesItsTextAsOneLine() {
        assertThat(KEYS.has(Locale.ENGLISH, "gui.any.key")).isTrue();
        assertThat(KEYS.lines(Locale.GERMAN, "gui.any.lore")).extracting(TextSourceTest::plain)
            .containsExactly("de:gui.any.lore");
    }

    @Test
    void theSourceOfTaffysMessagesRendersTellsAndSplitsAsTheMessagesDo() {
        Messages messages = Messages.load(folder, () -> new ByteArrayInputStream("""
            gui.home.title: "<gold>Home of <who>"
            gui.home.browse.lore: ["one", "two"]
            """.getBytes(StandardCharsets.UTF_8)), new CapturedLog().logger);

        TextSource source = TextSource.of(messages);

        assertThat(plain(source.render(Locale.ENGLISH, "gui.home.title", Placeholder.unparsed("who", "Alex"))))
            .isEqualTo("Home of Alex");
        assertThat(source.has(Locale.ENGLISH, "gui.home.title")).isTrue();
        assertThat(source.has(Locale.ENGLISH, "gui.home.absent")).isFalse();
        assertThat(source.lines(Locale.ENGLISH, "gui.home.browse.lore")).extracting(TextSourceTest::plain)
            .containsExactly("one", "two");
        assertThat(plain(source.render(Locale.ENGLISH, "gui.home.absent"))).isEqualTo("missing:gui.home.absent");
        assertThatNullPointerException().isThrownBy(() -> TextSource.of(null)).withMessage("messages");
    }

    @Test
    void menusOverAFunctionReadTheirTitleNamesLoreAndErrorFromIt() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(), null, null, KEYS);
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        menu.render = frame -> frame.footer(null, null, null);
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        viewer.locale = Locale.GERMAN;

        harness.open(viewer, MenuHarness.MAIN.id());
        harness.menus.load(menu.sessions.getLast(), CompletableFuture.failedFuture(new IllegalStateException("x")),
            (session, value) -> {
            });

        assertThat(harness.menus.text().source()).isSameAs(KEYS);
        assertThat(plain(harness.inventories.titles.getFirst())).isEqualTo("de:gui.main.title");
        assertThat(viewer.name(53)).isEqualTo("de:gui.main.close.name");
        assertThat(viewer.lore(53)).containsExactly("de:gui.main.close.lore");
        assertThat(viewer.said()).containsExactly("de:gui.error");
    }

    @Test
    void aSourceThatTellsWhichKeysItHoldsLeavesOptionalTextOut() {
        TextSource footerOnly = new TextSource() {
            @Override
            public Component render(Locale locale, String key, TagResolver... placeholders) {
                return KEYS.render(locale, key, placeholders);
            }

            @Override
            public boolean has(Locale locale, String key) {
                return key.equals("gui.footer.close.name");
            }
        };
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(), null, null, footerOnly);
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        menu.render = frame -> frame.footer(null, null, null);
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.MAIN.id());

        assertThat(viewer.name(53)).isEqualTo("en:gui.footer.close.name");
        assertThat(viewer.lore(53)).isEmpty();
    }

    @Test
    void promptsOverAFunctionAskThroughIt() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(), null, null, KEYS);
        harness.menus.register(new TestMenu(MenuHarness.MAIN));
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        Prompts prompts = new Prompts(harness.menus, PromptMode.CHAT, BedrockPlayers.NONE, (task, delay) -> {
        }, null, action -> ClickEvent.runCommand("/text-source-test"), player -> true, harness.clock);

        prompts.ask(viewer.player, new PromptRequest(Component.text("Price?"), "10",
            new Route(MenuHarness.MAIN.id(), 0, null), text -> new Answer.Retry(Component.text("no"))));

        assertThat(viewer.said()).containsExactly("en:gui.prompt.question",
            "en:gui.prompt.hint en:gui.prompt.cancel-button");
    }
}
