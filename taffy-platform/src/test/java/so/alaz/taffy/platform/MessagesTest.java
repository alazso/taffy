package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import so.alaz.taffy.test.FakeFolders;
import so.alaz.taffy.test.JvmDefaults;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static final String GERMAN = """
        prefix: "[D] "
        # shown after a delivery
        parcel.delivered: "<prefix>Geliefert f\u00fcr <count> \u20ac."
        parcel.held: "Gehalten: <count>."
        """;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @TempDir
    Path folder;

    private final CapturedLog log = new CapturedLog();
    private final List<String> passInputs = new ArrayList<>();

    // Stands in for PlaceholderAPI: records each input and resolves %rank% and %tag%.
    private final BiFunction<OfflinePlayer, String, String> pass = (player, input) -> {
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
        assertThat(log.lines()).containsExactly("WARNING lang/fr.yml is not saved as UTF-8 (the first byte that is"
            + " not UTF-8 is on line 1), so its messages are not used.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
    }

    @Test
    void aLanguageFileSavedInALegacyCodePageIsSkippedWithTheLineOfTheFirstSuchByte() throws IOException {
        writeWindows1252("de.yml", GERMAN);

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(plain(messages, Locale.GERMANY, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(log.lines()).containsExactly("WARNING lang/de.yml is not saved as UTF-8 (the first byte that is"
            + " not UTF-8 is on line 3), so its messages are not used.");
        assertThat(messages.loadedLanguages()).containsExactly("en");
    }

    @Test
    void aLenientLoadKeepsTheKeysOfAFileSavedInALegacyCodePage() throws IOException {
        writeWindows1252("de.yml", GERMAN);
        String replaced = "WARNING lang/de.yml is not saved as UTF-8, so each byte that is not UTF-8 is read as the"
            + " replacement character U+FFFD; the first is on line 3. Save the file as UTF-8.";

        Messages messages = Messages.loadLenient(folder, bundled(), log.logger, "en", true);

        assertThat(plain(messages, Locale.GERMANY, "parcel.held")).isEqualTo("Gehalten: 7.");
        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[D] Geliefert f\uFFFDr 7 \uFFFD.");
        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de");
        assertThat(log.lines()).containsExactly(replaced);

        messages.reload();

        assertThat(log.lines()).containsExactly(replaced, replaced);
    }

    @Test
    void aLenientLoadStillSkipsAFileThatIsNotValidYamlAndReadsAUtf8FileWithoutAWarning() throws IOException {
        writeWindows1252("fr.yml", "parcel.held: \"Retenu \u00e0 <count>\n");
        write("de.yml", GERMAN);

        Messages messages = Messages.loadLenient(folder, bundled(), log.logger, null, true);

        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[D] Geliefert f\u00fcr 7 \u20ac.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de");
        assertThat(log.lines()).containsExactly(
            "WARNING lang/fr.yml is not saved as UTF-8, so each byte that is not UTF-8 is read as the replacement"
                + " character U+FFFD; the first is on line 1. Save the file as UTF-8.",
            "WARNING lang/fr.yml is not valid YAML saved as UTF-8, so its messages are not used.");
    }

    @Test
    void aLenientLoadStillRefusesABundledFileThatIsNotUtf8() {
        byte[] notUtf8 = "prefix: \"caf\u00e9\"\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(
            () -> Messages.loadLenient(folder, () -> new ByteArrayInputStream(notUtf8), log.logger, "en", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the bundled en.yml is missing or unreadable");
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
    void twoFilesOfOneNormalizedLocaleGiveAWarningThatNamesTheOneUsed() throws IOException {
        write("pt-BR.yml", BUNDLED.replace("Held", "Hyphen"));
        write("pt_br.yml", BUNDLED.replace("Held", "Underscore"));

        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "pt_br");
        String held = plain(messages, Locale.of("pt", "BR"), "parcel.held");
        String used = held.equals("Hyphen: 7.") ? "pt-BR.yml" : "pt_br.yml";
        String replaced = used.equals("pt-BR.yml") ? "pt_br.yml" : "pt-BR.yml";
        assertThat(held).isIn("Hyphen: 7.", "Underscore: 7.");
        assertThat(log.lines()).containsExactly("WARNING lang/" + replaced + " and lang/" + used
            + " are both read as the language pt_br, so only lang/" + used + " is used. Remove or rename one of"
            + " them.");
    }

    @Test
    void aFileThatIsSkippedTakesNoPartInTheWarningForOneNormalizedLocale() throws IOException {
        write("pt-BR.yml", "parcel.held: [unclosed\n");
        write("pt_br.yml", BUNDLED);

        Messages.load(folder, bundled(), log.logger);

        assertThat(log.lines()).containsExactly(
            "WARNING lang/pt-BR.yml is not valid YAML saved as UTF-8, so its messages are not used.");
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

    @Test
    void aNamedFolderHoldsTheFilesAndIsNamedInEveryWarning() throws IOException {
        Path locales = Files.createDirectories(folder.resolve("locales"));
        Files.writeString(locales.resolve("de.yml"), "parcel.delivered: \"<prefix>Zugestellt: <count>.\"\n");
        Files.writeString(locales.resolve("fr.yml"), "parcel.delivered: [unclosed\n");
        Files.writeString(locales.resolve("pt-BR.yml"), "parcel.held: \"a\"\n");
        Files.write(locales.resolve("es.yml"), new byte[] {'a', ':', ' ', (byte) 0xC3, 0x28, '\n'});

        Messages messages = Messages.load(folder, "locales", bundled(), log.logger, "it", true);

        assertThat(locales.resolve("en.yml")).hasContent(BUNDLED);
        assertThat(folder.resolve("lang")).doesNotExist();
        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[P] Zugestellt: 7.");
        assertThat(log.lines()).containsExactlyInAnyOrder(
            "WARNING locales/de.yml lacks parcel.held, prefix; those messages fall back to locales/it.yml, then to"
                + " the bundled English text.",
            "WARNING locales/fr.yml is not valid YAML saved as UTF-8, so its messages are not used.",
            "WARNING locales/pt-BR.yml lacks parcel.delivered, prefix; those messages fall back to locales/pt.yml,"
                + " then to locales/it.yml, then to the bundled English text.",
            "WARNING locales/es.yml is not saved as UTF-8 (the first byte that is not UTF-8 is on line 1), so its"
                + " messages are not used.",
            "WARNING The default language it has no locales/it.yml, so the bundled English text is used in its"
                + " place.");

        log.records.clear();
        Files.delete(locales.resolve("en.yml"));
        Files.delete(locales.resolve("fr.yml"));
        Files.delete(locales.resolve("es.yml"));
        Files.writeString(locales.resolve("pt_br.yml"), BUNDLED);
        messages.reload();

        assertThat(locales.resolve("en.yml")).hasContent(BUNDLED);
        assertThat(log.lines()).filteredOn(line -> line.contains("both")).hasSize(1).first().asString()
            .startsWith("WARNING locales/pt").contains(" and locales/pt").contains("so only locales/pt");
    }

    @Test
    void aFolderThatIsNotOnePathSegmentIsRefused() {
        for (String name : List.of("", " ", ".", "..", "a/b", "a\\b", "../lang", "a..b")) {
            assertThatThrownBy(() -> Messages.load(folder, name, bundled(), log.logger, "en", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the folder '" + name + "' is not one path segment");
            assertThatThrownBy(
                () -> Messages.load(folder, name, List.of(), code -> bundled().get(), log.logger, "en", true))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(folder).isEmptyDirectory();
    }

    @Test
    void aFolderThatCannotBeWrittenIsNamedInTheWarning() throws IOException {
        Files.writeString(folder.resolve("locales"), "a file where the folder belongs");

        Messages messages = Messages.load(folder, "locales", bundled(), log.logger, "en", true);

        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(log.lines()).hasSize(1).first().asString()
            .startsWith("WARNING Could not write locales/en.yml: ")
            .endsWith(". Messages use the bundled English text.");
    }

    @Test
    void addMissingKeysAppendsTheBundledKeysAndKeepsTheOwnersLines() throws IOException {
        Path english = Files.createDirectories(folder.resolve("locales")).resolve("en.yml");
        String owned = "# my shop\r\nshop:\r\n  bought: 'Mine'   # kept\r\n\r\nextra: \"x\"";
        Files.writeString(english, owned);
        Messages messages = Messages.load(folder, "locales", supplier("""
            shop:
              bought: "Bought"
              sold: "Sold \\"it\\" for {price}\\\\"
              help:
                - "<gold>/shop buy"
                - ""
                - "caf\u00e9 \u20ac"
            none: []
            "yes": "y"
            block: |
              one
              two
            time: 20
            """), log.logger, "en", true);
        log.records.clear();

        List<String> added = messages.addMissingKeys();

        assertThat(added).containsExactly("shop.sold", "shop.help", "none", "yes", "block", "time");
        assertThat(Files.readString(english)).isEqualTo(owned + "\r\n"
            + "shop.sold: \"Sold \\\"it\\\" for {price}\\\\\"\r\n"
            + "shop.help:\r\n  - \"<gold>/shop buy\"\r\n  - \"\"\r\n  - \"caf\u00e9 \u20ac\"\r\n"
            + "none: []\r\n"
            + "\"yes\": \"y\"\r\n"
            + "block: \"one\\ntwo\\n\"\r\n"
            + "time: \"20\"\r\n");
        assertThat(messages.template(null, "shop.bought")).isEqualTo("Mine");
        assertThat(messages.template(null, "extra")).isEqualTo("x");
        assertThat(messages.template(null, "shop.sold")).isEqualTo("Sold \"it\" for {price}\\");
        assertThat(messages.lines(null, "shop.help")).hasSize(3);
        assertThat(messages.lines(null, "none")).isEmpty();
        assertThat(messages.template(null, "block")).isEqualTo("one\ntwo\n");
        assertThat(log.lines()).isEmpty();
        assertThat(english.resolveSibling("en.yml.tmp")).doesNotExist();

        assertThat(messages.addMissingKeys()).isEmpty();
        messages.reload();
        assertThat(log.lines()).isEmpty();
        assertThat(messages.addMissingKeys("fr")).isEmpty();
    }

    @Test
    void addMissingKeysKeepsAByteOrderMarkAndLineFeeds() throws IOException {
        Path english = Files.createDirectories(folder.resolve("lang")).resolve("en.yml");
        byte[] owned =
            "\uFEFFprefix: \"[\u00dc] \"\n# held\nparcel.held: \"Held\"\n".getBytes(StandardCharsets.UTF_8);
        Files.write(english, owned);
        Messages messages = Messages.load(folder, bundled(), log.logger);

        assertThat(messages.addMissingKeys()).containsExactly("parcel.delivered");

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(owned);
        expected.writeBytes("parcel.delivered: \"<prefix>Delivered: <count>.\"\n".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(english)).isEqualTo(expected.toByteArray());
        assertThat(plain(messages, Locale.ENGLISH, "parcel.delivered")).isEqualTo("[\u00dc] Delivered: 7.");
    }

    @Test
    void addMissingKeysLeavesAFileThatIsNotReadableAsItIs() throws IOException {
        Path english = Files.createDirectories(folder.resolve("lang")).resolve("en.yml");
        Files.writeString(english, "parcel.held: [unclosed\n");
        Messages messages = Messages.load(folder, bundled(), log.logger);
        log.records.clear();

        assertThat(messages.addMissingKeys()).isEmpty();
        assertThat(english).hasContent("parcel.held: [unclosed\n");
        assertThat(log.lines()).containsExactly(
            "WARNING lang/en.yml is not valid YAML saved as UTF-8, so no key was added to it.");

        log.records.clear();
        byte[] legacy = "parcel.held: \"Gar\u00e7on\"\n".getBytes(Charset.forName("windows-1252"));
        Files.write(english, legacy);
        Messages lenient = Messages.loadLenient(folder, bundled(), log.logger, "en", true);
        log.records.clear();

        assertThat(lenient.addMissingKeys()).isEmpty();
        assertThat(Files.readAllBytes(english)).isEqualTo(legacy);
        assertThat(log.lines()).containsExactly(
            "WARNING lang/en.yml is not valid YAML saved as UTF-8, so no key was added to it.");
    }

    @Test
    void addMissingKeysLeavesAFileWhoseKeysDoNotAllowTheNewOnes() throws IOException {
        Path english = Files.createDirectories(folder.resolve("lang")).resolve("en.yml");
        Files.writeString(english, "parcel: \"text where the bundled file has a section\"\n");
        Messages messages = Messages.load(folder, bundled(), log.logger);
        log.records.clear();

        assertThat(messages.addMissingKeys()).isEmpty();

        assertThat(english).hasContent("parcel: \"text where the bundled file has a section\"\n");
        assertThat(log.lines()).containsExactly("WARNING lang/en.yml was not changed, because prefix, parcel.delivered,"
            + " parcel.held cannot be added at its end.");
        assertThat(messages.template(null, "parcel")).isEqualTo("text where the bundled file has a section");
    }

    @Test
    void addMissingKeysOfAnAbsentFileWritesNothing() throws IOException {
        Files.writeString(folder.resolve("lang"), "a file where the folder belongs");
        Messages messages = Messages.load(folder, bundled(), log.logger);
        log.records.clear();

        assertThat(messages.addMissingKeys()).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void bundledLanguagesAreWrittenAndBackTheOwnersFileOfTheSameCode() throws IOException {
        Path locales = Files.createDirectories(folder.resolve("locales"));
        Files.writeString(locales.resolve("de.yml"), "parcel.held: \"Meins: <count>.\"\n");
        Files.writeString(locales.resolve("de_AT.yml"), "prefix: \"[AT] \"\n");
        Files.writeString(locales.resolve("fr.yml"), "parcel.held: \"Tenu: <count>.\"\n");
        Map<String, String> jar = Map.of("en", BUNDLED, "de", GERMAN, "pt_BR", "parcel.held: \"Retido: <count>.\"\n");
        List<String> opened = new ArrayList<>();
        Function<String, InputStream> opener = code -> {
            opened.add(code);
            return jar.containsKey(code) ? supplier(jar.get(code)).get() : null;
        };

        Messages messages =
            Messages.load(folder, "locales", List.of("de", "EN", "pt_BR"), opener, log.logger, "", true);

        assertThat(opened).contains("en", "de", "pt_BR").doesNotContain("EN", "pt_br");
        assertThat(locales.resolve("en.yml")).hasContent(BUNDLED);
        assertThat(locales.resolve("pt_BR.yml")).hasContent(jar.get("pt_BR"));
        assertThat(locales.resolve("de.yml")).hasContent("parcel.held: \"Meins: <count>.\"\n");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de", "de_at", "fr", "pt_br");
        assertThat(plain(messages, Locale.GERMANY, "parcel.held")).isEqualTo("Meins: 7.");
        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[D] Geliefert f\u00fcr 7 \u20ac.");
        assertThat(plain(messages, Locale.of("de", "AT"), "parcel.delivered"))
            .isEqualTo("[AT] Geliefert f\u00fcr 7 \u20ac.");
        assertThat(plain(messages, Locale.of("pt", "BR"), "parcel.held")).isEqualTo("Retido: 7.");
        assertThat(plain(messages, Locale.of("pt", "BR"), "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(plain(messages, Locale.FRANCE, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(log.lines()).containsExactlyInAnyOrder(
            "WARNING locales/fr.yml lacks parcel.delivered, prefix; those messages are shown in another language.",
            "WARNING locales/pt_BR.yml lacks parcel.delivered, prefix; those messages are shown in another"
                + " language.");

        log.records.clear();
        Files.delete(locales.resolve("pt_BR.yml"));
        Files.delete(locales.resolve("de.yml"));
        Files.writeString(locales.resolve("en.yml"), "prefix: \"[E] \"\n");
        Files.writeString(locales.resolve("fr.yml"), "prefix: \"[F] \"\n");
        messages.reload();

        assertThat(locales.resolve("pt_BR.yml")).hasContent(jar.get("pt_BR"));
        assertThat(locales.resolve("de.yml")).hasContent(GERMAN);
        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Held: 7.");
        assertThat(log.lines()).containsExactly(
            "WARNING locales/fr.yml lacks parcel.delivered, parcel.held; those messages are shown in another"
                + " language.");

        log.records.clear();
        messages.reload();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aBundledLanguageIsServedBeforeTheDefaultLanguageAndWithoutItsFile() throws IOException {
        Files.writeString(folder.resolve("lang"), "a file where the folder belongs");
        Map<String, String> jar = Map.of("en", BUNDLED, "de", GERMAN, "fr", "parcel.held: \"Tenu: <count>.\"\n");

        Messages messages = Messages.load(
            folder, "lang", List.of("de", "fr"), code -> supplier(jar.get(code)).get(), log.logger, "de", true);

        assertThat(log.lines()).hasSize(1).first().asString()
            .startsWith("WARNING Could not write lang/en.yml: ").endsWith(". Messages use the bundled English text.");
        assertThat(messages.loadedLanguages()).containsExactlyInAnyOrder("en", "de", "fr");
        assertThat(plain(messages, Locale.FRANCE, "parcel.held")).isEqualTo("Tenu: 7.");
        assertThat(plain(messages, Locale.FRANCE, "parcel.delivered")).isEqualTo("[D] Geliefert f\u00fcr 7 \u20ac.");
        assertThat(plain(messages, Locale.UK, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(plain(messages, Locale.JAPAN, "parcel.held")).isEqualTo("Gehalten: 7.");
    }

    @Test
    void aBundledLanguageFileThatIsMissingOrNamedTwiceStopsTheLoad() {
        Function<String, InputStream> english = code -> code.equals("en") ? bundled().get() : null;

        assertThatThrownBy(() -> Messages.load(folder, "lang", List.of("de"), english, log.logger, "en", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the bundled de.yml is missing or unreadable");
        assertThatThrownBy(
            () -> Messages.load(folder, "lang", List.of("pt_BR", "pt-br"), english, log.logger, "en", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("two bundled locales read as pt_br");
        assertThatThrownBy(() -> Messages.load(folder, "lang", List.of("../de"), english, log.logger, "en", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the bundled locale '../de' is not one path segment");
        assertThat(folder).isEmptyDirectory();
    }

    @Test
    void addMissingKeysOfALocaleTakesTheTextOfThatLanguageOnly() throws IOException {
        Path locales = Files.createDirectories(folder.resolve("locales"));
        Files.writeString(locales.resolve("de.yml"), "# meins\nparcel.held: \"Meins: <count>.\"\n");
        Files.writeString(locales.resolve("fr.yml"), "parcel.held: \"Tenu: <count>.\"\n");
        Map<String, String> jar = Map.of("en", BUNDLED, "de", "parcel.held: \"Gehalten\"\nprefix: \"[D] \"\n");
        Messages messages = Messages.load(
            folder, "locales", List.of("de"), code -> supplier(jar.get(code)).get(), log.logger, "en", true);
        log.records.clear();

        assertThat(messages.addMissingKeys("DE")).containsExactly("prefix");
        assertThat(messages.addMissingKeys("fr")).isEmpty();
        assertThat(messages.addMissingKeys()).isEmpty();

        assertThat(locales.resolve("de.yml"))
            .hasContent("# meins\nparcel.held: \"Meins: <count>.\"\nprefix: \"[D] \"\n");
        assertThat(locales.resolve("fr.yml")).hasContent("parcel.held: \"Tenu: <count>.\"\n");
        assertThat(plain(messages, Locale.GERMANY, "parcel.delivered")).isEqualTo("[D] Delivered: 7.");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void sendRendersTheKeyForTheSenderAndSkipsAnEmptyMessage() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[P] "
            hello: "<prefix>Hello <name> %rank%"
            pages:
              - "<red>one"
              - "two {name}"
            quiet: ""
            none: []
            """), log.logger);
        Text text = new Text(log.logger, pass, null);
        Player player = viewer(Locale.ENGLISH);
        CommandSender console = mock(CommandSender.class);

        messages.send(text, player, "hello", Placeholder.unparsed("name", "<red>{name} %rank%"));
        messages.send(text, console, "hello", Placeholder.unparsed("name", "Ann"));
        messages.send(text, player, "pages", Placeholder.unparsed("name", "Ann"));
        messages.send(text, player, "quiet");
        messages.send(text, player, "none");
        messages.send(text, console, "absent");

        ArgumentCaptor<Component> toPlayer = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.times(2)).sendMessage(toPlayer.capture());
        assertThat(toPlayer.getAllValues()).extracting(MessagesTest::plain)
            .containsExactly("[P] Hello <red>{name} %rank% VIP", "one\ntwo Ann");
        ArgumentCaptor<Component> toConsole = ArgumentCaptor.forClass(Component.class);
        verify(console, org.mockito.Mockito.times(2)).sendMessage(toConsole.capture());
        assertThat(toConsole.getAllValues()).extracting(MessagesTest::plain)
            .containsExactly("[P] Hello Ann %rank%", "missing:absent");
    }

    @Test
    void aChatPrefixFromThePluginStandsInFrontOfChatSendsOnly() {
        Messages plain = Messages.load(folder, supplier("""
            prefix: "[P] "
            hello: "<prefix>Hello <name>"
            pages:
              - "one"
              - "two"
            quiet: ""
            """), log.logger);
        String[] configured = {"<gold>[%tag%]</gold> <gray>"};
        Messages messages = plain.withChatPrefix(() -> configured[0]);
        Text text = new Text(log.logger, pass, null);
        Player player = viewer(Locale.ENGLISH);

        messages.send(text, player, "hello", Placeholder.unparsed("name", "<red>Ann"));
        messages.send(text, player, "pages");
        messages.send(text, player, "quiet");
        configured[0] = "";
        messages.send(text, player, "hello", Placeholder.unparsed("name", "Bo"));
        configured[0] = null;
        messages.send(text, player, "hello", Placeholder.unparsed("name", "Cy"));
        plain.send(text, player, "hello", Placeholder.unparsed("name", "Di"));
        messages.sendActionBar(text, player, "hello", Placeholder.unparsed("name", "Ed"));

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.times(5)).sendMessage(sent.capture());
        assertThat(sent.getAllValues()).extracting(MessagesTest::plain).containsExactly(
            "[DE] [P] Hello <red>Ann", "[DE] one\ntwo", "[P] Hello Bo", "[P] Hello Cy", "[P] Hello Di");
        assertThat(sent.getAllValues().getFirst()).isEqualTo(new Text(log.logger, null, null).render(
            "<gold>[DE]</gold> <gray>[P] Hello <name>", (Player) null, Placeholder.unparsed("name", "<red>Ann")));
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("[P] Hello Ed");
        assertThat(plain(messages.render(text, player, "hello", Placeholder.unparsed("name", "Flo"))))
            .isEqualTo("[P] Hello Flo");
        assertThat(plain(messages.render(Locale.ENGLISH, "hello", Placeholder.unparsed("name", "Gus"))))
            .isEqualTo("[P] Hello Gus");
    }

    @Test
    void aDerivedMessagesSharesTheFilesOfTheOneItCameFrom() throws IOException {
        Messages messages = Messages.load(folder, bundled(), log.logger);
        Messages prefixed = messages.withChatPrefix(() -> "> ").withBracedNamesIgnoringCase();

        write("en.yml", "parcel.held: \"Kept {Count}\"\n");
        prefixed.reload();

        assertThat(plain(messages, Locale.ENGLISH, "parcel.held")).isEqualTo("Kept {Count}");
        assertThat(plain(prefixed, Locale.ENGLISH, "parcel.held")).isEqualTo("Kept 7");
        assertThat(messages.addMissingKeys()).containsExactly("prefix", "parcel.delivered");
        assertThat(plain(prefixed, Locale.ENGLISH, "parcel.delivered")).isEqualTo("[P] Delivered: 7.");
        assertThat(prefixed.defaultLocale()).isEqualTo("en");
        assertThat(prefixed.withBracedNamesIgnoringCase()).isSameAs(prefixed);
    }

    @Test
    void anActionBarAndATitleAreShownByKey() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[P] "
            bar: "<prefix>Bound to <name> %rank%"
            win.title: "<gold>You won"
            win.subtitle: "{prize} %rank%"
            quiet: ""
            """), log.logger);
        Text text = new Text(log.logger, pass, null);
        Player player = viewer(Locale.ENGLISH);
        Title.Times times = Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(15));

        messages.sendActionBar(text, player, "bar", Placeholder.unparsed("name", "<b>Ann"));
        messages.sendActionBar(text, player, "quiet");
        messages.showTitle(text, player, "win.title", "win.subtitle", times, Placeholder.unparsed("prize", "{prize}"));
        messages.showTitle(text, player, "win.title", null, times);
        messages.showTitle(text, player, "quiet", "quiet", times);
        messages.showTitle(text, player, "quiet", null, times);

        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(bar.capture());
        assertThat(plain(bar.getValue())).isEqualTo("[P] Bound to <b>Ann VIP");
        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player, org.mockito.Mockito.times(2)).showTitle(shown.capture());
        Title first = shown.getAllValues().getFirst();
        assertThat(first.title()).isEqualTo(MINI.deserialize("<gold>You won"));
        assertThat(plain(first.subtitle())).isEqualTo("{prize} VIP");
        assertThat(first.times()).isEqualTo(times);
        assertThat(shown.getAllValues().getLast().subtitle()).isEqualTo(Component.empty());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void aTitleEntryReadsItsTimesAsTicksFromTheLangFile() {
        Messages messages = Messages.load(folder, supplier("""
            win:
              title: "<gold>You won {prizeName}"
              subtitle: "<gray>Enjoy"
              fadeIn: 5
              stay: 40
              fadeOut: 15
            lose:
              title: "Lost"
              fadeIn: soon
              stay: -4
              fadeOut: 1.5
            """), log.logger).withBracedNamesIgnoringCase();
        Text text = new Text(log.logger, null, null);
        Player player = viewer(Locale.ENGLISH);

        messages.showTitle(text, player, "win", Placeholder.unparsed("prizename", "<b>Gem"));
        messages.showTitle(text, player, "lose");
        messages.showTitle(text, player, "absent");

        ArgumentCaptor<Title> shown = ArgumentCaptor.forClass(Title.class);
        verify(player, org.mockito.Mockito.times(3)).showTitle(shown.capture());
        Title win = shown.getAllValues().getFirst();
        assertThat(plain(win.title())).isEqualTo("You won <b>Gem");
        assertThat(plain(win.subtitle())).isEqualTo("Enjoy");
        assertThat(win.times())
            .isEqualTo(Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(15)));
        Title lose = shown.getAllValues().get(1);
        assertThat(lose.subtitle()).isEqualTo(Component.empty());
        assertThat(lose.times()).isEqualTo(Title.DEFAULT_TIMES);
        assertThat(plain(shown.getAllValues().getLast().title())).isEqualTo("missing:absent.title");
    }

    @Test
    void camelCaseNamesStayAsWrittenUnlessTheMessagesIgnoreCase() {
        Messages messages = Messages.load(folder, supplier("""
            prefix: "[{worldName}] "
            open: "{prefix}{crateName} for {playerName}, {keyCount} of {maxKeys}"
            lore:
              - "{crateName}"
              - "{cratename}"
            """), log.logger);
        Messages ignoring = messages.withBracedNamesIgnoringCase();
        TagResolver[] values = {
            Placeholder.unparsed("cratename", "Vote"), Placeholder.unparsed("playername", "<red>{crateName}"),
            Placeholder.unparsed("keycount", "3"), Placeholder.unparsed("worldname", "nether")};
        Text text = new Text(log.logger, pass, null);
        Player player = viewer(Locale.ENGLISH);

        String written = "[{worldName}] {crateName} for {playerName}, {keyCount} of {maxKeys}";
        assertThat(plain(messages.render(Locale.ENGLISH, "open", values))).isEqualTo(written);
        assertThat(plain(messages.render(text, player, "open", values))).isEqualTo(written);
        assertThat(messages.lines(null, "lore", values)).extracting(MessagesTest::plain)
            .containsExactly("{crateName}", "Vote");

        String filled = "[nether] Vote for <red>{crateName}, 3 of {maxKeys}";
        assertThat(plain(ignoring.render(Locale.ENGLISH, "open", values))).isEqualTo(filled);
        assertThat(plain(ignoring.render(text, player, "open", values))).isEqualTo(filled);
        assertThat(plain(ignoring.render(text.withBracedNamesIgnoringCase(), null, "open", values))).isEqualTo(filled);
        assertThat(plain(messages.render(text.withBracedNamesIgnoringCase(), player, "open", values)))
            .isEqualTo(filled);
        assertThat(plain(ignoring.renderTemplate(text, player, "{Prefix}{CrateName}", values)))
            .isEqualTo("[nether] Vote");
        assertThat(ignoring.lines(null, "lore", values)).extracting(MessagesTest::plain)
            .containsExactly("Vote", "Vote");
        assertThat(ignoring.template(null, "lore")).isEqualTo("{crateName}\n{cratename}");
    }

    @Test
    void messagesThatIgnoreCaseRenderEveryLowerCaseTemplateAsTheOriginalDoes() {
        Messages messages = Messages.load(folder, listed(), log.logger);
        Messages ignoring = messages.withBracedNamesIgnoringCase();
        Text text = new Text(log.logger, pass, null);
        Player player = viewer(Locale.ENGLISH);
        TagResolver[] values = {Placeholder.unparsed("count", "<red>{count}"), Placeholder.parsed("x", "\u00a7cx")};

        for (String key : List.of("lore", "single", "prefix", "absent")) {
            assertThat(ignoring.render(Locale.ENGLISH, key, values))
                .isEqualTo(messages.render(Locale.ENGLISH, key, values));
            assertThat(ignoring.lines(Locale.ENGLISH, key, values))
                .isEqualTo(messages.lines(Locale.ENGLISH, key, values));
            assertThat(ignoring.render(text, player, key, values))
                .isEqualTo(messages.render(text, player, key, values));
        }
        assertThat(ignoring.renderTemplate(text, player, "{prefix}{count} {x} {none} %rank%", values))
            .isEqualTo(messages.renderTemplate(text, player, "{prefix}{count} {x} {none} %rank%", values));
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

    private void writeWindows1252(String name, String content) throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.write(folder.resolve("lang").resolve(name), content.getBytes(Charset.forName("windows-1252")));
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
