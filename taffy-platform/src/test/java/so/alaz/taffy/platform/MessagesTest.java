package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.FakeFolders;
import so.alaz.taffy.test.JvmDefaults;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MessagesTest {

    private static final String BUNDLED = """
        prefix: "[P] "
        parcel.delivered: "<prefix>Delivered: <count>."
        parcel.held: "Held: <count>."
        """;

    private static final String LISTED = """
        prefix: "[L] "
        lore:
          - "<prefix><red>First <count>"
          - "{count} more"
        single: "§6Only {count}"
        """;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @TempDir
    Path folder;

    private final CapturedLog log = new CapturedLog();
    private final List<String> passInputs = new ArrayList<>();

    // Stands in for PlaceholderAPI: records each input and resolves %rank% and %tag%.
    private final BiFunction<Player, String, String> pass = (player, input) -> {
        passInputs.add(input);
        return input.replace("%rank%", "VIP").replace("%tag%", "DE");
    };

    @Test
    void aFreshFolderGetsTheBundledEnglishFileAndAnEditedOneIsKept() throws IOException {
        Messages.load(folder, bundled(), log.logger);
        Path english = folder.resolve("lang/en.yml");
        assertThat(english).hasContent(BUNDLED);

        Files.writeString(english, BUNDLED.replace("Delivered", "Arrived"));
        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.ENGLISH, "parcel.delivered")).isEqualTo("[P] Arrived: 7.");
        assertThat(english).content().contains("Arrived");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aLocaleResolvesToItsFileThenItsLanguageThenEnglish() throws IOException {
        write("de.yml", "parcel.delivered: \"<prefix>Zugestellt: <count>.\"\n");
        write("pt_BR.yml", "parcel.delivered: \"<prefix>Entregue: <count>.\"\n");

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[P] Zugestellt: 7.");
        assertThat(plain(messages, Locale.of("pt", "BR"), "parcel.delivered")).isEqualTo("[P] Entregue: 7.");
        assertThat(plain(messages, Locale.of("pt", "PT"), "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(plain(messages, Locale.GERMANY, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(log.lines()).containsExactlyInAnyOrder(
            "WARNING lang/de.yml lacks parcel.held, prefix; those messages use the English text.",
            "WARNING lang/pt_BR.yml lacks parcel.held, prefix; those messages fall back to lang/pt.yml, then to"
                + " English.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de", "pt_br");
        assertThat(messages.defaultLocale()).isEqualTo("en");
        assertThat(messages.perPlayer()).isTrue();
    }

    @Test
    void aFileThatIsNotYamlIsSkippedWithAWarning() throws IOException {
        write("fr.yml", "parcel.delivered: [unclosed\n");

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.FRANCE, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(log.lines()).containsExactly(
            "WARNING lang/fr.yml is not valid YAML saved as UTF-8, so its messages are not used.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
    }

    @Test
    void aLanguageFileThatIsNotUtf8IsSkippedWithAWarning() throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("parcel.delivered: \"Livr".getBytes(StandardCharsets.US_ASCII));
        bytes.write(0xC3);
        bytes.write(0x28);
        bytes.writeBytes("\"\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(folder.resolve("lang/fr.yml"), bytes.toByteArray());

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.FRANCE, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(log.lines()).containsExactly(
            "WARNING lang/fr.yml is not valid YAML saved as UTF-8, so its messages are not used.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
    }

    @Test
    void aFolderNamedLikeALanguageFileIsSkippedWithAWarning() throws IOException {
        Files.createDirectories(folder.resolve("lang/zz.yml"));

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(log.lines()).containsExactly(
            "WARNING lang/zz.yml is not valid YAML saved as UTF-8, so its messages are not used.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
    }

    @Test
    void aMissingBundledFileStopsTheLoadAndAnUnknownKeyRendersTheMarker() {
        assertThatThrownBy(() -> Messages.load(folder, () -> null, log.logger))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bundled en.yml");
        assertThatThrownBy(() -> Messages.load(folder, supplier("prefix: [unclosed\n"), log.logger))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bundled en.yml");
        byte[] notUtf8 = {'a', ':', ' ', '"', 'x', (byte) 0xC3, 0x28, '"', '\n'};
        assertThatThrownBy(() -> Messages.load(folder, () -> new ByteArrayInputStream(notUtf8), log.logger))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bundled en.yml");
        assertThat(folder.resolve("lang")).doesNotExist();

        Messages messages = Messages.load(folder, bundled(), log.logger);
        Component marker = Component.text("missing:parcel.nothing", NamedTextColor.RED);

        assertThat(messages.template(Locale.ENGLISH, "parcel.nothing")).isEqualTo("<red>missing:parcel.nothing");
        assertThat(messages.render(Locale.ENGLISH, "parcel.nothing", Placeholder.unparsed("count", "7")))
            .isEqualTo(marker);
        assertThat(messages.render(new Text(log.logger, null, null), null, "parcel.nothing")).isEqualTo(marker);
    }

    @Test
    void aFolderThatCannotBeWrittenFallsBackToTheBundledText() throws IOException {
        Path blocked = folder.resolve("blocked");
        Files.writeString(blocked, "a file where the data folder should be");

        Messages messages = Messages.load(blocked, bundled(), log.logger);

        assertThat(plain(messages, Locale.ENGLISH, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(log.lines()).hasSize(1).allMatch(line -> line.startsWith("WARNING Could not write lang/en.yml")
            && line.endsWith(". Messages use the bundled English text."));
        assertThat(messages.loadedLanguages()).isEmpty();

        messages.reload();

        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(log.lines()).hasSize(2);
    }

    @Test
    void withPerPlayerOffEveryLocaleGetsTheDefaultLanguage() throws IOException {
        write("de.yml", "parcel.held: \"Gehalten: <count>.\"\n");
        write("fr.yml", "parcel.held: \"Retenu: <count>.\"\n");

        Messages messages = Messages.load(folder, bundled(), log.logger, "de", false);

        assertThat(messages.perPlayer()).isFalse();
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(messages, null, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(messages.template(Locale.FRANCE, "parcel.held")).isEqualTo("Gehalten: <count>.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de", "fr");
    }

    @Test
    void aNonEnglishDefaultIsUsedBeforeTheBundledEnglishText() throws IOException {
        write("de.yml", "parcel.held: \"Gehalten: <count>.\"\n");
        write("fr.yml", "parcel.delivered: \"Livré: <count>.\"\n");
        write("pt_br.yml", "prefix: \"[BR] \"\n");
        write("de_at.yml", "prefix: \"[AT] \"\n");
        write("en.yml", "prefix: \"[EN] \"\nparcel.held: \"Kept: <count>.\"\n");

        Messages messages = Messages.load(folder, bundled(), log.logger, "DE", true);

        assertThat(messages.defaultLocale()).isEqualTo("de");
        assertThat(plain(messages, Locale.FRANCE, "parcel.delivered")).isEqualTo("Livré: 7.");
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(messages, Locale.ITALY, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(messages, Locale.ITALY, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(plain(messages, Locale.US, "parcel.held")).isEqualTo("Kept: 7.");
        assertThat(plain(messages, Locale.US, "parcel.delivered")).isEqualTo("[EN] Delivered: 7.");
        assertThat(plain(messages, Locale.of("de", "AT"), "parcel.delivered")).isEqualTo("[AT] Delivered: 7.");
        assertThat(plain(messages, Locale.of("de", "AT"), "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(log.lines()).containsExactlyInAnyOrder(
            "WARNING lang/de.yml lacks parcel.delivered, prefix; those messages use the bundled English text.",
            "WARNING lang/fr.yml lacks parcel.held, prefix; those messages fall back to lang/de.yml, then to the"
                + " bundled English text.",
            "WARNING lang/pt_br.yml lacks parcel.delivered, parcel.held; those messages fall back to lang/pt.yml,"
                + " then to lang/de.yml, then to the bundled English text.",
            "WARNING lang/de_at.yml lacks parcel.delivered, parcel.held; those messages fall back to lang/de.yml,"
                + " then to the bundled English text.",
            "WARNING lang/en.yml lacks parcel.delivered; those messages fall back to lang/de.yml, then to the"
                + " bundled English text.");
    }

    @Test
    void aDefaultLocaleIsReadInLowerCaseAndBlankAsEnglish() throws IOException {
        assertThat(Messages.normalizeLocale(null)).isEqualTo("en");
        assertThat(Messages.normalizeLocale("  ")).isEqualTo("en");
        assertThat(Messages.normalizeLocale("pt_BR")).isEqualTo("pt_br");

        write("pt_BR.yml", "parcel.held: \"Retido: <count>.\"\n");
        Messages messages = Messages.load(folder, bundled(), log.logger, "PT_BR", false);

        assertThat(messages.defaultLocale()).isEqualTo("pt_br");
        assertThat(plain(messages, null, "parcel.held")).isEqualTo("Retido: 7.");
        assertThat(Messages.load(folder, bundled(), log.logger, "", true).defaultLocale()).isEqualTo("en");
        assertThat(Messages.load(folder, bundled(), log.logger, null, true).defaultLocale()).isEqualTo("en");
    }

    @Test
    void aHyphenInTheDefaultLocaleOrInAFileNameReadsAsAnUnderscore() throws IOException {
        assertThat(Messages.normalizeLocale("pt-BR")).isEqualTo("pt_br");

        write("pt_br.yml", "parcel.held: \"Retido: <count>.\"\n");
        write("de-AT.yml", "parcel.held: \"Gehalten: <count>.\"\n");
        Messages messages = Messages.load(folder, bundled(), log.logger, "pt-BR", true);

        assertThat(messages.defaultLocale()).isEqualTo("pt_br");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "pt_br", "de_at");
        assertThat(plain(messages, null, "parcel.held")).isEqualTo("Retido: 7.");
        assertThat(plain(messages, Locale.JAPAN, "parcel.held")).isEqualTo("Retido: 7.");
        assertThat(plain(messages, Locale.of("de", "AT"), "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(log.lines()).containsExactlyInAnyOrder(
            "WARNING lang/pt_br.yml lacks parcel.delivered, prefix; those messages use the bundled English text.",
            "WARNING lang/de-AT.yml lacks parcel.delivered, prefix; those messages fall back to lang/de.yml, then to"
                + " lang/pt_br.yml, then to the bundled English text.");
    }

    @Test
    void aDefaultLocaleWithoutAFileIsReportedAndServedFromTheBundledText() throws IOException {
        write("fr.yml", BUNDLED.replace("Held", "Retenu"));

        Messages messages = Messages.load(folder, bundled(), log.logger, "de", true);

        assertThat(plain(messages, null, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Retenu: 7.");
        assertThat(log.lines()).containsExactly(
            "WARNING The default language de has no lang/de.yml, so the bundled English text is used in its place.");

        messages.reload();

        assertThat(log.lines()).hasSize(2);
    }

    @Test
    void localeCodesAreMatchedTheSameUnderATurkishDefaultLocale() throws Exception {
        write("IT_IT.yml", BUNDLED.replace("Held", "Tenuto"));
        write("FI.yml", BUNDLED.replace("Held", "Pidossa"));

        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat(Messages.normalizeLocale("IT_IT")).isEqualTo("it_it");
            Messages messages = Messages.load(folder, bundled(), log.logger, "IT_IT", true);

            assertThat(messages.defaultLocale()).isEqualTo("it_it");
            assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "it_it", "fi");
            assertThat(plain(messages, null, "parcel.held")).isEqualTo("Tenuto: 7.");
            assertThat(plain(messages, Locale.ITALY, "parcel.held")).isEqualTo("Tenuto: 7.");
            assertThat(plain(messages, Locale.of("FI", "FI"), "parcel.held")).isEqualTo("Pidossa: 7.");
            assertThat(log.lines()).isEmpty();
        });
    }

    @Test
    void anExactLocaleFileIsReadBeforeItsLanguageFileAndAHyphenReadsAsAnUnderscore() throws IOException {
        write("pt.yml", "parcel.held: \"Retido: <count>.\"\nparcel.delivered: \"Entregue: <count>.\"\n");
        write("pt_br.yml", "parcel.held: \"Segurado: <count>.\"\n");

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.of("pt", "BR"), "parcel.held")).isEqualTo("Segurado: 7.");
        assertThat(plain(messages, Locale.of("pt", "BR"), "parcel.delivered")).isEqualTo("Entregue: 7.");
        assertThat(plain(messages, Locale.of("pt", "PT"), "parcel.held")).isEqualTo("Retido: 7.");
        assertThat(Locale.of("pt-br")).hasToString("pt-br");
        assertThat(plain(messages, Locale.of("pt-br"), "parcel.held")).isEqualTo("Segurado: 7.");
    }

    @Test
    void aNullLocaleGetsTheDefaultLanguage() throws IOException {
        write("de.yml", "parcel.held: \"Gehalten: <count>.\"\n");

        Messages english = Messages.load(folder, bundled(), log.logger);
        Messages german = Messages.load(folder, bundled(), log.logger, "de", true);

        assertThat(plain(english, null, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(english.template(null, "parcel.held")).isEqualTo("Held: <count>.");
        assertThat(plain(german, null, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(german, null, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(plain(german, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
    }

    @Test
    void reloadReadsTheFolderAgain() throws IOException {
        Messages messages = Messages.load(folder, bundled(), log.logger);
        Path english = folder.resolve("lang/en.yml");

        Files.writeString(english, BUNDLED.replace("Held", "Kept"));
        write("fr.yml", "parcel.held: \"Retenu: <count>.\"\n");
        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
        messages.reload();

        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Kept: 7.");
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Retenu: 7.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "fr");
        assertThat(log.lines()).containsExactly(
            "WARNING lang/fr.yml lacks parcel.delivered, prefix; those messages use the English text.");

        Files.delete(english);
        Files.delete(folder.resolve("lang/fr.yml"));
        write("de.yml", "parcel.held: [unclosed\n");
        messages.reload();

        assertThat(english).hasContent(BUNDLED);
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
        assertThat(log.lines()).hasSize(2).last().isEqualTo(
            "WARNING lang/de.yml is not valid YAML saved as UTF-8, so its messages are not used.");
    }

    @Test
    void aRenderDuringAReloadSeesTheFilesLoadedBefore() throws IOException {
        write("de.yml", BUNDLED);
        Messages messages = Messages.load(folder, bundled(), log.logger);
        Files.delete(folder.resolve("lang/de.yml"));
        write("fr.yml", "parcel.held: \"Retenu: <count>.\"\n");
        List<Set<String>> seenWhileReloading = new ArrayList<>();
        // The reload warns about fr.yml while it is still reading the folder.
        log.logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                seenWhileReloading.add(messages.loadedLanguages());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        messages.reload();

        assertThat(seenWhileReloading).containsExactly(Set.of("en", "de"));
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "fr");
    }

    @Test
    void aFolderThatFailsWhileItIsListedFallsBackToTheBundledText() throws IOException {
        Path german = folder.resolve("de.yml");
        Files.writeString(german, BUNDLED.replace("Held", "Gehalten"));
        // Lists de.yml, then fails.
        Messages messages = Messages.load(FakeFolders.unlistable(new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return new Iterator<>() {
                    private boolean listed;

                    @Override
                    public boolean hasNext() {
                        if (listed) {
                            throw new DirectoryIteratorException(new IOException("device error"));
                        }
                        return true;
                    }

                    @Override
                    public Path next() {
                        if (listed) {
                            throw new NoSuchElementException();
                        }
                        listed = true;
                        return german;
                    }
                };
            }

            @Override
            public void close() {
            }
        }), bundled(), log.logger);

        assertThat(log.lines()).containsExactly(
            "WARNING Could not read the lang folder: device error. Messages use the bundled English text.");
        assertThat(messages.loadedLanguages()).isEmpty();
        assertThat(plain(messages, Locale.GERMANY, "parcel.held")).isEqualTo("Held: 7.");
    }

    @Test
    void aFolderThatCannotBeListedFallsBackToTheBundledText() {
        Messages messages = Messages.load(FakeFolders.unlistable(null), bundled(), log.logger);

        assertThat(log.lines()).containsExactly(
            "WARNING Could not read the lang folder: access denied. Messages use the bundled English text.");
        assertThat(messages.loadedLanguages()).isEmpty();
        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
    }

    @Test
    void aBundledFileThatIsGoneAtReloadLeavesTheEnglishFileUnwritten() throws IOException {
        AtomicBoolean gone = new AtomicBoolean();
        Messages messages = Messages.load(folder, () -> gone.get() ? null : bundled().get(), log.logger);
        Path english = folder.resolve("lang/en.yml");
        Files.delete(english);
        gone.set(true);

        messages.reload();

        assertThat(english).doesNotExist();
        assertThat(messages.loadedLanguages()).isEmpty();
        assertThat(plain(messages, Locale.ENGLISH, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void thePrefixAppearsOnlyWhereATemplateAsksForIt() throws IOException {
        write("en.yml", """
            prefix: "<red>[P] "
            tag: "<prefix>Hi"
            braces: "{prefix}Hi"
            both: "<prefix>a {prefix}b"
            none: "Hi"
            """);
        write("de.yml", "prefix: \"[DE] \"\n");
        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(messages.render(Locale.ENGLISH, "tag")).isEqualTo(MINI.deserialize("<red>[P] Hi"));
        assertThat(messages.render(Locale.ENGLISH, "braces")).isEqualTo(MINI.deserialize("<red>[P] Hi"));
        assertThat(plain(messages.render(Locale.ENGLISH, "both"))).isEqualTo("[P] a [P] b");
        assertThat(messages.render(Locale.ENGLISH, "none")).isEqualTo(Component.text("Hi"));
        assertThat(plain(messages.render(Locale.GERMANY, "tag"))).isEqualTo("[DE] Hi");
        assertThat(plain(messages.render(Locale.FRANCE, "braces"))).isEqualTo("[P] Hi");
        assertThat(messages.template(Locale.ENGLISH, "braces")).isEqualTo("{prefix}Hi");
        assertThat(plain(messages.render(Locale.ENGLISH, "tag", Placeholder.unparsed("prefix", "<mine> "))))
            .isEqualTo("<mine> Hi");
    }

    @Test
    void sectionSignCodesInATemplateOrInThePrefixAreRenderedAsTheirTags() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "§6[%tag%] "
            hi: "<prefix>§aHi <name>"
            """), log.logger);
        Text text = new Text(log.logger, (player, input) -> input.replace("%tag%", "§bT"), null);

        Component withoutViewer = messages.render(Locale.ENGLISH, "hi", Placeholder.unparsed("name", "Ann"));
        Component withViewer = messages.render(text, viewer(Locale.ENGLISH), "hi", Placeholder.unparsed("name", "Ann"));

        assertThat(withoutViewer).isEqualTo(MINI.deserialize("<gold>[%tag%] <green>Hi Ann"));
        assertThat(withViewer).isEqualTo(MINI.deserialize("<gold>[<aqua>T</aqua>] <green>Hi Ann"));
        assertThat(messages.template(Locale.ENGLISH, "hi")).isEqualTo("<prefix>§aHi <name>");
    }

    @Test
    void sectionSignCodesInAParsedValueAreRenderedAsTheirTags() {
        Messages messages = Messages.load(folder, supplier("got: \"You got <item>\"\n"), log.logger);
        Text text = new Text(log.logger, null, null);
        Component expected = MINI.deserialize("You got <green>Keys");

        assertThat(messages.render(Locale.ENGLISH, "got", Placeholder.parsed("item", "§aKeys"))).isEqualTo(expected);
        assertThat(messages.render(text, viewer(Locale.ENGLISH), "got", Placeholder.parsed("item", "§aKeys")))
            .isEqualTo(expected);
        assertThat(messages.lines(Locale.ENGLISH, "got", Placeholder.parsed("item", "§aKeys")))
            .containsExactly(expected);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aMissingPrefixIsEmpty() {
        Messages messages = Messages.load(folder, supplier("tag: \"<prefix>Hi {prefix}!\"\n"), log.logger);

        assertThat(messages.render(Locale.ENGLISH, "tag")).isEqualTo(Component.text("Hi !"));
        assertThat(messages.render(new Text(log.logger, null, null), null, "tag")).isEqualTo(Component.text("Hi !"));
    }

    @Test
    void bracedNamesReadAsTagsForSuppliedValues() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[{world}] "
            give: "{prefix}{player} got {count} of {max}"
            """), log.logger);

        Component rendered = messages.render(Locale.ENGLISH, "give",
            Placeholder.unparsed("player", "<red>Ann"),
            Placeholder.unparsed("count", "3"),
            Placeholder.unparsed("world", "nether"));

        assertThat(plain(rendered)).isEqualTo("[nether] <red>Ann got 3 of {max}");
        assertThat(plain(messages.render(Locale.ENGLISH, "give"))).isEqualTo("[{world}] {player} got {count} of {max}");
        Text plainText = new Text(log.logger, null, null);
        assertThat(plain(messages.render(plainText, null, "give", Placeholder.unparsed("world", "end"))))
            .isEqualTo("[end] {player} got {count} of {max}");
    }

    @Test
    void theViewerPathUsesTheViewersLanguageAndRunsThePlaceholderApiPass() throws IOException {
        write("de.yml", """
            prefix: "[%tag%] "
            greet: "<prefix>Hallo <name>, Rang %rank%"
            """);
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[EN] "
            greet: "<prefix>Hello <name>, rank %rank%"
            """), log.logger);
        Text text = new Text(log.logger, pass, null);
        Player german = viewer(Locale.GERMANY);

        Component rendered = messages.render(text, german, "greet", Placeholder.unparsed("name", "%rank%"));

        assertThat(plain(rendered)).isEqualTo("[DE] Hallo %rank%, Rang VIP");
        assertThat(passInputs).containsExactly("%tag%", "%rank%");

        passInputs.clear();
        assertThat(plain(messages.render(text, viewer(Locale.JAPAN), "greet", Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("[EN] Hello Ann, rank VIP");
        assertThat(plain(messages.render(text, german, "greet",
            Placeholder.unparsed("name", "Ann"), Placeholder.unparsed("prefix", "%tag% "))))
            .isEqualTo("%tag% Hallo Ann, Rang VIP");

        passInputs.clear();
        assertThat(plain(messages.render(text, null, "greet", Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("[EN] Hello Ann, rank %rank%");
        assertThat(passInputs).isEmpty();
    }

    @Test
    void aTagInPlaceholderOutputIsShownAsWrittenInThePrefixAndInTheTemplate() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "<gold>[%tag%] "
            greet: "<prefix>Hello <name> %rank%"
            """), log.logger);
        String injected = "<click:run_command:'/op Mallory'><hover:show_text:'x'><name>";
        Text text = new Text(log.logger,
            (player, input) -> input.replace("%tag%", injected).replace("%rank%", "<green>VIP"), null);
        Player english = viewer(Locale.ENGLISH);

        Component inert = messages.render(text, english, "greet", Placeholder.unparsed("name", "Ann"));
        Component formatted = messages.render(
            text.withPlaceholderFormatting(), english, "greet", Placeholder.unparsed("name", "Ann"));
        Component template = messages.renderTemplate(text, english, "{prefix}%rank%");

        assertThat(inert).isEqualTo(Component.text("[" + injected + "] Hello Ann <green>VIP", NamedTextColor.GOLD));
        assertThat(formatted).isEqualTo(Component.text("[" + injected + "] Hello Ann ", NamedTextColor.GOLD)
            .append(Component.text("VIP", NamedTextColor.GREEN)));
        assertThat(template).isEqualTo(Component.text("[" + injected + "] <green>VIP", NamedTextColor.GOLD));
    }

    @Test
    void theViewerPathServesTheDefaultLanguageWithPerPlayerOff() throws IOException {
        write("de.yml", "greet: \"Hallo <name>\"\n");
        Messages messages = Messages.load(folder, supplier("greet: \"Hello <name>\"\n"), log.logger, "en", false);

        Component rendered = messages.render(
            new Text(log.logger, null, null), viewer(Locale.GERMANY), "greet", Placeholder.unparsed("name", "Ann"));

        assertThat(plain(rendered)).isEqualTo("Hello Ann");
    }

    @Test
    void aTemplateThatIsNotAKeyIsRenderedWithThePrefixOfTheViewersLanguage() throws IOException {
        write("de.yml", "prefix: \"[%tag%] \"\n");
        Messages messages = Messages.load(folder, bundled(), log.logger);
        Text text = new Text(log.logger, pass, null);
        Player german = viewer(Locale.GERMANY);

        Component rendered = messages.renderTemplate(
            text, german, "{prefix}You got {amount} <item>, %rank%",
            Placeholder.unparsed("amount", "3"), Placeholder.parsed("item", "<red>keys"));

        assertThat(plain(rendered)).isEqualTo("[DE] You got 3 keys, VIP");
        assertThat(MINI.serialize(rendered)).contains("<red>keys");
        assertThat(passInputs).containsExactly("%tag%", "%rank%");
        assertThat(plain(messages.renderTemplate(text, german, "parcel.held"))).isEqualTo("parcel.held");
        assertThat(plain(messages.renderTemplate(text, null, "<prefix>console %rank%")))
            .isEqualTo("[P] console %rank%");
    }

    @Test
    void everyValueThatIsNotASectionIsKeptAndAListAsItsLines() {
        Messages messages = Messages.load(folder, supplier("""
            count: 5
            enabled: true
            lines:
              - a
              - b
            command:
              give:
                success: "Given."
            """), log.logger);

        assertThat(messages.template(null, "count")).isEqualTo("5");
        assertThat(messages.template(null, "enabled")).isEqualTo("true");
        assertThat(messages.template(null, "lines")).isEqualTo("a\nb");
        assertThat(messages.template(null, "command.give.success")).isEqualTo("Given.");
        assertThat(messages.template(null, "command.give")).isEqualTo("<red>missing:command.give");
    }

    @Test
    void aListRendersWithItsLinesJoinedByNewlines() {
        Messages messages = Messages.load(folder, listed(), log.logger);

        assertThat(messages.template(Locale.ENGLISH, "lore")).isEqualTo("<prefix><red>First <count>\n{count} more");
        assertThat(plain(messages, Locale.ENGLISH, "lore")).isEqualTo("[L] First 7\n7 more");
        Text plainText = new Text(log.logger, null, null);
        assertThat(plain(messages.render(plainText, null, "lore", Placeholder.unparsed("count", "7"))))
            .isEqualTo("[L] First 7\n7 more");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void linesGivesOneComponentPerEntryWithItsValuesFilled() {
        Messages messages = Messages.load(folder, listed(), log.logger);

        List<Component> lore = messages.lines(Locale.ENGLISH, "lore", Placeholder.unparsed("count", "<b>7"));

        assertThat(lore).extracting(MessagesTest::plain).containsExactly("[L] First <b>7", "<b>7 more");
        assertThat(lore.getFirst()).isEqualTo(MINI.deserialize("[L] <red>First <count>",
            Placeholder.unparsed("count", "<b>7")));
        assertThat(lore.get(1).color()).isNull();
        assertThat(lore).isUnmodifiable();
        assertThat(messages.lines(Locale.ENGLISH, "single", Placeholder.unparsed("count", "7")))
            .containsExactly(MINI.deserialize("<gold>Only 7"));
        assertThat(messages.lines(null, "lore", Placeholder.unparsed("count", "7"),
            Placeholder.unparsed("prefix", "> "))).extracting(MessagesTest::plain)
            .containsExactly("> First 7", "7 more");
    }

    @Test
    void aLanguageFileCanTurnALineIntoAList() throws IOException {
        write("de.yml", "single: [\"Eins {count}\", \"Zwei\"]\n");
        Messages messages = Messages.load(folder, listed(), log.logger);

        assertThat(messages.lines(Locale.GERMANY, "single", Placeholder.unparsed("count", "7")))
            .extracting(MessagesTest::plain).containsExactly("Eins 7", "Zwei");
        assertThat(plain(messages, Locale.GERMANY, "single")).isEqualTo("Eins 7\nZwei");
        assertThat(messages.lines(Locale.ENGLISH, "single", Placeholder.unparsed("count", "7"))).hasSize(1);
    }

    @Test
    void aListPrefixIsJoinedByANewlineWhereverItAppears() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: ["a", "b"]
            tag: "<prefix>Hi"
            lore:
              - "<prefix>x"
              - "y"
            """), log.logger);

        assertThat(messages.template(null, "prefix")).isEqualTo("a\nb");
        assertThat(plain(messages.render(Locale.ENGLISH, "tag"))).isEqualTo("a\nbHi");
        assertThat(plain(messages.render(new Text(log.logger, null, null), null, "tag"))).isEqualTo("a\nbHi");
        assertThat(messages.lines(Locale.ENGLISH, "lore")).extracting(MessagesTest::plain)
            .containsExactly("a\nbx", "y");
    }

    @Test
    void anEmptyListEntryIsAnEmptyLine() {
        Messages messages = Messages.load(folder, supplier("""
            lore:
              - first
              -
              - third
            """), log.logger);

        assertThat(messages.lines(Locale.ENGLISH, "lore")).extracting(MessagesTest::plain)
            .containsExactly("first", "", "third");
        assertThat(messages.template(Locale.ENGLISH, "lore")).isEqualTo("first\n\nthird");
    }

    @Test
    void aBlockScalarIsOneLineThatKeepsItsNewlines() {
        Messages messages = Messages.load(folder, supplier("""
            lore: |
              first
              second
            """), log.logger);

        assertThat(messages.lines(Locale.ENGLISH, "lore")).extracting(MessagesTest::plain)
            .containsExactly("first\nsecond\n");
    }

    @Test
    void anEmptyListIsAMessageOfNoLines() {
        Messages messages = Messages.load(folder, supplier("""
            lore: []
            other: "z"
            """), log.logger);

        assertThat(messages.has(Locale.ENGLISH, "lore")).isTrue();
        assertThat(messages.lines(Locale.ENGLISH, "lore")).isEmpty();
        assertThat(messages.template(Locale.ENGLISH, "lore")).isEmpty();
        assertThat(messages.render(Locale.ENGLISH, "lore")).isEqualTo(Component.empty());
        assertThat(plain(messages.render(new Text(log.logger, null, null), null, "lore"))).isEmpty();
    }

    @Test
    void linesOfAKeyNoFileHasIsTheMarkerLine() {
        Messages messages = Messages.load(folder, listed(), log.logger);

        assertThat(messages.lines(Locale.ENGLISH, "nowhere", Placeholder.unparsed("count", "7")))
            .containsExactly(Component.text("missing:nowhere", NamedTextColor.RED));
        assertThat(messages.lines(null, "nowhere"))
            .containsExactly(Component.text("missing:nowhere", NamedTextColor.RED));
    }

    @Test
    void hasLooksOnlyWhereALocaleResolves() throws IOException {
        write("de.yml", "only-german: x\n");
        write("de_at.yml", "only-austrian: x\n");
        write("fr.yml", "only-french: x\n");

        Messages messages = Messages.load(folder, listed(), log.logger);

        assertThat(messages.has(Locale.GERMAN, "only-german")).isTrue();
        assertThat(messages.has(Locale.of("de", "AT"), "only-german")).isTrue();
        assertThat(messages.has(Locale.of("de", "AT"), "only-austrian")).isTrue();
        assertThat(messages.has(Locale.GERMAN, "only-austrian")).isFalse();
        assertThat(messages.has(Locale.ENGLISH, "only-german")).isFalse();
        assertThat(messages.has(null, "only-german")).isFalse();
        assertThat(messages.has(Locale.FRANCE, "lore")).isTrue();
        assertThat(messages.has(null, "lore")).isTrue();
        assertThat(messages.has(Locale.ENGLISH, "nowhere")).isFalse();

        Messages german = Messages.load(folder, listed(), log.logger, "de", false);

        assertThat(german.has(Locale.FRANCE, "only-german")).isTrue();
        assertThat(german.has(null, "only-german")).isTrue();
        assertThat(german.has(Locale.FRANCE, "only-french")).isFalse();
        assertThat(german.has(Locale.of("de", "AT"), "only-austrian")).isFalse();
        assertThat(german.has(Locale.FRANCE, "single")).isTrue();
    }

    private String plain(Messages messages, Locale locale, String key) {
        return plain(messages.render(locale, key, Placeholder.unparsed("count", "7")));
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

    private static Supplier<InputStream> listed() {
        return supplier(LISTED);
    }

    private static Supplier<InputStream> bundled() {
        return supplier(BUNDLED);
    }

    private static Supplier<InputStream> supplier(String yaml) {
        return () -> new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
