package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ConfigUpgradeTest {

    private static final String SHIPPED_V2 = """
        config-version: 2

        # Seconds between two uses.
        cooldown-seconds: 30

        storage:
          type: sqlite
          mysql:
            host: localhost
        """;

    private static final String OWNER_V1 = """
        # My server.
        config-version: 1

        # Raised for the event.
        cooldown: 5 # was 30

        storage:
          # Our database.
          type: mysql
          # The box in the rack.
          host: db.example.net

        # Not used any more.
        legacy-mode: true
        my-key: 7
        """;

    private static final ConfigUpgrade TO_V2 = ConfigUpgrade.none().from(1, keys -> {
        keys.move("cooldown", "cooldown-seconds");
        keys.move("storage.host", "storage.mysql.host");
        keys.remove("legacy-mode");
    });

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();

    @Test
    void withoutAStepARenamedKeyGetsTheShippedDefaultAndTheOldKeyStays() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("cooldown-seconds")).isEqualTo(30);
        assertThat(loaded.getInt("cooldown")).isEqualTo(5);
    }

    @Test
    void aStepMovesTheOwnerValuesWithTheirCommentsBeforeTheShippedKeysAreAdded() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        YamlConfiguration loaded = loader(SHIPPED_V2).upgrades(TO_V2).load();

        assertThat(loaded.getInt("cooldown-seconds")).isEqualTo(5);
        assertThat(loaded.getString("storage.mysql.host")).isEqualTo("db.example.net");
        assertThat(loaded.getKeys(true)).doesNotContain("cooldown", "storage.host", "legacy-mode");
        assertThat(Files.readString(configFile())).isEqualTo("""
            # My server.
            config-version: 2

            storage:
              # Our database.
              type: mysql
              mysql:
                # The box in the rack.
                host: db.example.net
            my-key: 7

            # Raised for the event.
            cooldown-seconds: 5 # was 30
            """);
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
        assertThat(log.lines()).containsExactly(
            "INFO Upgraded config.yml from version 1 to 2; the previous file is kept as config.yml.v1.bak.");
    }

    @Test
    void aSecondLoadRunsNoStepAndWritesNothing() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        List<Integer> ran = new ArrayList<>();
        ConfigLoader loader = loader(SHIPPED_V2).upgrades(ConfigUpgrade.none().from(1, keys -> ran.add(1)));
        loader.load();
        String saved = Files.readString(configFile());

        loader.load();

        assertThat(ran).containsExactly(1);
        assertThat(Files.readString(configFile())).isEqualTo(saved);
        assertThat(log.lines()).hasSize(1);
    }

    @Test
    void theStepsFromTheVersionOfTheFileUpToTheShippedOneRunInAscendingOrder() throws Exception {
        Files.writeString(configFile(), "config-version: 2\n");
        List<Integer> ran = new ArrayList<>();
        ConfigUpgrade upgrades = ConfigUpgrade.none()
            .from(4, keys -> ran.add(4))
            .from(3, keys -> ran.add(3))
            .from(5, keys -> ran.add(5))
            .from(2, keys -> ran.add(2))
            .from(1, keys -> ran.add(1));

        loader("config-version: 5\n").upgrades(upgrades).load();

        assertThat(ran).containsExactly(2, 3, 4);
    }

    @Test
    void aFileWithoutAVersionRunsTheStepsFromTheBaselineVersion() throws Exception {
        Files.writeString(configFile(), "cooldown: 5\n");
        List<Integer> ran = new ArrayList<>();
        ConfigUpgrade upgrades = ConfigUpgrade.none().from(0, keys -> ran.add(0)).from(1, keys -> ran.add(1));

        loader("config-version: 2\n").upgrades(upgrades).baselineVersion(1).load();

        assertThat(ran).containsExactly(1);
    }

    @Test
    void aStepThatThrowsStopsTheLoadAndLeavesTheFileByteForByte() throws Exception {
        byte[] owner = OWNER_V1.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(configFile(), owner);
        IllegalStateException bug = new IllegalStateException("no such unit");
        ConfigUpgrade upgrades = ConfigUpgrade.none().from(1, keys -> {
            keys.remove("my-key");
            throw bug;
        });

        assertThatThrownBy(() -> loader(SHIPPED_V2).upgrades(upgrades).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("the step that upgrades config.yml from version 1 failed: IllegalStateException no such unit")
            .hasCause(bug);

        assertThat(Files.readAllBytes(configFile())).isEqualTo(owner);
        assertThat(names()).containsExactly("config.yml");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFailedSaveLeavesTheFileAndTheNextLoadRunsTheStepsAgain() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Path blockedBackup = Files.createDirectory(folder.resolve("config.yml.v1.bak"));
        Path blocker = Files.writeString(blockedBackup.resolve("keep"), "x");
        ConfigLoader loader = loader(SHIPPED_V2).upgrades(TO_V2);

        YamlConfiguration loaded = loader.load();

        assertThat(loaded.getInt("cooldown-seconds")).isEqualTo(5);
        assertThat(Files.readString(configFile())).isEqualTo(OWNER_V1);
        Files.delete(blocker);
        Files.delete(blockedBackup);

        loader.load();

        assertThat(Files.readString(configFile())).contains("cooldown-seconds: 5").doesNotContain("legacy-mode");
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
    }

    @Test
    void aNewerFileIsRefusedOnlyAfterRefuseNewerAndIsNeverChanged() throws Exception {
        String newer = "config-version: 3\ncooldown: 5\n";
        Files.writeString(configFile(), newer);

        assertThat(loader(SHIPPED_V2).upgrades(TO_V2).load().getInt("cooldown")).isEqualTo(5);
        assertThat(log.lines()).containsExactly("WARNING config.yml is version 3, newer than the version 2 this"
            + " build knows, so it is read as it is and nothing is changed.");

        assertThatThrownBy(() -> loader(SHIPPED_V2).upgrades(TO_V2).refuseNewer().load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is version 3, newer than the version 2 this build knows, so it is not read");
        assertThat(Files.readString(configFile())).isEqualTo(newer);
        assertThat(names()).containsExactly("config.yml");
        assertThat(log.lines()).hasSize(1);
    }

    @Test
    void refuseNewerStillLoadsAndUpgradesTheOtherFiles() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        assertThat(loader(SHIPPED_V2).refuseNewer().upgrades(TO_V2).load().getInt("config-version")).isEqualTo(2);
        assertThat(loader(SHIPPED_V2).refuseNewer().load().getInt("cooldown-seconds")).isEqualTo(5);
    }

    @Test
    void whatTheFileHoldsAtTheNewPathWinsAndTheOldKeyIsRemoved() throws Exception {
        YamlConfiguration yaml = StrictYaml.parse("""
            cooldown: 5
            cooldown-seconds: 9
            storage:
              host: old
              port: 3306
              mysql:
                # Kept.
                host: mine
            flat: 1
            nested: text
            """, "test.yml");
        ConfigUpgrade.Keys keys = new ConfigUpgrade.Keys(yaml);

        assertThat(keys.move("cooldown", "cooldown-seconds")).isTrue();
        assertThat(keys.move("storage.host", "storage.mysql.host")).isTrue();
        assertThat(keys.move("flat", "nested.flat")).isTrue();

        assertThat(yaml.saveToString()).isEqualTo("""
            cooldown-seconds: 9
            storage:
              port: 3306
              mysql:
                # Kept.
                host: mine
            nested: text
            """);
    }

    @Test
    void aSectionMovesWithItsKeysAndFillsOnlyTheKeysTheTargetLacks() throws Exception {
        YamlConfiguration yaml = StrictYaml.parse("""
            # The database.
            database:
              # Where it runs.
              host: db
              pool:
                size: 4
            storage:
              mysql:
                host: mine
            first: 1
            """, "test.yml");
        ConfigUpgrade.Keys keys = new ConfigUpgrade.Keys(yaml);

        assertThat(keys.move("database", "storage.mysql")).isTrue();
        assertThat(keys.move("storage", "storage.settings")).isTrue();

        assertThat(yaml.saveToString()).isEqualTo("""
            first: 1
            storage:
              settings:
                mysql:
                  host: mine
                  pool:
                    size: 4
            """);
    }

    @Test
    void aMoveOfAKeyTheFileLacksOrOntoItselfAndASecondRunChangeNothing() throws Exception {
        YamlConfiguration yaml = StrictYaml.parse("# Kept.\ncooldown: 5\nother: 1\n", "test.yml");
        ConfigUpgrade.Keys keys = new ConfigUpgrade.Keys(yaml);

        assertThat(keys.move("absent", "cooldown")).isFalse();
        assertThat(keys.move("cooldown", "cooldown")).isFalse();
        assertThat(keys.remove("absent")).isFalse();
        assertThat(yaml.saveToString()).isEqualTo("# Kept.\ncooldown: 5\nother: 1\n");

        assertThat(keys.move("cooldown", "cooldown-seconds")).isTrue();
        assertThat(keys.remove("other")).isTrue();
        String once = yaml.saveToString();
        assertThat(keys.move("cooldown", "cooldown-seconds")).isFalse();
        assertThat(keys.remove("other")).isFalse();

        assertThat(yaml.saveToString()).isEqualTo(once).isEqualTo("# Kept.\ncooldown-seconds: 5\n");
        assertThat(keys.yaml()).isSameAs(yaml);
    }

    @Test
    void aBlankPathANegativeVersionAndASecondStepOfOneVersionAreRefused() {
        ConfigUpgrade.Keys keys = new ConfigUpgrade.Keys(new YamlConfiguration());

        assertThatThrownBy(() -> keys.move(" ", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.move("a", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.remove("")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a path of a config upgrade is blank");
        assertThatThrownBy(() -> ConfigUpgrade.none().from(-1, step -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the version of a step is -1; expected 0 or more");
        ConfigUpgrade one = ConfigUpgrade.none().from(1, step -> { });
        assertThatThrownBy(() -> one.from(1, step -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("version 1 already has a step");
    }

    private ConfigLoader loader(String shippedText) {
        return new ConfigLoader(folder, () -> new ByteArrayInputStream(shippedText.getBytes(StandardCharsets.UTF_8)),
            log.logger);
    }

    private Path configFile() {
        return folder.resolve("config.yml");
    }

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
