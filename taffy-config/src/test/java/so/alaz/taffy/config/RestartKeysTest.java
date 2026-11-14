package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

final class RestartKeysTest {

    private static final Predicate<String> RESTART_ONLY = path ->
        path.startsWith("storage.") || path.startsWith("hooks.") || path.matches("modules\\.[^.]+\\.enabled");

    private static final String BASE = """
        storage:
          type: sqlite
          sqlite:
            file: data.db
        modules:
          chat:
            enabled: true
        chat:
          cooldown: 3s
        """;

    @Test
    void nothingChangedMeansNothingToRestart() throws Exception {
        assertThat(RestartKeys.changed(yaml(BASE), yaml(BASE), RESTART_ONLY)).isEmpty();
    }

    @Test
    void storageAndModuleSwitchesNeedARestart() throws Exception {
        String after = BASE
            .replace("type: sqlite", "type: mariadb")
            .replace("enabled: true", "enabled: false");

        assertThat(RestartKeys.changed(yaml(BASE), yaml(after), RESTART_ONLY))
            .containsExactly("modules.chat.enabled", "storage.type");
    }

    @Test
    void otherKeysApplyOnReload() throws Exception {
        String after = BASE.replace("cooldown: 3s", "cooldown: 5s");

        assertThat(RestartKeys.changed(yaml(BASE), yaml(after), RESTART_ONLY)).isEmpty();
    }

    @Test
    void addedAndRemovedStorageKeysCount() throws Exception {
        String after = BASE.replace("    file: data.db\n", "") + "storage-extra: 1\n";
        String added = BASE.replace("type: sqlite", "type: sqlite\n  server-id: lobby");

        assertThat(RestartKeys.changed(yaml(BASE), yaml(after), RESTART_ONLY)).containsExactly("storage.sqlite.file");
        assertThat(RestartKeys.changed(yaml(BASE), yaml(added), RESTART_ONLY)).containsExactly("storage.server-id");
    }

    @Test
    void hookSwitchesNeedARestartAndFilterRulesDoNot() throws Exception {
        String before = BASE + "hooks:\n  nexo: true\nfilters:\n  allow-damaged: true\n";
        String hooks = before.replace("nexo: true", "nexo: false");
        String filters = before.replace("allow-damaged: true", "allow-damaged: false");

        assertThat(RestartKeys.changed(yaml(before), yaml(hooks), RESTART_ONLY)).containsExactly("hooks.nexo");
        assertThat(RestartKeys.changed(yaml(before), yaml(filters), RESTART_ONLY)).isEmpty();
    }

    @Test
    void aKeySetBackToItsStartupValueIsNoLongerReported() throws Exception {
        YamlConfiguration startup = yaml(BASE);
        YamlConfiguration firstReload = yaml(BASE.replace("type: sqlite", "type: mariadb"));
        YamlConfiguration secondReload = yaml(BASE);

        assertThat(RestartKeys.changed(startup, firstReload, RESTART_ONLY)).containsExactly("storage.type");
        assertThat(RestartKeys.changed(startup, secondReload, RESTART_ONLY)).isEmpty();
        assertThat(RestartKeys.changed(firstReload, secondReload, RESTART_ONLY)).containsExactly("storage.type");
    }

    @Test
    void aListIsComparedAsOneValue() throws Exception {
        String before = BASE + "hooks:\n  order:\n    - nexo\n    - oraxen\n";
        String sameList = BASE + "hooks:\n  order: [nexo, oraxen]\n";
        String reordered = BASE + "hooks:\n  order:\n    - oraxen\n    - nexo\n";
        String longer = before + "    - itemsadder\n";

        assertThat(RestartKeys.changed(yaml(before), yaml(sameList), RESTART_ONLY)).isEmpty();
        assertThat(RestartKeys.changed(yaml(before), yaml(reordered), RESTART_ONLY)).containsExactly("hooks.order");
        assertThat(RestartKeys.changed(yaml(before), yaml(longer), RESTART_ONLY)).containsExactly("hooks.order");
    }

    @Test
    void theRuleIsAskedAboutValuesAndNeverAboutSections() throws Exception {
        List<String> asked = new ArrayList<>();

        RestartKeys.changed(yaml(BASE), yaml(BASE), path -> {
            asked.add(path);
            return false;
        });

        assertThat(asked).containsExactlyInAnyOrder(
            "storage.type", "storage.sqlite.file", "modules.chat.enabled", "chat.cooldown",
            "storage.type", "storage.sqlite.file", "modules.chat.enabled", "chat.cooldown");
    }

    @Test
    void pathsAreRelativeToTheSectionPassed() throws Exception {
        YamlConfiguration after = yaml(BASE.replace("file: data.db", "file: other.db"));
        List<String> asked = new ArrayList<>();

        List<String> changed = RestartKeys.changed(
            yaml(BASE).getConfigurationSection("storage"),
            after.getConfigurationSection("storage"),
            path -> {
                asked.add(path);
                return true;
            });

        assertThat(asked).containsExactlyInAnyOrder("type", "sqlite.file", "type", "sqlite.file");
        assertThat(changed).containsExactly("sqlite.file");
    }

    @Test
    void anEmptySectionThatOnlyOneHasIsNotReported() throws Exception {
        String before = BASE + "hooks:\n  extra: {}\n  nexo: true\n";
        String after = BASE + "hooks:\n  nexo: true\n";

        assertThat(RestartKeys.changed(yaml(before), yaml(after), path -> true)).isEmpty();
    }

    @Test
    void aSectionReplacedByAValueIsReportedWithTheKeysBeneathIt() throws Exception {
        String after = BASE.replace("  sqlite:\n    file: data.db\n", "  sqlite: data.db\n");

        assertThat(RestartKeys.changed(yaml(BASE), yaml(after), path -> path.startsWith("storage.")))
            .containsExactly("storage.sqlite", "storage.sqlite.file");
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }
}
