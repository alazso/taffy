package so.alaz.taffy.platform;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class MessagesDeliveryTest {

    private static final String OTHER_ENTRY =
        ", which holds only chat, actionbar, title, subtitle, times and sound, so ";
    private static final String NOT_TICKS =
        ", which is not three whole numbers of ticks (fade in, stay and fade out, such as 10 70 20), so the title of ";

    @TempDir
    Path folder;

    private final CapturedLog log = new CapturedLog();

    // Stands in for PlaceholderAPI: resolves %rank%.
    private final BiFunction<OfflinePlayer, String, String> pass = (player, input) -> input.replace("%rank%", "VIP");

    @Test
    void aDeliverySectionIsDeliveredToAPlayerAsEachOfItsEntriesSays() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[P] "
            pay.sent:
              delivery:
                chat: "<prefix>Sent <amount> %rank%"
                actionbar: "<green>Sent {amount}"
                title: "<gold>Paid"
                subtitle: "to <target>"
                times: "5 40 15"
                sound: "ENTITY.experience_orb.pickup 0.5 1.4"
            """), log.logger).withChatPrefix(() -> "> ");
        Player player = viewer(Locale.ENGLISH);

        messages.send(new Text(log.logger, pass, null), player, "pay.sent",
            Placeholder.unparsed("amount", "5"), Placeholder.unparsed("target", "Bo"));

        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("> [P] Sent 5 VIP");
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Sent 5");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(plain(shown.getValue().title())).isEqualTo("Paid");
        assertThat(plain(shown.getValue().subtitle())).isEqualTo("to Bo");
        assertThat(shown.getValue().times())
            .isEqualTo(Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(15)));
        ArgumentCaptor<Sound> played = ArgumentCaptor.forClass(Sound.class);
        verify(player).playSound(played.capture());
        assertThat(played.getValue())
            .isEqualTo(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 0.5f, 1.4f));
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void onlySendReadsADeliverySection() {
        Messages messages = Messages.load(folder, supplier("""
            pay.sent:
              delivery:
                actionbar: "Sent"
                times: "5 40 15"
            """), log.logger);
        Player player = viewer(Locale.ENGLISH);
        Text text = new Text(log.logger, null, null);

        assertThat(messages.has(null, "pay.sent")).isFalse();
        assertThat(messages.has(null, "pay.sent.delivery")).isFalse();
        assertThat(messages.has(null, "pay.sent.delivery.actionbar")).isTrue();
        assertThat(messages.template(null, "pay.sent.delivery.times")).isEqualTo("5 40 15");
        assertThat(plain(messages.render(Locale.ENGLISH, "pay.sent"))).isEqualTo("missing:pay.sent");
        assertThat(plain(messages.render(text, player, "pay.sent"))).isEqualTo("missing:pay.sent");
        assertThat(messages.lines(null, "pay.sent")).extracting(MessagesDeliveryTest::plain)
            .containsExactly("missing:pay.sent");
        messages.sendActionBar(text, player, "pay.sent");
        messages.showTitle(text, player, "pay.sent");

        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("missing:pay.sent");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(plain(shown.getValue().title())).isEqualTo("missing:pay.sent.title");
    }

    @Test
    void anEntryThatIsAbsentOrEmptyIsLeftOutAndASenderThatIsNoPlayerGetsOneChatMessage() {
        Messages messages = Messages.load(folder, supplier("""
            bar:
              delivery:
                actionbar: "Bar <n>"
            both:
              delivery:
                title: "T"
                subtitle: "S"
            sub:
              delivery:
                subtitle: "S only"
            chatty:
              delivery:
                chat:
                  - "one"
                  - "two"
                actionbar: "Bar"
            quiet:
              delivery:
                chat: ""
                actionbar: "Hidden"
            ping:
              delivery:
                sound: "block.note_block.pling"
            empty:
              delivery:
                actionbar: ""
                title: ""
                sound: ""
            """), log.logger).withChatPrefix(() -> "> ");
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);
        CommandSender console = mock(CommandSender.class);

        for (String key : List.of("bar", "both", "sub", "chatty", "quiet", "ping", "empty")) {
            messages.send(text, player, key, Placeholder.unparsed("n", "1"));
            messages.send(text, console, key, Placeholder.unparsed("n", "1"));
        }

        ArgumentCaptor<Component> toConsole = ArgumentCaptor.forClass(Component.class);
        verify(console, times(4)).sendMessage(toConsole.capture());
        assertThat(toConsole.getAllValues()).extracting(MessagesDeliveryTest::plain)
            .containsExactly("> Bar 1", "> T\nS", "> S only", "> one\ntwo");
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("> one\ntwo");
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player, times(3)).sendActionBar(bar.capture());
        assertThat(bar.getAllValues()).extracting(MessagesDeliveryTest::plain)
            .containsExactly("Bar 1", "Bar", "Hidden");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player, times(2)).showTitle(shown.capture());
        assertThat(shown.getAllValues()).extracting(title -> plain(title.title()) + "|" + plain(title.subtitle()))
            .containsExactly("T|S", "|S only");
        assertThat(shown.getAllValues()).extracting(Title::times).containsOnly(Title.DEFAULT_TIMES);
        verify(player).playSound(any(Sound.class));
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aSectionOrAKeyNamedDeliveryOfAReleasedFileIsReadAsBefore() {
        Messages messages = Messages.load(folder, supplier("""
            gui:
              shop:
                title: "Shop"
                sound: "ui.button.click"
            gui.confirm.delivery.name: "<aqua>Delivery"
            gui.confirm.delivery.lore: ["Where it goes"]
            gui.confirm.listing.delivery: "Kept until it sells"
            delivery:
              title: "Top"
              failed: "Failed"
            note.delivery: "A value"
            menu.delivery.title: "Menu"
            menu.delivery.items.close.name: "Close"
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        for (String key : List.of("gui.shop", "delivery", "note.delivery",
            "gui.confirm.listing.delivery", "gui.confirm.delivery.name", "gui.shop.title")) {
            messages.send(text, player, key);
        }

        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player, times(6)).sendMessage(chat.capture());
        assertThat(chat.getAllValues()).extracting(MessagesDeliveryTest::plain).containsExactly(
            "missing:gui.shop", "missing:delivery", "A value",
            "Kept until it sells", "Delivery", "Shop");
        verify(player, never()).sendActionBar(any(Component.class));
        verify(player, never()).showTitle(any(Title.class));
        verify(player, never()).playSound(any(Sound.class));
        assertThat(messages.lines(null, "gui.confirm.delivery.lore")).hasSize(1);
        assertThat(messages.template(null, "delivery.title")).isEqualTo("Top");
        assertThat(messages.template(null, "menu.delivery.title")).isEqualTo("Menu");
        assertThat(messages.has(null, "menu.delivery.items.close.name")).isTrue();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void theFirstFileThatHasTheMessageInEitherFormDecidesHowItIsSent() throws IOException {
        write("en.yml", """
            paid:
              delivery:
                actionbar: "Bar <n>"
            got: "Chat <n>"
            """);
        Messages messages = Messages.load(folder, supplier("""
            paid: "Paid <n>"
            got.delivery.actionbar: "Got <n>"
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        messages.send(text, player, "paid", Placeholder.unparsed("n", "1"));
        messages.send(text, player, "got", Placeholder.unparsed("n", "1"));

        assertThat(log.lines()).isEmpty();
        assertThat(plain(messages.render(Locale.ENGLISH, "paid", Placeholder.unparsed("n", "1")))).isEqualTo("Paid 1");
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Bar 1");
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("Chat 1");

        write("en.yml", "paid: \"Mine <n>\"\n");
        messages.reload();
        messages.send(text, player, "paid", Placeholder.unparsed("n", "2"));
        messages.send(text, player, "got", Placeholder.unparsed("n", "2"));

        assertThat(log.lines()).containsExactly(
            "WARNING lang/en.yml lacks got.delivery.actionbar; those messages use the bundled English text.");
        verify(player, times(2)).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Got 2");
        verify(player, times(2)).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("Mine 2");
    }

    @Test
    void aPlayerIsSentTheFormOfTheFileOfTheirLanguage() throws IOException {
        write("de.yml", "paid: \"Bezahlt\"\n");
        write("fr.yml", "other: \"Autre\"\n");
        Messages messages = Messages.load(folder, supplier("""
            paid:
              delivery:
                actionbar: "Paid"
            """), log.logger, "en", true);
        Text text = new Text(log.logger, null, null);
        Player german = viewer(Locale.GERMANY);
        Player french = viewer(Locale.FRANCE);

        messages.send(text, german, "paid");
        messages.send(text, french, "paid");

        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(german).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("Bezahlt");
        verify(german, never()).sendActionBar(any(Component.class));
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(french).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Paid");
        verify(french, never()).sendMessage(any(Component.class));
        assertThat(log.lines()).containsExactly(
            "WARNING lang/fr.yml lacks paid.delivery.actionbar; those messages use the English text.");
    }

    @Test
    void aBadTimesOrSoundIsReportedAtTheFirstSendAfterEachLoadAndFallsBack() {
        Messages messages = Messages.load(folder, supplier("""
            win:
              delivery:
                title: "Won"
                times: "5 forty 15"
                sound: "level!up 1 1"
            lose:
              delivery:
                title: "Lost"
                times: [5, 40]
                sound:
                  - "a.b"
                  - "c.d"
            far:
              delivery:
                title: "Far"
                times: "1 2 9223372036854775807"
            neg:
              delivery:
                title: "Neg"
                times: "1 -2 3"
            slow:
              delivery:
                title: "Slow"
                times: [10, 70, 20]
                sound: " "
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);
        List<String> keys = List.of("win", "lose", "far", "neg", "slow");

        assertThat(log.lines()).isEmpty();
        keys.forEach(key -> messages.send(text, player, key));
        keys.forEach(key -> messages.send(text, player, key));

        List<String> expected = List.of(
            "WARNING lang/en.yml has the times '5 forty 15' at win.delivery.times" + NOT_TICKS
                + "win has the default times.",
            "WARNING lang/en.yml has the sound 'level!up 1 1' at win.delivery.sound, whose key is not"
                + " a valid sound key, so win plays no sound.",
            "WARNING lang/en.yml has the times '5 40' at lose.delivery.times" + NOT_TICKS
                + "lose has the default times.",
            "WARNING lang/en.yml has a list of 2 entries at lose.delivery.sound, which takes one sound, so lose"
                + " plays no sound.",
            "WARNING lang/en.yml has the times '1 2 9223372036854775807' at far.delivery.times" + NOT_TICKS
                + "far has the default times.",
            "WARNING lang/en.yml has the times '1 -2 3' at neg.delivery.times" + NOT_TICKS
                + "neg has the default times.");
        assertThat(log.lines()).containsExactlyElementsOf(expected);
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player, times(10)).showTitle(shown.capture());
        assertThat(shown.getAllValues().subList(0, 5)).extracting(Title::times).containsExactly(
            Title.DEFAULT_TIMES, Title.DEFAULT_TIMES, Title.DEFAULT_TIMES, Title.DEFAULT_TIMES,
            Title.Times.times(Ticks.duration(10), Ticks.duration(70), Ticks.duration(20)));
        verify(player, never()).playSound(any(Sound.class));

        log.records.clear();
        messages.reload();
        assertThat(log.lines()).isEmpty();
        messages.send(text, player, "neg");
        assertThat(log.lines()).containsExactly(expected.get(5));
    }

    @Test
    void aBadEntryOfTheBundledTextIsReportedWhenTheBundledTextIsSent() throws IOException {
        write("en.yml", "other: \"x\"\n");
        Messages messages = Messages.load(folder, supplier("""
            win:
              delivery:
                title: "Won"
                times: "soon"
            other: "Other"
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);
        log.records.clear();

        messages.send(text, player, "win");
        messages.reload();
        messages.send(text, player, "win");

        assertThat(log.lines()).containsExactly(
            "WARNING The bundled en.yml has the times 'soon' at win.delivery.times" + NOT_TICKS
                + "win has the default times.",
            "WARNING lang/en.yml lacks win.delivery.times, win.delivery.title; those messages use the bundled"
                + " English text.");
    }

    @Test
    void aTitleGroupNamedDeliveryOfAReleasedFileIsWarnedAboutAndCompletedKeyByKey() throws IOException {
        String owned = """
            order.done: "Done"
            order.delivery.title: "Mine"
            mail:
              delivery:
                title: "My mail"
            sounds.delivery.sound: "not a sound key!!"
            """;
        write("en.yml", owned);
        Messages messages = Messages.load(folder, supplier("""
            prefix: "P"
            order.done: "Done"
            order.delivery.title: "Order"
            order.delivery.subtitle: "On its way"
            gui.delivery.title: "Delivery"
            mail.delivery.title: "Mail"
            mail.delivery.subtitle: "Check your mailbox"
            mail.delivery.sound: "MAIL_SOUND!"
            sounds.delivery.sound: "not a sound key!!"
            sounds.delivery.times: 3
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        messages.reload();
        String lacks = "WARNING lang/en.yml lacks gui.delivery.title, mail.delivery.sound, mail.delivery.subtitle,"
            + " order.delivery.subtitle, prefix, sounds.delivery.times; those messages use the bundled English text.";
        assertThat(log.lines()).containsExactly(lacks, lacks);
        messages.showTitle(text, player, "order.delivery");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(plain(shown.getValue().title())).isEqualTo("Mine");
        assertThat(plain(shown.getValue().subtitle())).isEqualTo("On its way");

        log.records.clear();
        assertThat(messages.addMissingKeys()).containsExactly("prefix", "order.delivery.subtitle",
            "gui.delivery.title", "mail.delivery.subtitle", "mail.delivery.sound", "sounds.delivery.times");
        assertThat(Files.readString(folder.resolve("lang").resolve("en.yml"))).isEqualTo(owned + """
            prefix: "P"
            order.delivery.subtitle: "On its way"
            gui.delivery.title: "Delivery"
            mail.delivery.subtitle: "Check your mailbox"
            mail.delivery.sound: "MAIL_SOUND!"
            sounds.delivery.times: "3"
            """);
        messages.reload();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aDeliverySectionWithAnotherEntryIsReportedAtASendAndTheMessageIsTakenFromTheNextFile() throws IOException {
        write("en.yml", """
            paid:
              delivery:
                actionbr: "Typo"
                chat: "Chat"
            got:
              delivery:
                actionbar: "Mine"
                title:
                  text: "Nested"
            blank:
              delivery:
                chat: "Mine"
                title:
                name:
            other:
              delivery:
                name: "Not a message of the plugin"
            """);
        Messages messages = Messages.load(folder, supplier("""
            paid: "Paid"
            got.delivery.actionbar: "Got"
            blank: "Blank"
            """), log.logger);
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        assertThat(log.lines()).containsExactly(
            "WARNING lang/en.yml lacks blank, paid; those messages use the bundled English text.");
        log.records.clear();
        for (String key : List.of("paid", "got", "blank", "other", "paid", "got", "blank", "other")) {
            messages.send(text, player, key);
        }

        assertThat(log.lines()).containsExactly(
            "WARNING lang/en.yml has paid.delivery.actionbr in the delivery section of paid" + OTHER_ENTRY
                + "paid is not read from this file.",
            "WARNING lang/en.yml has got.delivery.title.text in the delivery section of got" + OTHER_ENTRY
                + "got is not read from this file.",
            "WARNING lang/en.yml has blank.delivery.name (no value), blank.delivery.title (no value) in the delivery"
                + " section of blank" + OTHER_ENTRY + "blank is not read from this file.",
            "WARNING lang/en.yml has other.delivery.name in the delivery section of other" + OTHER_ENTRY
                + "other is not read from this file.");
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player, times(6)).sendMessage(chat.capture());
        assertThat(chat.getAllValues().subList(0, 3)).extracting(MessagesDeliveryTest::plain)
            .containsExactly("Paid", "Blank", "missing:other");
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Got");
        verify(player, never()).showTitle(any(Title.class));
    }

    @Test
    void addMissingKeysAddsEachEntryTheFileLacksAndAWholeSectionNested() throws IOException {
        String owned = """
            half:
              delivery:
                title: "Mine"
            moved:
              delivery:
                actionbar: "Bar"
            set: "As chat"
            nulled:
            entry:
              delivery:
                title:
            section:
              delivery:
            """;
        write("en.yml", owned);
        Messages messages = Messages.load(folder, supplier("""
            pay.sent:
              delivery:
                actionbar: "Sent"
                chat:
                  - "one"
                  - "two"
                sound: "a.b 1 2"
            half:
              delivery:
                actionbar: "Half"
                title: "Half title"
            plain: "Plain"
            moved: "Moved"
            set:
              delivery:
                title: "Set"
            nulled:
              delivery:
                title: "Nulled"
            entry:
              delivery:
                title: "Entry"
                subtitle: "Entry too"
            section:
              delivery:
                title: "Section"
            last: "Last"
            """), log.logger);
        log.records.clear();
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        List<String> added = messages.addMissingKeys();

        assertThat(added).containsExactly("pay.sent.delivery.actionbar", "pay.sent.delivery.chat",
            "pay.sent.delivery.sound", "half.delivery.actionbar", "plain", "nulled.delivery.title",
            "entry.delivery.subtitle", "section.delivery.title", "last");
        assertThat(Files.readString(folder.resolve("lang").resolve("en.yml"))).isEqualTo(owned + """
            pay.sent.delivery:
              actionbar: "Sent"
              chat:
                - "one"
                - "two"
              sound: "a.b 1 2"
            half.delivery.actionbar: "Half"
            plain: "Plain"
            nulled.delivery.title: "Nulled"
            entry.delivery.subtitle: "Entry too"
            section.delivery.title: "Section"
            last: "Last"
            """);
        messages.send(text, player, "pay.sent");
        messages.send(text, player, "half");
        messages.send(text, player, "moved");
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(chat.capture());
        assertThat(plain(chat.getValue())).isEqualTo("one\ntwo");
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player, times(3)).sendActionBar(bar.capture());
        assertThat(bar.getAllValues()).extracting(MessagesDeliveryTest::plain)
            .containsExactly("Sent", "Half", "Bar");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(shown.capture());
        assertThat(plain(shown.getValue().title())).isEqualTo("Mine");
        verify(player).playSound(Sound.sound(Key.key("a.b"), Sound.Source.MASTER, 1f, 2f));
        assertThat(log.lines()).isEmpty();
        assertThat(messages.addMissingKeys()).isEmpty();
    }

    @Test
    void aBundledLanguageBacksTheOwnersFileOneWholeMessageAtATimeAtASend() throws IOException {
        write("de.yml", """
            paid: "Meins"
            both.delivery.title: "Titel"
            """);
        Map<String, String> jar = Map.of(
            "en", """
                paid: "Paid"
                got.delivery.actionbar: "Got"
                both.delivery.actionbar: "Both"
                typo: "Typo"
                """,
            "de", """
                paid.delivery.actionbar: "Bezahlt"
                got: "Bekommen"
                both:
                  delivery:
                    actionbar: "Beide"
                    sound: "a.b"
                typo.delivery.actionbr: "Tippfehler"
                """);

        Messages messages = Messages.load(folder, "lang", List.of("de"),
            code -> supplier(jar.get(code)).get(), log.logger, "en", true);
        Text text = new Text(log.logger, null, null);
        Player german = viewer(Locale.GERMANY);
        Player english = viewer(Locale.ENGLISH);
        for (String key : List.of("paid", "got", "both", "typo")) {
            messages.send(text, german, key);
            messages.send(text, english, key);
        }

        assertThat(log.lines()).containsExactly(
            "WARNING lang/de.yml lacks typo; those messages are shown in another language.",
            "WARNING The bundled de.yml has typo.delivery.actionbr in the delivery section of typo" + OTHER_ENTRY
                + "typo is not read from this file.");
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(german, times(3)).sendMessage(chat.capture());
        assertThat(chat.getAllValues()).extracting(MessagesDeliveryTest::plain)
            .containsExactly("Meins", "Bekommen", "Typo");
        verify(german, never()).sendActionBar(any(Component.class));
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(german).showTitle(shown.capture());
        assertThat(plain(shown.getValue().title())).isEqualTo("Titel");
        verify(german, never()).playSound(any(Sound.class));
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(english, times(2)).sendActionBar(bar.capture());
        assertThat(bar.getAllValues()).extracting(MessagesDeliveryTest::plain).containsExactly("Got", "Both");
        verify(english, times(2)).sendMessage(chat.capture());
        assertThat(chat.getAllValues().subList(3, 5)).extracting(MessagesDeliveryTest::plain)
            .containsExactly("Paid", "Typo");

        log.records.clear();
        assertThat(messages.addMissingKeys("de")).containsExactly("got", "both.delivery.actionbar",
            "both.delivery.sound", "typo.delivery.actionbr");
        messages.send(text, german, "both");
        verify(german, times(2)).showTitle(any(Title.class));
        verify(german).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("Beide");
        verify(german).playSound(any(Sound.class));
        assertThat(log.lines()).isEmpty();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Player viewer(Locale locale) {
        Player viewer = mock(Player.class);
        when(viewer.locale()).thenReturn(locale);
        return viewer;
    }

    private void write(String name, String content) throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang").resolve(name), content);
    }

    private static Supplier<InputStream> supplier(String yaml) {
        return () -> new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
