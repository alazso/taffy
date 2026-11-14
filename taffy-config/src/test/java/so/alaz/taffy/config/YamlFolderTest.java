package so.alaz.taffy.config;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.FakeFolders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class YamlFolderTest {

    /** The title of the file; a file without one is not a usable entry. */
    private static final YamlFolder.Parser<String> TITLE = (id, file, yaml) -> {
        String title = yaml.getString("title");
        if (title == null) {
            throw new ConfigLoadException("title is missing");
        }
        return title;
    };

    @TempDir
    private Path root;

    private Path folder() throws IOException {
        return Files.createDirectories(root.resolve("menus"));
    }

    private Path write(String name, String text) throws IOException {
        return Files.writeString(folder().resolve(name), text);
    }

    @Test
    void anAbsentFolderGivesAnEmptyResult() throws Exception {
        YamlFolder.Loaded<String> loaded = new YamlFolder(root.resolve("menus")).load(TITLE);

        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.errors()).isEmpty();
        assertThat(root.resolve("menus")).doesNotExist();
    }

    @Test
    void aFileWhereTheFolderBelongsGivesAnEmptyResult() throws Exception {
        Path file = Files.writeString(root.resolve("menus"), "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(file).load(TITLE);

        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void filesLoadInTheOrderOfTheirNames() throws Exception {
        write("shop.yml", "title: Shop\n");
        write("bank.yml", "title: Bank\n");
        write("arena.yml", "title: Arena\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsExactly(
            Map.entry("arena", "Arena"), Map.entry("bank", "Bank"), Map.entry("shop", "Shop"));
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void theParserGetsTheIdTheFileNameAndTheParsedFile() throws Exception {
        write("Main-Menu.yml", "rows: 6\n");
        List<String> seen = new ArrayList<>();

        new YamlFolder(folder()).load((id, file, yaml) -> {
            seen.add(id + " " + file + " " + yaml.getInt("rows"));
            return id;
        });

        assertThat(seen).containsExactly("Main-Menu Main-Menu.yml 6");
    }

    @Test
    void aFileInASubfolderIsNotRead() throws Exception {
        write("shop.yml", "title: Shop\n");
        Path nested = Files.createDirectories(folder().resolve("old"));
        Files.writeString(nested.resolve("bank.yml"), "title: Bank\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("shop");
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void anUpperCaseSuffixCountsAndTheIdKeepsItsLetterCase() throws Exception {
        write("Kit.YML", "title: Kit\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsExactly(Map.entry("Kit", "Kit"));
    }

    @Test
    void namesWithAnotherEndingAreIgnored() throws Exception {
        write("shop.yml.bak", "title: Backup\n");
        write("shop.yml.tmp", "title: Partial\n");
        write("bank.yaml", "title: Bank\n");
        write("notes.txt", "not yaml: [\n");
        write("yml", "title: Bare\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void aNameThatIsOnlyTheSuffixIsIgnored() throws Exception {
        write(".yml", "title: Untitled\n");
        write(".YML", "title: Untitled\n");
        write("shop.yml", "title: Shop\n");
        YamlFolder folder = new YamlFolder(folder());

        YamlFolder.Loaded<String> loaded = folder.load(TITLE);

        assertThat(loaded.entries()).containsExactly(Map.entry("shop", "Shop"));
        assertThat(loaded.errors()).isEmpty();
        assertThat(folder.loadOne("", TITLE)).isNull();
    }

    @Test
    void aParserThatReturnsNullIsReportedAndTheOtherFilesLoad() throws Exception {
        write("bank.yml", "title: Bank\n");
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder())
            .load((id, file, yaml) -> id.equals("bank") ? null : id);

        assertThat(loaded.entries()).containsExactly(Map.entry("shop", "shop"));
        assertThat(loaded.errors()).containsExactly("bank.yml: NullPointerException the parser returned null");
    }

    @Test
    void malformedYamlIsReportedWithItsReasonAndTheOtherFilesLoad() throws Exception {
        write("arena.yml", "title: Arena\n");
        write("bank.yml", "title: [Bank\n");
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("arena", "shop");
        assertThat(loaded.errors()).singleElement(InstanceOfAssertFactories.STRING)
            .startsWith("bank.yml is not valid YAML: ")
            .hasSizeGreaterThan("bank.yml is not valid YAML: ".length());
    }

    @Test
    void aFileNotSavedAsUtf8IsReported() throws Exception {
        Files.write(folder().resolve("bank.yml"), "title: Café\n".getBytes(StandardCharsets.ISO_8859_1));
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("shop");
        assertThat(loaded.errors()).containsExactly("bank.yml is not saved as UTF-8");
    }

    @Test
    void aFolderNamedLikeAFileIsReportedAsUnreadable() throws Exception {
        Path named = Files.createDirectories(folder().resolve("bank.yml"));
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("shop");
        assertThat(loaded.errors()).singleElement(InstanceOfAssertFactories.STRING)
            .startsWith("cannot read " + named + ": ");
    }

    @Test
    void aParserFailureGivesTheFileNameAndItsMessage() throws Exception {
        write("bank.yml", "rows: 6\n");
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("shop");
        assertThat(loaded.errors()).containsExactly("bank.yml: title is missing");
    }

    @Test
    void anUncheckedParserFailureGivesItsClassNameAndMessage() throws Exception {
        write("bank.yml", "title: Bank\n");
        write("shop.yml", "title: Shop\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load((id, file, yaml) -> {
            if (id.equals("bank")) {
                throw new IllegalStateException("the menu is locked");
            }
            return id;
        });

        assertThat(loaded.entries()).containsOnlyKeys("shop");
        assertThat(loaded.errors()).containsExactly("bank.yml: IllegalStateException the menu is locked");
    }

    @Test
    void everyFailingFileIsReportedInTheOrderOfTheNames() throws Exception {
        write("c.yml", "rows: 6\n");
        write("a.yml", "title: [\n");
        write("b.yml", "title: B\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsOnlyKeys("b");
        assertThat(loaded.errors()).hasSize(2);
        assertThat(loaded.errors().get(0)).startsWith("a.yml is not valid YAML: ");
        assertThat(loaded.errors().get(1)).isEqualTo("c.yml: title is missing");
    }

    @Test
    void aFolderThatCannotBeListedThrows() {
        Path folder = FakeFolders.unlistable(null);

        assertThatThrownBy(() -> new YamlFolder(folder).load(TITLE))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read the folder " + folder + ": IOException access denied")
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aFolderThatFailsWhileItIsListedThrowsBeforeAnyFileIsRead() throws Exception {
        Path shop = write("shop.yml", "title: Shop\n");
        List<String> parsed = new ArrayList<>();
        Path folder = FakeFolders.unlistable(stream(List.of(shop), new IOException("device error")));

        assertThatThrownBy(() -> new YamlFolder(folder).load((id, file, yaml) -> {
            parsed.add(id);
            return id;
        }))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read the folder " + folder + ": IOException device error")
            .cause().hasMessage("device error");
        assertThat(parsed).isEmpty();
    }

    @Test
    void twoNamesWithTheSameIdKeepTheEntryReadLater() throws Exception {
        Path upper = Files.createDirectories(root.resolve("upper"));
        Path lower = Files.createDirectories(root.resolve("lower"));
        Path first = Files.writeString(upper.resolve("kit.YML"), "title: Upper\n");
        Path second = Files.writeString(lower.resolve("kit.yml"), "title: Lower\n");
        Path folder = FakeFolders.unlistable(stream(List.of(second, first), null));

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder).load(TITLE);

        assertThat(loaded.entries()).containsExactly(Map.entry("kit", "Lower"));
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void theResultIsAnUnmodifiableCopy() throws Exception {
        write("shop.yml", "title: Shop\n");
        write("bank.yml", "rows: 6\n");
        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThatThrownBy(() -> loaded.entries().put("arena", "Arena"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.errors().add("arena.yml: broken"))
            .isInstanceOf(UnsupportedOperationException.class);

        Map<String, String> entries = new LinkedHashMap<>(Map.of("shop", "Shop"));
        List<String> errors = new ArrayList<>(List.of("bank.yml: broken"));
        YamlFolder.Loaded<String> copy = new YamlFolder.Loaded<>(entries, errors);
        entries.put("arena", "Arena");
        errors.clear();
        assertThat(copy.entries()).containsOnlyKeys("shop");
        assertThat(copy.errors()).containsExactly("bank.yml: broken");
    }

    @Test
    void loadOneReadsOnlyThatFile() throws Exception {
        write("shop.yml", "title: Shop\n");
        write("bank.yml", "title: [\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).loadOne("shop", TITLE);

        assertThat(loaded).isNotNull();
        assertThat(loaded.entries()).containsExactly(Map.entry("shop", "Shop"));
        assertThat(loaded.errors()).isEmpty();
    }

    @Test
    void loadOneReportsABadFileWithNoEntry() throws Exception {
        write("bank.yml", "rows: 6\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).loadOne("bank", TITLE);

        assertThat(loaded).isNotNull();
        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.errors()).containsExactly("bank.yml: title is missing");
    }

    @Test
    void loadOneOfAMissingFileIsNull() throws Exception {
        write("shop.yml", "title: Shop\n");

        assertThat(new YamlFolder(folder()).loadOne("bank", TITLE)).isNull();
        assertThat(new YamlFolder(root.resolve("absent")).loadOne("shop", TITLE)).isNull();
    }

    @Test
    void loadOneOfAFolderNamedLikeAFileIsNull() throws Exception {
        Files.createDirectories(folder().resolve("bank.yml"));

        assertThat(new YamlFolder(folder()).loadOne("bank", TITLE)).isNull();
    }

    @Test
    void loadOneOfAnIdThatIsNotAPlainFileNameIsNull() throws Exception {
        Files.writeString(root.resolve("config.yml"), "title: Outside\n");
        Path nested = Files.createDirectories(folder().resolve("old"));
        Files.writeString(nested.resolve("bank.yml"), "title: Bank\n");
        Files.writeString(folder().resolve("..yml"), "title: Dot\n");
        YamlFolder folder = new YamlFolder(folder());

        assertThat(folder.loadOne("../config", TITLE)).isNull();
        assertThat(folder.loadOne("old/bank", TITLE)).isNull();
        assertThat(folder.loadOne(".", TITLE)).isNull();
        assertThat(folder.loadOne("..", TITLE)).isNull();
        assertThat(folder.loadOne("bank\0", TITLE)).isNull();
    }

    @Test
    void aFileWithAnAnchorUsedInsideItselfIsReportedAndTheOtherFilesLoad() throws Exception {
        write("a.yml", "title: A\n");
        write("b.yml", "title: &loop\n  inner: *loop\n");
        write("c.yml", "title: C\n");

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(TITLE);

        assertThat(loaded.entries()).containsExactly(Map.entry("a", "A"), Map.entry("c", "C"));
        assertThat(loaded.errors())
            .containsExactly("b.yml is not valid YAML: an anchor is used inside the value it names");
    }

    /** A directory stream that gives {@code files}, then fails with {@code failure} when it is not {@code null}. */
    private static DirectoryStream<Path> stream(List<Path> files, @Nullable IOException failure) {
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                Iterator<Path> listed = files.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        if (!listed.hasNext() && failure != null) {
                            throw new DirectoryIteratorException(failure);
                        }
                        return listed.hasNext();
                    }

                    @Override
                    public Path next() {
                        return listed.next();
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }
}
