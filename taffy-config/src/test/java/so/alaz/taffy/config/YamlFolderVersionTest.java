package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class YamlFolderVersionTest {

    private static final String KEY = "crate-version";

    /** The file name and the reward of the file, which only the layout of version 2 has. */
    private static final YamlFolder.Parser<String> REWARD = (id, file, yaml) -> {
        String reward = yaml.getString("reward");
        if (reward == null) {
            throw new ConfigLoadException("reward is missing");
        }
        return file + "=" + reward;
    };

    private static final ConfigUpgrade TO_V2 = ConfigUpgrade.none().from(1, keys -> keys.move("prize", "reward"));

    private static final String OLD = """
        # A crate.
        crate-version: 1
        # What it gives.
        prize: DIAMOND
        """;

    @TempDir
    private Path root;

    private final CapturedLog log = new CapturedLog();

    private Path folder() throws IOException {
        return Files.createDirectories(root.resolve("crates"));
    }

    private Path write(String name, String text) throws IOException {
        Path file = folder().resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private YamlFolder versioned(boolean recursive) throws IOException {
        return new YamlFolder(folder(), log.logger, KEY, 2, TO_V2, recursive);
    }

    @Test
    void theReaderWithoutAVersionNeitherUpgradesNorWrites() throws Exception {
        Path file = write("gold.yml", OLD);

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder()).load(REWARD);

        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.errors()).containsExactly("gold.yml: reward is missing");
        assertThat(Files.readString(file)).isEqualTo(OLD);
        assertThat(names(folder())).containsExactly("gold.yml");
    }

    @Test
    void anOlderFileIsUpgradedBeforeTheParserSeesItAndSavedBesideACopy() throws Exception {
        byte[] old = OLD.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(folder().resolve("gold.yml"), old);

        YamlFolder.Loaded<String> loaded = versioned(false).load(REWARD);

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(Files.readString(file)).isEqualTo("""
            # A crate.
            crate-version: 2
            # What it gives.
            reward: DIAMOND
            """);
        assertThat(Files.readAllBytes(folder().resolve("gold.yml.v1.bak"))).isEqualTo(old);
        assertThat(names(folder())).containsExactly("gold.yml", "gold.yml.v1.bak");
        assertThat(log.lines()).containsExactly(
            "INFO Upgraded gold.yml from version 1 to 2; the previous file is kept as gold.yml.v1.bak.");
    }

    @Test
    void aFileOfTheCurrentVersionIsNeverWrittenAndASecondLoadRunsNoStep() throws Exception {
        Path file = write("gold.yml", OLD);
        List<String> ran = new ArrayList<>();
        YamlFolder folder = new YamlFolder(folder(), log.logger, KEY, 2,
            TO_V2.from(0, keys -> ran.add("0")).from(2, keys -> ran.add("2")), false);
        folder.load(REWARD);
        String saved = Files.readString(file);
        Files.delete(folder().resolve("gold.yml.v1.bak"));

        YamlFolder.Loaded<String> loaded = folder.load(REWARD);

        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(Files.readString(file)).isEqualTo(saved);
        assertThat(names(folder())).containsExactly("gold.yml");
        assertThat(ran).isEmpty();
        assertThat(log.lines()).hasSize(1);
    }

    @Test
    void aFileWithoutTheKeyCountsAsVersionZeroAndGetsTheKey() throws Exception {
        Path file = write("gold.yml", "prize: DIAMOND\n");
        ConfigUpgrade upgrades = ConfigUpgrade.none().from(0, keys -> keys.move("prize", "reward"));

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder(), log.logger, KEY, 1, upgrades, false).load(REWARD);

        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(Files.readString(file)).isEqualTo("reward: DIAMOND\ncrate-version: 1\n");
        assertThat(Files.readString(folder().resolve("gold.yml.v0.bak"))).isEqualTo("prize: DIAMOND\n");
    }

    @Test
    void aNewerFileIsNotParsedAndNeverChanged() throws Exception {
        String newer = "crate-version: \"3\"\nreward: DIAMOND\nprize: x\n";
        Path file = write("gold.yml", newer);
        write("iron.yml", "crate-version: 2\nreward: IRON\n");

        YamlFolder.Report<String> report = versioned(false).loadReport(
            (id, name, yaml) -> new YamlFolder.Parsed<>(REWARD.parse(id, name, yaml), List.of()));

        String line =
            "gold.yml: crate-version is 3, newer than the version 2 this build knows, so the file is not read";
        assertThat(report.entries()).containsExactly(Map.entry("iron", "iron.yml=IRON"));
        assertThat(report.errors()).containsExactly(line);
        assertThat(report.problems()).containsExactly(
            new ConfigProblem("gold.yml", null, ConfigProblem.Severity.ERROR, line, null));
        assertThat(Files.readString(file)).isEqualTo(newer);
        assertThat(names(folder())).containsExactly("gold.yml", "iron.yml");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aStepThatThrowsLeavesTheFileByteForByteAndTheOtherFilesLoad() throws Exception {
        Path file = write("gold.yml", OLD);
        write("iron.yml", "crate-version: 2\nreward: IRON\n");
        IllegalStateException bug = new IllegalStateException("no such prize");
        ConfigUpgrade upgrades = ConfigUpgrade.none().from(1, keys -> {
            keys.remove("prize");
            throw bug;
        });

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder(), log.logger, KEY, 2, upgrades, false).load(REWARD);

        assertThat(loaded.entries()).containsExactly(Map.entry("iron", "iron.yml=IRON"));
        assertThat(loaded.errors()).containsExactly(
            "gold.yml: the step that upgrades it from version 1 failed: IllegalStateException no such prize");
        assertThat(Files.readString(file)).isEqualTo(OLD);
        assertThat(names(folder())).containsExactly("gold.yml", "iron.yml");
        assertThat(log.records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage())
                .isEqualTo("The step that upgrades gold.yml from version 1 failed, so the file is not read.");
            assertThat(record.getThrown()).isSameAs(bug);
        });
    }

    @Test
    void anUpgradeThatCannotBeSavedLeavesTheFileAndStillLoadsTheUpgradedEntry() throws Exception {
        Path file = write("gold.yml", OLD);
        Path blockedBackup = Files.createDirectory(folder().resolve("gold.yml.v1.bak"));
        Path blocker = Files.writeString(blockedBackup.resolve("keep"), "x");
        YamlFolder folder = versioned(false);

        YamlFolder.Loaded<String> loaded = folder.load(REWARD);

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(Files.readString(file)).isEqualTo(OLD);
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Could not save the upgraded gold.yml: cannot write it: ")
            .endsWith(". The upgraded entry is used and the save is tried again the next time the file is loaded.");
        Files.delete(blocker);
        Files.delete(blockedBackup);

        folder.load(REWARD);

        assertThat(Files.readString(file)).contains("crate-version: 2");
        assertThat(Files.readString(blockedBackup)).isEqualTo(OLD);
    }

    @Test
    void anUpgradeDoesNotReplaceAFileThatChangedOnTheDiskWhileItWasRead() throws Exception {
        Path file = write("gold.yml", OLD);
        String edited = OLD.replace("DIAMOND", "EMERALD");
        ConfigUpgrade upgrades = ConfigUpgrade.none().from(1, keys -> {
            keys.move("prize", "reward");
            try {
                Files.writeString(file, edited);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });

        YamlFolder.Loaded<String> loaded = new YamlFolder(folder(), log.logger, KEY, 2, upgrades, false).load(REWARD);

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(Files.readString(file)).isEqualTo(edited);
        assertThat(names(folder())).containsExactly("gold.yml");
        assertThat(log.lines()).containsExactly("WARNING Could not save the upgraded gold.yml: the file changed"
            + " while it was read. The upgraded entry is used and the save is tried again the next time the file is"
            + " loaded.");
    }

    @Test
    void aVersionKeyBeneathAKeyThatHoldsAValueLeavesTheFileAndTheValue() throws Exception {
        String owned = "meta: owner text\nreward: DIAMOND\n";
        Path file = write("gold.yml", owned);
        YamlFolder folder = new YamlFolder(folder(), log.logger, "meta.version", 1, ConfigUpgrade.none(), false);

        YamlFolder.Loaded<String> loaded = folder.load((id, name, yaml) -> yaml.getString("meta"));

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "owner text"));
        assertThat(Files.readString(file)).isEqualTo(owned);
        assertThat(names(folder())).containsExactly("gold.yml");
        assertThat(log.lines()).containsExactly("WARNING Could not save the upgraded gold.yml: meta.version lies"
            + " beneath a key that holds a value, so the version cannot be written. The upgraded entry is used and"
            + " the save is tried again the next time the file is loaded.");
    }

    @Test
    void aLenientReaderDoesNotSaveAFileThatHoldsBytesThatAreNotUtf8() throws Exception {
        byte[] old = "# café\ncrate-version: 1\nprize: DIAMOND\n".getBytes(StandardCharsets.ISO_8859_1);
        Path file = Files.write(folder().resolve("gold.yml"), old);
        List<String> warnings = new ArrayList<>();

        YamlFolder.Loaded<String> loaded = versioned(false).lenient(warnings::add).load(REWARD);

        assertThat(loaded.entries()).containsExactly(Map.entry("gold", "gold.yml=DIAMOND"));
        assertThat(warnings).hasSize(1);
        assertThat(Files.readAllBytes(file)).isEqualTo(old);
        assertThat(names(folder())).containsExactly("gold.yml");
        assertThat(log.lines()).containsExactly("WARNING Could not save the upgraded gold.yml: it is not saved as"
            + " UTF-8. The upgraded entry is used and the save is tried again the next time the file is loaded.");
    }

    @Test
    void aRecursiveReaderReadsSubfoldersWithIdsFromTheRelativePath() throws Exception {
        write("zinc.yml", "crate-version: 2\nreward: ZINC\n");
        write("rare/gold.yml", OLD);
        write("rare/epic/star.yml", "crate-version: 2\nreward: STAR\n");
        write("rare/note.yaml", "crate-version: 2\n");
        write(".hidden/secret.yml", "crate-version: 2\nreward: NO\n");

        YamlFolder.Loaded<String> loaded = versioned(true).load(REWARD);

        assertThat(loaded.entries()).containsExactly(
            Map.entry("rare/epic/star", "rare/epic/star.yml=STAR"),
            Map.entry("rare/gold", "rare/gold.yml=DIAMOND"),
            Map.entry("zinc", "zinc.yml=ZINC"));
        assertThat(loaded.errors())
            .containsExactly("rare/note.yaml: rename it to rare/note.yml to have it read");
        assertThat(names(folder().resolve("rare"))).contains("gold.yml", "gold.yml.v1.bak");
        assertThat(log.lines()).containsExactly(
            "INFO Upgraded rare/gold.yml from version 1 to 2; the previous file is kept as gold.yml.v1.bak.");
    }

    @Test
    void withoutTheRecursiveScanSubfoldersAreNotRead() throws Exception {
        write("zinc.yml", "crate-version: 2\nreward: ZINC\n");
        Path nested = write("rare/gold.yml", OLD);
        YamlFolder folder = versioned(false);

        assertThat(folder.load(REWARD).entries()).containsOnlyKeys("zinc");
        assertThat(folder.loadOne("rare/gold", REWARD)).isNull();
        assertThat(Files.readString(nested)).isEqualTo(OLD);
    }

    @Test
    void loadOneOfARecursiveReaderTakesTheIdOfAFileInASubfolderAndUpgradesIt() throws Exception {
        Path nested = write("rare/gold.yml", OLD);
        write("zinc.yml", "crate-version: 2\nreward: ZINC\n");
        YamlFolder folder = versioned(true);

        YamlFolder.Loaded<String> one = folder.loadOne("rare/gold", REWARD);

        assertThat(one.entries()).containsExactly(Map.entry("rare/gold", "rare/gold.yml=DIAMOND"));
        assertThat(Files.readString(nested)).contains("crate-version: 2");
        assertThat(folder.loadOne("zinc", REWARD).entries()).containsOnlyKeys("zinc");
        assertThat(folder.loadOne("rare/../zinc", REWARD)).isNull();
        assertThat(folder.loadOne("rare//gold", REWARD)).isNull();
        assertThat(folder.loadOne("/zinc", REWARD)).isNull();
        assertThat(folder.loadOne("rare/", REWARD)).isNull();
        assertThat(folder.loadOne("rare/.gold", REWARD)).isNull();
        assertThat(folder.loadOne("rare/missing", REWARD)).isNull();
        assertThat(folder.loadOne("", REWARD)).isNull();
    }

    @Test
    void aBlankVersionKeyAndACurrentVersionBelowOneAreRefused() throws Exception {
        Path folder = folder();

        assertThatThrownBy(() -> new YamlFolder(folder, log.logger, " ", 1, ConfigUpgrade.none(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the version key is blank");
        assertThatThrownBy(() -> new YamlFolder(folder, log.logger, KEY, 0, ConfigUpgrade.none(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the current version is 0; expected 1 or more");
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
