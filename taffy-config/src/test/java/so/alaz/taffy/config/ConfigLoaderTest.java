package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ConfigLoaderTest {

    private static final String WROTE_DEFAULT =
        "INFO Wrote the default config.yml, so the default settings are in effect.";

    private static final String SHIPPED_V2 = """
        # Shipped header.

        # Layout version.
        config-version: 2

        # Switch modules off.
        modules:
          chat:
            enabled: true

        # Added in version 2.
        new-group:
          # The new flag.
          flag: true # inline note
        """;

    private static final String OWNER_V1 = """
        # Owner header kept on purpose.

        # Layout version.
        config-version: 1

        # Switch modules off.
        modules:
          chat:
            # The owner turned this off.
            enabled: false

        # A key the shipped file does not have.
        my-key: 7
        """;

    private static final String SHIPPED_KITS = """
        config-version: 2
        kits:
          default: starter
          starter:
            cooldown: 1h
            icon: GOLD_INGOT
        """;

    private static final Map<String, Set<String>> KITS_ARE_OWNER_KEYED = Map.of("kits", Set.of("default"));

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();

    @Test
    void aFreshInstallGetsTheShippedFileByteForByte() throws Exception {
        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(Files.readString(configFile())).isEqualTo(SHIPPED_V2);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(log.lines()).containsExactly(WROTE_DEFAULT);
    }

    @Test
    void anOlderFileGainsTheNewKeysWithTheirCommentsAndKeepsEverythingElse() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(loaded.getBoolean("modules.chat.enabled")).isFalse();
        assertThat(loaded.getInt("my-key")).isEqualTo(7);

        YamlConfiguration onDisk = reread();
        assertThat(onDisk.getInt("config-version")).isEqualTo(2);
        assertThat(onDisk.getBoolean("modules.chat.enabled")).isFalse();
        assertThat(onDisk.getInt("my-key")).isEqualTo(7);
        assertThat(onDisk.getComments("new-group")).containsExactly(null, "Added in version 2.");
        assertThat(onDisk.getComments("new-group.flag")).containsExactly("The new flag.");
        assertThat(onDisk.getInlineComments("new-group.flag")).containsExactly("inline note");
        assertThat(onDisk.getComments("modules.chat.enabled")).containsExactly("The owner turned this off.");
        assertThat(onDisk.getComments("my-key")).containsExactly(null, "A key the shipped file does not have.");
        assertThat(onDisk.options().getHeader()).containsExactly("Owner header kept on purpose.");
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
        assertThat(log.lines()).containsExactly("INFO Upgraded config.yml from version 1 to 2 and added"
            + " new-group.flag; the previous file is kept as config.yml.v1.bak.");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void anOlderFileThatLacksNoKeyGetsTheNewVersionOnly() throws Exception {
        String owner = SHIPPED_V2
            .replace("config-version: 2", "config-version: 1")
            .replace("flag: true", "flag: false");
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getBoolean("new-group.flag")).isFalse();
        assertThat(Files.readString(configFile())).isEqualTo(owner.replace("config-version: 1", "config-version: 2"));
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(owner);
        assertThat(log.lines()).containsExactly(
            "INFO Upgraded config.yml from version 1 to 2; the previous file is kept as config.yml.v1.bak.");
    }

    @Test
    void whatTheRewriteCannotKeepSurvivesInTheKeptCopy() throws Exception {
        String owner = """
            config-version: 1
            my-list:
              # keep bedrock out
              - BEDROCK # inline on an item
            my-empty:
            """;
        Files.writeString(configFile(), owner);

        loader(SHIPPED_V2).load();

        assertThat(reread().getStringList("my-list")).containsExactly("BEDROCK");
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(owner);
    }

    @Test
    void aFailedSaveKeepsTheOwnerFileAndRunsOnTheUpgradedSettings() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Path blockedBackup = Files.createDirectory(folder.resolve("config.yml.v1.bak"));
        Files.writeString(blockedBackup.resolve("keep"), "x");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(Files.readString(configFile())).isEqualTo(OWNER_V1);
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Could not save the upgraded config.yml: cannot copy it to config.yml.v1.bak: ")
            .endsWith(". The upgraded settings are used and the save is tried again the next time the settings are"
                + " loaded.");
    }

    @Test
    void anUpgradeThatWasNotSavedIsTriedAgainAtTheNextLoad() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Path blockedBackup = Files.createDirectory(folder.resolve("config.yml.v1.bak"));
        Path blocker = Files.writeString(blockedBackup.resolve("keep"), "x");
        ConfigLoader loader = loader(SHIPPED_V2);
        loader.load();
        Files.delete(blocker);
        Files.delete(blockedBackup);

        loader.load();

        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
        assertThat(log.lines()).hasSize(2);
        assertThat(log.lines().get(1)).isEqualTo("INFO Upgraded config.yml from version 1 to 2 and added"
            + " new-group.flag; the previous file is kept as config.yml.v1.bak.");
    }

    @Test
    void aQuotedVersionCountsAsItsNumber() throws Exception {
        Files.writeString(configFile(), "config-version: \"9\"\n");

        loader(SHIPPED_V2).load();

        assertThat(log.lines()).singleElement().asString().startsWith("WARNING config.yml is version 9");
        assertThat(ConfigLoader.version(" 3 ", 0)).isEqualTo(3);
        assertThat(ConfigLoader.version(2.0, 0)).isEqualTo(2);
        assertThat(ConfigLoader.version("two", 4)).isEqualTo(4);
        assertThat(ConfigLoader.version("12345678901", 4)).isEqualTo(4);
        assertThat(ConfigLoader.version(null, 0)).isZero();
    }

    @Test
    void aFileNotSavedAsUtf8StopsTheLoadAndSaysSo() throws Exception {
        String owner = "# café\nconfig-version: 1\n";
        Files.write(configFile(), owner.getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> loader(SHIPPED_V2).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not saved as UTF-8: the first byte that is not UTF-8 is on line 1");
    }

    @Test
    void aTagPaperCannotBuildStopsTheLoadAsInvalidYaml() throws Exception {
        Files.writeString(configFile(), "config-version: 1\nprefix: !Custom value\n");

        assertThatThrownBy(() -> loader(SHIPPED_V2).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ");
    }

    @Test
    void anUpgradeWritesThroughASymbolicLink() throws Exception {
        Path shared = Files.createDirectory(folder.resolve("shared")).resolve("config.yml");
        Files.writeString(shared, OWNER_V1);
        try {
            Files.createSymbolicLink(configFile(), shared);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "this file system cannot create symbolic links");
        }

        loader(SHIPPED_V2).load();

        assertThat(Files.isSymbolicLink(configFile())).isTrue();
        assertThat(Files.readString(shared)).contains("config-version: 2");
    }

    @Test
    void aLeftoverTemporaryFileIsRemovedByALoadOfACurrentFile() throws Exception {
        Files.writeString(configFile(), SHIPPED_V2);
        Files.writeString(temporaryFile(), "junk: [");

        loader(SHIPPED_V2).load();

        assertThat(temporaryFile()).doesNotExist();
        assertThat(Files.readString(configFile())).isEqualTo(SHIPPED_V2);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void anUpgradeWithALeftoverTemporaryFileSavesAndLeavesNoTemporaryFile() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Files.writeString(temporaryFile(), "junk: [");

        loader(SHIPPED_V2).load();

        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(reread().getInt("my-key")).isEqualTo(7);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aLeftoverThatCannotBeRemovedStillLetsACurrentFileLoad() throws Exception {
        Files.writeString(configFile(), SHIPPED_V2);
        Path inside = Files.writeString(Files.createDirectory(temporaryFile()).resolve("inside.txt"), "kept");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(Files.readString(inside)).isEqualTo("kept");
        assertThat(Files.readString(configFile())).isEqualTo(SHIPPED_V2);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aLeftoverThatCannotBeRemovedStopsTheSaveOfAnUpgradeAndKeepsTheFile() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Path inside = Files.writeString(Files.createDirectory(temporaryFile()).resolve("inside.txt"), "kept");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(Files.readString(configFile())).isEqualTo(OWNER_V1);
        assertThat(Files.readString(inside)).isEqualTo("kept");
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Could not save the upgraded config.yml: cannot write ");
    }

    @Test
    void aTemporaryFileThatAppearsAfterTheLoadStartsIsReplacedByTheSave() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Supplier<InputStream> planting = () -> {
            plant(() -> Files.writeString(temporaryFile(), "junk: ["));
            return new ByteArrayInputStream(SHIPPED_V2.getBytes(StandardCharsets.UTF_8));
        };

        new ConfigLoader(folder, planting, log.logger).load();

        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(log.lines()).singleElement().asString().startsWith("INFO Upgraded config.yml from version 1 to 2");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aLeftoverTemporaryLinkNeverChangesTheFileItLeadsTo() throws Exception {
        Path other = Files.writeString(folder.resolve("other.txt"), "untouched");
        Files.writeString(configFile(), OWNER_V1);
        link(temporaryFile(), other);

        loader(SHIPPED_V2).load();

        assertThat(Files.readString(other)).isEqualTo("untouched");
        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(Files.exists(temporaryFile(), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aTemporaryLinkThatAppearsAfterTheLoadStartsNeverChangesTheFileItLeadsTo() throws Exception {
        Path other = Files.writeString(folder.resolve("other.txt"), "untouched");
        Files.writeString(configFile(), OWNER_V1);
        link(folder.resolve("probe.link"), other);
        Files.delete(folder.resolve("probe.link"));
        Supplier<InputStream> planting = () -> {
            plant(() -> Files.createSymbolicLink(temporaryFile(), other));
            return new ByteArrayInputStream(SHIPPED_V2.getBytes(StandardCharsets.UTF_8));
        };

        new ConfigLoader(folder, planting, log.logger).load();

        assertThat(Files.readString(other)).isEqualTo("untouched");
        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(Files.exists(temporaryFile(), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aLinkedFileIsWrittenAndCleanedInTheFolderOfTheFileItLeadsTo() throws Exception {
        Path shared = Files.createDirectory(folder.resolve("shared"));
        Path real = Files.writeString(shared.resolve("real.yml"), OWNER_V1);
        Path leftover = Files.writeString(shared.resolve("real.yml.tmp"), "junk: [");
        link(configFile(), real);

        loader(SHIPPED_V2).load();

        assertThat(leftover).doesNotExist();
        assertThat(Files.isSymbolicLink(configFile())).isTrue();
        assertThat(Files.readString(real)).contains("config-version: 2");
        assertThat(leftovers()).isEmpty();
        try (Stream<Path> files = Files.list(shared)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .containsExactly("real.yml");
        }
    }

    @Test
    void aLinkedFileLeftoverIsRemovedByALoadOfACurrentFile() throws Exception {
        Path shared = Files.createDirectory(folder.resolve("shared"));
        Path real = Files.writeString(shared.resolve("real.yml"), SHIPPED_V2);
        Path leftover = Files.writeString(shared.resolve("real.yml.tmp"), "junk: [");
        link(configFile(), real);

        loader(SHIPPED_V2).load();

        assertThat(leftover).doesNotExist();
        assertThat(Files.readString(real)).isEqualTo(SHIPPED_V2);
    }

    @Test
    void anUpgradeKeepsTheFilePermissions() throws Exception {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
            "this file system has no POSIX permissions");
        Files.writeString(configFile(), OWNER_V1);
        Set<PosixFilePermission> chosen = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(configFile(), chosen);

        loader(SHIPPED_V2).load();

        assertThat(Files.getPosixFilePermissions(configFile())).isEqualTo(chosen);
    }

    @Test
    void anUpgradeKeepsTheFilePermissionsOnAnyFileSystemWithPosixPermissions() throws Exception {
        try (FileSystem zip = posixZip()) {
            Path data = Files.createDirectories(zip.getPath("/data"));
            Path config = Files.writeString(data.resolve("config.yml"), OWNER_V1);
            Set<PosixFilePermission> chosen = PosixFilePermissions.fromString("rw-rw-r--");
            Files.setPosixFilePermissions(config, chosen);

            new ConfigLoader(data, shipped(SHIPPED_V2), log.logger).load();

            assertThat(Files.readString(config)).contains("config-version: 2");
            assertThat(Files.getPosixFilePermissions(config)).isEqualTo(chosen);
        }
    }

    @Test
    void aFileWrittenWhereNoneExistedGetsThePermissionsOfAnyNewFile() throws Exception {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
            "this file system has no POSIX permissions");

        loader(SHIPPED_V2).load();

        Path plain = Files.writeString(folder.resolve("plain.yml"), "x: 1\n");
        assertThat(Files.getPosixFilePermissions(configFile())).isEqualTo(Files.getPosixFilePermissions(plain));
    }

    @Test
    void aSaveThatCannotReplaceTheFileKeepsItAndLeavesNoTemporaryFile() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(DosFileAttributeView.class),
            "this file system has no read-only attribute");
        Files.setAttribute(configFile(), "dos:readonly", true);
        try {
            assumeFalse(Files.isWritable(configFile()), "the read-only attribute does not stop writes here");

            YamlConfiguration loaded = loader(SHIPPED_V2).load();

            assertThat(loaded.getInt("config-version")).isEqualTo(2);
            assertThat(Files.readString(configFile())).isEqualTo(OWNER_V1);
            assertThat(log.lines()).singleElement().asString()
                .startsWith("WARNING Could not save the upgraded config.yml: cannot write ");
            assertThat(leftovers()).isEmpty();
        } finally {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.setAttribute(file, "dos:readonly", false);
                }
            }
        }
    }

    @Test
    void aKeyLeftEmptyCountsAsMissingInAnUpgrade() throws Exception {
        Files.writeString(configFile(), """
            config-version: 1
            prefix:
            webhook: ~
            retired:
            """);

        YamlConfiguration loaded = loader("""
            config-version: 2
            prefix: "<gray>[X]"
            webhook: ""
            """).load();

        assertThat(loaded.getString("prefix")).isEqualTo("<gray>[X]");
        assertThat(loaded.getString("webhook")).isEmpty();
        assertThat(loaded.contains("retired")).isFalse();
        assertThat(reread().getString("prefix")).isEqualTo("<gray>[X]");
    }

    @Test
    void aShippedFileNotSavedAsUtf8StopsTheLoadAndWritesNothing() {
        Supplier<InputStream> latin1 = () -> new ByteArrayInputStream(
            "config-version: 2\nprefix: \"\u00a7a\"\n".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> new ConfigLoader(folder, latin1, log.logger).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("the config.yml inside the plugin jar is not saved as UTF-8")
            .hasCauseInstanceOf(CharacterCodingException.class);
        assertThat(configFile()).doesNotExist();
    }

    @Test
    void aFileWithNoVersionCountsAsVersionZero() throws Exception {
        Files.writeString(configFile(), "my-key: 7\n");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.getBoolean("modules.chat.enabled")).isTrue();
        assertThat(loaded.getInt("my-key")).isEqualTo(7);
        assertThat(log.lines()).containsExactly("INFO Upgraded config.yml from version 0 to 2 and added"
            + " modules.chat.enabled, new-group.flag; the previous file is kept as config.yml.v0.bak.");
    }

    @Test
    void anUpgradeNeverAddsBackAnEntryTheOwnerRenamedOrRemoved() throws Exception {
        Files.writeString(configFile(), "kits:\n  builder:\n    cooldown: 1h\n");

        YamlConfiguration loaded = ownerKeyedLoader(SHIPPED_KITS, KITS_ARE_OWNER_KEYED).load();

        assertThat(loaded.getConfigurationSection("kits").getKeys(false))
            .containsExactlyInAnyOrder("builder", "default");
        assertThat(loaded.getString("kits.default")).isEqualTo("starter");
    }

    @Test
    void anUpgradeAddsMissingKeysInsideAnEntryTheOwnerKept() throws Exception {
        Files.writeString(configFile(), "kits:\n  default: starter\n  starter:\n    cooldown: 2h\n");

        YamlConfiguration loaded = ownerKeyedLoader(SHIPPED_KITS, KITS_ARE_OWNER_KEYED).load();

        assertThat(loaded.getString("kits.starter.icon")).isEqualTo("GOLD_INGOT");
        assertThat(loaded.getString("kits.starter.cooldown")).isEqualTo("2h");
    }

    @Test
    void anEmptyOwnerKeyedSectionGetsTheShippedEntries() throws Exception {
        Files.writeString(configFile(), "kits: {}\n");

        YamlConfiguration loaded = ownerKeyedLoader(SHIPPED_KITS, KITS_ARE_OWNER_KEYED).load();

        assertThat(loaded.getString("kits.starter.cooldown")).isEqualTo("1h");
        assertThat(loaded.getString("kits.default")).isEqualTo("starter");
    }

    @Test
    void aFileWithoutTheOwnerKeyedSectionGetsTheShippedEntries() throws Exception {
        Files.writeString(configFile(), "my-key: 7\n");

        YamlConfiguration loaded = ownerKeyedLoader(SHIPPED_KITS, KITS_ARE_OWNER_KEYED).load();

        assertThat(loaded.getString("kits.starter.cooldown")).isEqualTo("1h");
        assertThat(loaded.getString("kits.default")).isEqualTo("starter");
    }

    @Test
    void withoutOwnerKeyedSectionsAnUpgradeAddsShippedEntriesUnderAnySection() throws Exception {
        Files.writeString(configFile(), "kits:\n  builder:\n    cooldown: 1h\n");

        YamlConfiguration loaded = loader(SHIPPED_KITS).load();

        assertThat(loaded.getConfigurationSection("kits").getKeys(false))
            .containsExactlyInAnyOrder("builder", "default", "starter");
        assertThat(loaded.getString("kits.starter.icon")).isEqualTo("GOLD_INGOT");
        assertThat(log.lines()).containsExactly("INFO Upgraded config.yml from version 0 to 2 and added"
            + " kits.default, kits.starter.cooldown, kits.starter.icon;"
            + " the previous file is kept as config.yml.v0.bak.");
    }

    @Test
    void anOwnerKeyedSectionMayLieInsideAnotherSection() throws Exception {
        String shipped = """
            config-version: 2
            cooldown:
              enabled: true
              tiers:
                member:
                  length: 30s
                  bypass: false
                vip:
                  length: 10s
                  bypass: false
            """;
        Files.writeString(configFile(), """
            cooldown:
              tiers:
                member:
                  length: 1m
                staff:
                  length: 0s
            """);

        YamlConfiguration loaded = ownerKeyedLoader(shipped, Map.of("cooldown.tiers", Set.of())).load();

        assertThat(loaded.getConfigurationSection("cooldown.tiers").getKeys(false))
            .containsExactlyInAnyOrder("member", "staff");
        assertThat(loaded.getString("cooldown.tiers.member.length")).isEqualTo("1m");
        assertThat(loaded.get("cooldown.tiers.member.bypass")).isEqualTo(false);
        assertThat(loaded.contains("cooldown.tiers.staff.bypass")).isFalse();
        assertThat(loaded.get("cooldown.enabled")).isEqualTo(true);
        assertThat(log.lines()).containsExactly("INFO Upgraded config.yml from version 0 to 2 and added"
            + " cooldown.enabled, cooldown.tiers.member.bypass; the previous file is kept as config.yml.v0.bak.");
    }

    @Test
    void theOwnerKeyedMapIsCopiedWhenTheLoaderIsBuilt() throws Exception {
        Set<String> fixed = new HashSet<>(Set.of("default"));
        Map<String, Set<String>> ownerKeyed = new HashMap<>(Map.of("kits", fixed));
        ConfigLoader loader = ownerKeyedLoader(SHIPPED_KITS, ownerKeyed);
        fixed.clear();
        ownerKeyed.clear();
        Files.writeString(configFile(), "kits:\n  builder:\n    cooldown: 1h\n");

        YamlConfiguration loaded = loader.load();

        assertThat(loaded.getConfigurationSection("kits").getKeys(false))
            .containsExactlyInAnyOrder("builder", "default");
    }

    @Test
    void aCurrentFileIsLeftUntouchedEvenWithAKeyMissing() throws Exception {
        String owner = "config-version: 2\nmy-key: 7\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(Files.readString(configFile())).isEqualTo(owner);
        assertThat(loaded.contains("new-group.flag")).isFalse();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aNewerFileIsReadAsItIsWithAWarning() throws Exception {
        String owner = "config-version: 9\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(Files.readString(configFile())).isEqualTo(owner);
        assertThat(loaded.getInt("config-version")).isEqualTo(9);
        assertThat(log.lines()).containsExactly("WARNING config.yml is version 9, newer than the version 2"
            + " this build knows, so it is read as it is and nothing is changed.");
    }

    @Test
    void aScalarWhereTheShippedFileHasAGroupIsKept() throws Exception {
        Files.writeString(configFile(), "config-version: 1\nmodules: false\n");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.get("modules")).isEqualTo(false);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(reread().get("modules")).isEqualTo(false);
    }

    @Test
    void invalidYamlStopsTheLoadWithTheReason() throws Exception {
        String owner = "config-version: 1\nmodules: [\n";
        Files.writeString(configFile(), owner);

        assertThatThrownBy(() -> loader(SHIPPED_V2).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ");
        assertThat(Files.readString(configFile())).isEqualTo(owner);
    }

    @Test
    void aJarWithoutTheFileStopsTheLoad() {
        ConfigLoader loader = new ConfigLoader(folder, () -> null, log.logger);

        assertThatThrownBy(loader::load)
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("the plugin jar has no config.yml");
    }

    @Test
    void anUnreadableShippedFileStopsTheLoad() {
        Supplier<InputStream> broken = () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        };

        assertThatThrownBy(() -> new ConfigLoader(folder, broken, log.logger).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read the config.yml inside the plugin jar");
    }

    @Test
    void aShippedFileThatIsNotValidYamlStopsTheLoadAndWritesNothing() {
        assertThatThrownBy(() -> loader("config-version: 2\nmodules: [\n").load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("the config.yml inside the plugin jar is not valid YAML: ");
        assertThat(configFile()).doesNotExist();
    }

    @Test
    void anUnwritableFolderStopsTheLoad() throws Exception {
        Path blocker = folder.resolve("blocker");
        Files.writeString(blocker, "a file, not a folder");

        assertThatThrownBy(() -> new ConfigLoader(blocker, shipped(SHIPPED_V2), log.logger).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("cannot write config.yml: ")
            .hasMessageNotContaining(folder.toString())
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void anUnreadableFileStopsTheLoad() throws Exception {
        Files.createDirectory(configFile());

        assertThatThrownBy(() -> loader(SHIPPED_V2).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("cannot read config.yml: ")
            .hasMessageNotContaining(folder.toString())
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aShippedFileWithAHeaderLoadsCleanly() throws Exception {
        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.options().getHeader()).containsExactly("Shipped header.");
        assertThat(loaded.getComments("new-group.flag")).containsExactly("The new flag.");
    }

    @Test
    void aLaterLoadReadsTheFileAgain() throws Exception {
        ConfigLoader loader = loader(SHIPPED_V2);
        YamlConfiguration first = loader.load();
        Files.writeString(configFile(), SHIPPED_V2.replace("flag: true", "flag: false"));

        YamlConfiguration second = loader.load();

        assertThat(second).isNotSameAs(first);
        assertThat(second.getBoolean("new-group.flag")).isFalse();
        assertThat(first.getBoolean("new-group.flag")).isTrue();
        assertThat(log.lines()).containsExactly(WROTE_DEFAULT);
    }

    @Test
    void aLaterLoadWritesAMissingFileAgain() throws Exception {
        ConfigLoader loader = loader(SHIPPED_V2);
        loader.load();
        Files.delete(configFile());

        YamlConfiguration loaded = loader.load();

        assertThat(Files.readString(configFile())).isEqualTo(SHIPPED_V2);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(log.lines()).containsExactly(WROTE_DEFAULT, WROTE_DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "modules: {}\n",
        "config-version: \"2\"\n",
        "config-version: 0\n",
        "config-version: -1\n",
        "config-version: 2.0\n",
        "config-version: two\n",
        "config-version: 99999999999\n",
        "config-version:\n"})
    void aShippedFileWithoutAWholeNumberVersionStopsTheFirstLoadAndWritesNothing(String shippedText) {
        assertThatThrownBy(() -> loader(shippedText).load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("the config.yml inside the plugin jar has no whole number of 1 or more under config-version")
            .hasNoCause();
        assertThat(configFile()).doesNotExist();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aShippedFileWithoutAVersionLeavesAnOwnerFileAsItIs() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        assertThatThrownBy(() -> loader("config-version: \"2\"\nnew-group:\n  flag: true\n").load())
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("the config.yml inside the plugin jar has no whole number of 1 or more under config-version");
        assertThat(Files.readString(configFile())).isEqualTo(OWNER_V1);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).containsExactly(configFile());
        }
    }

    @Test
    void aFileWithoutAVersionAtTheBaselineIsNeitherWrittenNorCopied() throws Exception {
        String owner = "# written by an earlier release\nmy-key: 7\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2.replace("config-version: 2", "config-version: 1"))
            .baselineVersion(1)
            .load();

        assertThat(loaded.getInt("my-key")).isEqualTo(7);
        assertThat(loaded.contains("config-version")).isFalse();
        assertThat(loaded.contains("new-group.flag")).isFalse();
        assertThat(Files.readString(configFile())).isEqualTo(owner);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).containsExactly(configFile());
        }
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFileWithoutAVersionIsUpgradedFromTheBaselineOnceTheShippedVersionIsHigher() throws Exception {
        String owner = "# written by an earlier release\nmy-key: 7\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).baselineVersion(1).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.getInt("my-key")).isEqualTo(7);
        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(reread().getBoolean("new-group.flag")).isTrue();
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(owner);
        assertThat(folder.resolve("config.yml.v0.bak")).doesNotExist();
        assertThat(log.lines()).containsExactly("INFO Upgraded config.yml from version 1 to 2 and added"
            + " modules.chat.enabled, new-group.flag; the previous file is kept as config.yml.v1.bak.");
    }

    @Test
    void aVersionThatIsNotANumberCountsAsTheBaseline() throws Exception {
        String owner = "config-version: latest\nmy-key: 7\n";
        Files.writeString(configFile(), owner);

        loader(SHIPPED_V2).baselineVersion(2).load();

        assertThat(Files.readString(configFile())).isEqualTo(owner);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void theBaselineDoesNotApplyToAFileThatHoldsAVersion() throws Exception {
        Files.writeString(configFile(), OWNER_V1);

        loader(SHIPPED_V2).baselineVersion(2).load();

        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
    }

    @Test
    void baselineVersionGivesACopyAndRefusesANegativeVersion() throws Exception {
        Files.writeString(configFile(), "my-key: 7\n");
        ConfigLoader loader = loader(SHIPPED_V2);

        assertThat(loader.baselineVersion(2)).isNotSameAs(loader);
        assertThatThrownBy(() -> loader.baselineVersion(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the baseline version is -1; expected 0 or more");
        loader.load();

        assertThat(folder.resolve("config.yml.v0.bak")).exists();
    }

    @Test
    void aLongTextStaysOnOneLineThroughAnUpgrade() throws Exception {
        String message = "<gradient:#ff0000:#00ff00>Willkommen auf dem Server</gradient> " + "sehr lang ".repeat(14)
            + "<gray>und noch ein Satz: mit Doppelpunkt, #Raute und 'Anfuehrungszeichen'";
        assertThat(message).hasSizeGreaterThan(200);
        Files.writeString(configFile(), "config-version: 1\nmessage: \"" + message + "\"\nlines:\n  - \"" + message
            + "\"\n");

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getString("message")).isEqualTo(message);
        assertThat(reread().getString("message")).isEqualTo(message);
        assertThat(reread().getStringList("lines")).containsExactly(message);
        assertThat(Files.readAllLines(configFile())).filteredOn(line -> line.startsWith("message:"))
            .singleElement().asString().contains("Anfuehrungszeichen");
        assertThat(Files.readAllLines(configFile())).filteredOn(line -> line.contains("Anfuehrungszeichen"))
            .hasSize(2);
    }

    @Test
    void aFileWithCarriageReturnsUpgradesAndTheCopyKeepsItByteForByte() throws Exception {
        byte[] owner = ("\uFEFF" + OWNER_V1.replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8);
        Files.write(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(Files.readAllBytes(folder.resolve("config.yml.v1.bak"))).isEqualTo(owner);
        assertThat(loaded.getBoolean("modules.chat.enabled")).isFalse();
        YamlConfiguration onDisk = reread();
        assertThat(onDisk.getInt("config-version")).isEqualTo(2);
        assertThat(onDisk.getBoolean("modules.chat.enabled")).isFalse();
        assertThat(onDisk.getInt("my-key")).isEqualTo(7);
        assertThat(onDisk.getBoolean("new-group.flag")).isTrue();
        assertThat(onDisk.getComments("modules.chat.enabled")).containsExactly("The owner turned this off.");
        assertThat(Files.readString(configFile())).doesNotContain("\r").doesNotContain("\uFEFF");
    }

    @Test
    void unusualCharactersSurviveAnUpgrade() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("emoji", "gg \uD83D\uDE00 \u2764");
        values.put("legacy", "\u00a7aGr\u00fcn & \u00a7lfett");
        values.put("cjk", "\u4f60\u597d\u3001\u4e16\u754c");
        values.put("tabbed", "a\tb");
        values.put("two-lines", "first\nsecond\n");
        values.put("spaced", "  padded  ");
        values.put("yaml-like", "yes: [not, a, list] # nor a comment");
        values.put("numeric", "0451");
        values.put("empty", "");
        values.put("backslash", "C:\\Server\\plugins \\n");
        values.put("quotes", "\"double\" and 'single'");
        YamlConfiguration owner = new YamlConfiguration();
        owner.set("config-version", 1);
        values.forEach(owner::set);
        Files.writeString(configFile(), owner.saveToString());

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        YamlConfiguration onDisk = reread();
        assertThat(onDisk.getInt("config-version")).isEqualTo(2);
        values.forEach((key, value) -> {
            assertThat(loaded.getString(key)).as(key).isEqualTo(value);
            assertThat(onDisk.get(key)).as(key).isEqualTo(value);
        });
        assertThat(log.lines()).singleElement().asString().startsWith("INFO Upgraded config.yml from version 1 to 2");
    }

    @Test
    void aBinaryValueOfTheOwnerIsWrittenBack() throws Exception {
        Files.writeString(configFile(), "config-version: 1\nicon: !!binary aGVsbG8=\n");

        loader(SHIPPED_V2).load();

        assertThat(reread().get("icon")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(reread().getInt("config-version")).isEqualTo(2);
    }

    @Test
    void aTextTheWriterCannotWriteKeepsTheFileAndWritesNoCopy() throws Exception {
        String owner = "config-version: 1\nmessage: \"a\\ud83db\"\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getInt("config-version")).isEqualTo(2);
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(loaded.getString("message")).isEqualTo("a\ud83db");
        assertThat(Files.readString(configFile())).isEqualTo(owner);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).containsExactly(configFile());
        }
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Could not save the upgraded config.yml: its settings cannot be written as YAML (")
            .endsWith("). The upgraded settings are used and the save is tried again the next time the settings are"
                + " loaded.");
    }

    @Test
    void aTextTheWriterWouldTurnIntoBinaryKeepsTheFileAndWritesNoCopy() throws Exception {
        String owner = "config-version: 1\nsounds:\n  bell: \"ding\\a\"\n";
        Files.writeString(configFile(), owner);

        YamlConfiguration loaded = loader(SHIPPED_V2).load();

        assertThat(loaded.getString("sounds.bell")).isEqualTo("ding\u0007");
        assertThat(loaded.getBoolean("new-group.flag")).isTrue();
        assertThat(Files.readString(configFile())).isEqualTo(owner);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).containsExactly(configFile());
        }
        assertThat(log.lines()).containsExactly("WARNING Could not save the upgraded config.yml: the value of"
            + " sounds.bell would not be read back as it is. The upgraded settings are used and the save is tried"
            + " again the next time the settings are loaded.");
    }

    @Test
    void theCopyReplacesAnEarlierCopyAndLeavesNoTemporaryFile() throws Exception {
        Files.writeString(configFile(), OWNER_V1);
        Files.writeString(folder.resolve("config.yml.v1.bak"), "an earlier copy\n");
        Files.writeString(folder.resolve("config.yml.v1.bak.tmp"), "cut off");

        loader(SHIPPED_V2).load();

        assertThat(Files.readString(folder.resolve("config.yml.v1.bak"))).isEqualTo(OWNER_V1);
        assertThat(reread().getInt("config-version")).isEqualTo(2);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theCopyGetsThePermissionsOfTheFile() throws Exception {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
            "this file system has no POSIX permissions");
        Files.writeString(configFile(), OWNER_V1);
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(configFile(), ownerOnly);

        loader(SHIPPED_V2).load();

        assertThat(Files.getPosixFilePermissions(folder.resolve("config.yml.v1.bak"))).isEqualTo(ownerOnly);
    }

    @Test
    void anOwnerKeyedSectionInsideAnotherIsRefused() {
        Map<String, Set<String>> nested = Map.of("kits", Set.of(), "kits.special", Set.of());

        assertThatThrownBy(() -> ownerKeyedLoader(SHIPPED_KITS, nested))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the owner-keyed section kits.special lies inside the owner-keyed section kits");
        assertThatThrownBy(() -> ownerKeyedLoader(SHIPPED_KITS, Map.of(" ", Set.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("an owner-keyed section has a blank path");
        ownerKeyedLoader(SHIPPED_KITS, Map.of("kits", Set.of(), "kits-extra", Set.of(), "menus.kits", Set.of()));
    }

    private ConfigLoader loader(String shippedText) {
        return new ConfigLoader(folder, shipped(shippedText), log.logger);
    }

    private ConfigLoader ownerKeyedLoader(String shippedText, Map<String, Set<String>> ownerKeyed) {
        return new ConfigLoader(folder, shipped(shippedText), log.logger, ownerKeyed);
    }

    private static Supplier<InputStream> shipped(String text) {
        return () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private Path configFile() {
        return folder.resolve("config.yml");
    }

    private Path temporaryFile() {
        return folder.resolve("config.yml.tmp");
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "this file system cannot create symbolic links");
        }
    }

    private static void plant(IoAction action) {
        try {
            action.run();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private YamlConfiguration reread() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(Files.readString(configFile()));
        return yaml;
    }

    /** A zip file system with POSIX permissions, which every operating system can open. */
    private FileSystem posixZip() throws IOException {
        return FileSystems.newFileSystem(
            folder.resolve("posix.zip"), Map.of("create", "true", "enablePosixFileAttributes", "true"));
    }

    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
        }
    }
}
