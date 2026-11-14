package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.RaceRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ConfigHolderTest {

    private static final String WROTE_DEFAULT =
        "INFO Wrote the default config.yml, so the default settings are in effect.";

    private final List<String> failures = new ArrayList<>();

    @Test
    void theFirstValueIsBothInEffectAndTheStartupValue() throws Exception {
        ConfigHolder<String> holder = ConfigHolder.load(() -> "first", failures::add);

        assertThat(holder.get()).isEqualTo("first");
        assertThat(holder.startup()).isEqualTo("first");
        assertThat(failures).isEmpty();
    }

    @Test
    void aFirstLoadThatFailsThrowsTheLoadersExceptionAndReportsNothing() {
        AtomicInteger loads = new AtomicInteger();
        ConfigLoadException broken = new ConfigLoadException("config.yml is not valid YAML: bad indent");

        assertThatThrownBy(() -> ConfigHolder.<String>load(() -> {
            loads.incrementAndGet();
            throw broken;
        }, failures::add)).isSameAs(broken);
        assertThat(loads).hasValue(1);
        assertThat(failures).isEmpty();
    }

    @Test
    void aSuccessfulReloadPutsTheNewValueInEffectAndKeepsTheStartupValue() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ConfigHolder<Integer> holder = ConfigHolder.load(loads::incrementAndGet, failures::add);

        assertThat(holder.reload()).isTrue();
        assertThat(holder.reload()).isTrue();

        assertThat(holder.get()).isEqualTo(3);
        assertThat(holder.startup()).isEqualTo(1);
        assertThat(failures).isEmpty();
    }

    @Test
    void aFailingReloadKeepsTheValueReportsOneLineWithTheReasonAndReturnsFalse() throws Exception {
        List<ConfigHolder.Loader<String>> answers = new ArrayList<>(List.of(
            () -> "first",
            () -> {
                throw new ConfigLoadException("config.yml is not valid YAML: bad indent");
            }));
        ConfigHolder<String> holder = ConfigHolder.load(() -> answers.removeFirst().load(), failures::add);

        assertThat(holder.reload()).isFalse();

        assertThat(holder.get()).isEqualTo("first");
        assertThat(holder.startup()).isEqualTo("first");
        assertThat(failures).containsExactly(
            "The reload failed, so the settings in effect stay active and no value read by this reload is used:"
                + " config.yml is not valid YAML: bad indent");
    }

    @Test
    void aRuntimeExceptionFromTheLoaderIsThrownOnAndKeepsTheValue() throws Exception {
        IllegalStateException bug = new IllegalStateException("a bug in the loader");
        List<ConfigHolder.Loader<String>> answers = new ArrayList<>(List.of(
            () -> "first",
            () -> {
                throw bug;
            }));
        ConfigHolder<String> holder = ConfigHolder.load(() -> answers.removeFirst().load(), failures::add);

        assertThatThrownBy(holder::reload).isSameAs(bug);

        assertThat(holder.get()).isEqualTo("first");
        assertThat(failures).isEmpty();
    }

    @Test
    void aNullFromTheLoaderIsThrownAtTheFirstLoadAndKeepsTheValueOnAReload() throws Exception {
        assertThatThrownBy(() -> ConfigHolder.<String>load(() -> null, failures::add))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("the loader returned null");

        List<ConfigHolder.Loader<String>> answers = new ArrayList<>(List.of(() -> "first", () -> null));
        ConfigHolder<String> holder = ConfigHolder.load(() -> answers.removeFirst().load(), failures::add);

        assertThatThrownBy(holder::reload)
            .isInstanceOf(NullPointerException.class)
            .hasMessage("the loader returned null");
        assertThat(holder.get()).isEqualTo("first");
        assertThat(failures).isEmpty();
    }

    @Test
    void aValueTheReaderReplacesWithItsFallbackDoesNotFailTheReload(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("settings.yml");
        Files.writeString(file, "limit: 9\n");
        List<String> warnings = new ArrayList<>();
        ConfigHolder<Integer> holder = ConfigHolder.load(
            () -> new ConfigReader(StrictYaml.read(file, "settings.yml"), warnings::add).integer("limit", 5),
            failures::add);

        Files.writeString(file, "limit: five\n");

        assertThat(holder.reload()).isTrue();
        assertThat(holder.get()).isEqualTo(5);
        assertThat(warnings).containsExactly("limit is \"five\"; expected a whole number, so 5 is used.");
        assertThat(failures).isEmpty();
    }

    @Test
    void reloadServesAsABooleanSupplierAndACallableAndTheHolderAsASupplier() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ConfigHolder<Integer> holder = ConfigHolder.load(loads::incrementAndGet, failures::add);
        BooleanSupplier command = holder::reload;
        Callable<Boolean> editor = holder::reload;
        Supplier<Integer> settings = holder;

        assertThat(command.getAsBoolean()).isTrue();
        assertThat(editor.call()).isTrue();

        assertThat(settings.get()).isEqualTo(3);
    }

    @Test
    void reloadsThatRaceNeverRunTheLoaderTwiceAtOnce() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInside = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        ConfigHolder<Integer> holder = ConfigHolder.load(() -> {
            mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            inside.decrementAndGet();
            return loads.incrementAndGet();
        }, failures::add);

        List<Boolean> results = RaceRunner.race(8, contender -> holder.reload());

        assertThat(results).containsOnly(true).hasSize(8);
        assertThat(mostInside).hasValue(1);
        assertThat(loads).hasValue(9);
        assertThat(holder.get()).isEqualTo(9);
        assertThat(holder.startup()).isEqualTo(1);
    }

    @Test
    void aBrokenEditAndAnEditWithValueErrorsBothKeepThePreviousSettings(@TempDir Path folder) throws Exception {
        String shipped = """
            config-version: 1
            storage:
              table: entries
              host: localhost
            limit: 5
            """;
        CapturedLog log = new CapturedLog();
        List<String> warnings = new ArrayList<>();
        ConfigLoader files = new ConfigLoader(folder,
            () -> new ByteArrayInputStream(shipped.getBytes(StandardCharsets.UTF_8)), log.logger);
        ConfigHolder.Loader<Settings> loader = () -> {
            ConfigReader reader = new ConfigReader(files.load(), warnings::add);
            Settings settings = Settings.read(reader);
            List<String> errors = reader.errors();
            if (!errors.isEmpty()) {
                errors.forEach(log.logger::severe);
                throw new ConfigLoadException(ConfigLoader.FILE_NAME + " has " + errors.size()
                    + " errors, listed above");
            }
            return settings;
        };
        ConfigHolder<Settings> holder = ConfigHolder.load(loader, log.logger::warning);
        Path file = folder.resolve(ConfigLoader.FILE_NAME);

        Files.writeString(file, shipped.replace("limit: 5", "limit: 9"));
        assertThat(holder.reload()).isTrue();
        Settings edited = holder.get();
        assertThat(edited).isEqualTo(new Settings("entries", "localhost", 9));
        assertThat(holder.startup()).isEqualTo(new Settings("entries", "localhost", 5));

        Files.writeString(file, "config-version: 1\nlimit: [9\n");
        assertThat(holder.reload()).isFalse();
        assertThat(holder.get()).isSameAs(edited);

        Files.writeString(file, """
            config-version: 1
            storage:
              table: ""
              host: [one, two]
            limit: 12
            """);
        assertThat(holder.reload()).isFalse();
        assertThat(holder.get()).isSameAs(edited);

        assertThat(log.lines()).hasSize(5);
        assertThat(log.lines().get(0)).isEqualTo(WROTE_DEFAULT);
        assertThat(log.lines().get(1))
            .startsWith("WARNING The reload failed, so the settings in effect stay active and no value read by this"
                + " reload is used: config.yml is not valid YAML: ");
        assertThat(log.lines().subList(2, 5)).containsExactly(
            "SEVERE storage.table is \"\"; set it to the name of the table.",
            "SEVERE storage.host is [one, two]; expected a host name.",
            "WARNING The reload failed, so the settings in effect stay active and no value read by this reload is"
                + " used: config.yml has 2 errors, listed above");
        assertThat(warnings).isEmpty();
    }

    private record Settings(String table, String host, int limit) {

        static final int LIMIT = 5;
        static final String HOST = "localhost";

        static Settings read(ConfigReader reader) {
            return new Settings(
                String.valueOf(reader.requiredText("storage.table", "set it to the name of the table")),
                String.valueOf(reader.strictText("storage.host", HOST, "a host name")),
                reader.integer("limit", LIMIT));
        }
    }
}
