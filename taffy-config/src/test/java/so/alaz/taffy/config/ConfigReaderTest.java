package so.alaz.taffy.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.Durations;
import so.alaz.taffy.test.JvmDefaults;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ConfigReaderTest {

    private static final String PERCENT_EXPECTED = "a percent from 0% to 100% such as 5%";

    private static final String DATES = """
        day: 2026-12-31
        moment: 2026-12-31T10:00:00Z
        quoted: "2026-12-31T10:00:00"
        """;

    private final List<String> warnings = new ArrayList<>();

    private record Percent(int value) {

        static Percent parse(String text) {
            if (!text.matches("\\d{1,3}%")) {
                throw new IllegalArgumentException("no percent sign in " + text);
            }
            int value = Integer.parseInt(text.substring(0, text.length() - 1));
            if (value > 100) {
                throw new IllegalArgumentException("above 100: " + text);
            }
            return new Percent(value);
        }

        @Override
        public String toString() {
            return value + "%";
        }
    }

    @Test
    void readsEachTypeAndRecordsThePath() throws Exception {
        ConfigReader reader = reader("""
            whole: 3
            flag: true
            wait: 15s
            tax: "5%"
            group:
              a: 1
              b: 2
            """);

        assertThat(reader.integer("whole", 0)).isEqualTo(3);
        assertThat(reader.bool("flag", false)).isTrue();
        assertThat(reader.duration("wait", Duration.ZERO)).isEqualTo(Duration.ofSeconds(15));
        assertThat(reader.parsed("tax", new Percent(0), PERCENT_EXPECTED, Percent::parse))
            .isEqualTo(new Percent(5));
        assertThat(reader.children("group")).containsExactly("a", "b");
        assertThat(reader.isSection("group")).isTrue();
        assertThat(reader.readPaths()).containsExactly("whole", "flag", "wait", "tax", "group");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aMissingKeyUsesTheFallbackWithoutAWarning() throws Exception {
        ConfigReader reader = reader("other: 1\n");
        Percent fallback = new Percent(1);

        assertThat(reader.integer("whole", 4)).isEqualTo(4);
        assertThat(reader.bool("flag", true)).isTrue();
        assertThat(reader.duration("wait", Duration.ofMinutes(2))).isEqualTo(Duration.ofMinutes(2));
        assertThat(reader.parsed("tax", fallback, PERCENT_EXPECTED, Percent::parse)).isSameAs(fallback);
        assertThat(reader.children("group")).isEmpty();
        assertThat(reader.isSection("group")).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void anUnreadableValueUsesTheFallbackAndNamesBoth() throws Exception {
        ConfigReader reader = reader("""
            whole: lots
            flag: maybe
            wait: 3
            tax: "200%"
            big: 99999999999
            group: 5
            nested:
              a: 1
            """);

        assertThat(reader.integer("whole", 4)).isEqualTo(4);
        assertThat(reader.bool("flag", true)).isTrue();
        assertThat(reader.duration("wait", Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(reader.parsed("tax", new Percent(0), PERCENT_EXPECTED, Percent::parse))
            .isEqualTo(new Percent(0));
        assertThat(reader.integer("big", 1)).isEqualTo(1);
        assertThat(reader.children("group")).isEmpty();
        assertThat(reader.bool("nested", false)).isFalse();

        assertThat(warnings).containsExactly(
            "whole is \"lots\"; expected a whole number, so 4 is used.",
            "flag is \"maybe\"; expected true or false, so true is used.",
            "wait is 3; expected " + Durations.EXPECTED + ", so 30s is used.",
            "tax is \"200%\"; expected a percent from 0% to 100% such as 5%, so 0% is used.",
            "big is 99999999999; expected a whole number from -2147483648 to 2147483647, so 1 is used.",
            "group should hold keys beneath it, so its value is ignored.",
            "nested is a group of keys; expected true or false, so false is used.");
    }

    @Test
    void textAcceptsAnySingleValue() throws Exception {
        ConfigReader reader = reader("""
            word: hello
            number: 12345
            flag: false
            blank: ""
            list: [a, b]
            """);

        assertThat(reader.text("word", "x")).isEqualTo("hello");
        assertThat(reader.text("number", "x")).isEqualTo("12345");
        assertThat(reader.text("flag", "x")).isEqualTo("false");
        assertThat(reader.text("blank", "x")).isEmpty();
        assertThat(reader.text("missing", "x")).isEqualTo("x");
        assertThat(reader.text("list", "x")).isEqualTo("x");
        assertThat(warnings).containsExactly("list is [a, b]; expected a single value, so x is used.");
    }

    @Test
    void quotedRefusesValuesYamlAlreadyRewrote() throws Exception {
        ConfigReader reader = reader("""
            kept: "0451"
            octal: 0451
            switch: true
            missing_is_fine: x
            """);

        assertThat(reader.quoted("kept", "f")).isEqualTo("0451");
        assertThat(reader.quoted("octal", "f")).isEqualTo("f");
        assertThat(reader.quoted("switch", "f")).isEqualTo("f");
        assertThat(reader.quoted("absent", "f")).isEqualTo("f");
        assertThat(reader.errors()).hasSize(2);
        assertThat(reader.errors().get(0)).startsWith("octal must be in quotes");
        assertThat(reader.errors()).containsExactly(
            "octal must be in quotes, because YAML changes an unquoted value it reads as a number, a date or"
                + " true/false.",
            "switch must be in quotes, because YAML changes an unquoted value it reads as a number, a date or"
                + " true/false.");
        assertThat(warnings).isEmpty();
    }

    @Test
    void textReadsAnUnquotedDateAsItsDateAndAsksForQuotesAroundADateWithATime() throws Exception {
        forEachZone(() -> {
            ConfigReader reader = reader(DATES);

            assertThat(reader.text("day", "x")).isEqualTo("2026-12-31");
            assertThat(reader.text("moment", "x")).isEqualTo("x");
            assertThat(reader.text("quoted", "x")).isEqualTo("2026-12-31T10:00:00");

            assertThat(warnings).containsExactly(
                "moment must be in quotes, because YAML reads a date and time written without them in UTC, so x is"
                    + " used.");
            assertThat(reader.errors()).isEmpty();
        });
    }

    @Test
    void stringsKeepsAnUnquotedDateAndSkipsADateWithATime() throws Exception {
        forEachZone(() -> {
            ConfigReader reader = reader("days: [2026-12-31, 2026-12-31T10:00:00Z, \"2026-12-31T10:00:00\"]\n");

            assertThat(reader.strings("days", List.of("X"))).containsExactly("2026-12-31", "2026-12-31T10:00:00");

            assertThat(warnings).containsExactly(
                "days has 2026-12-31T10:00:00Z, which must be in quotes, because YAML reads a date and time written"
                    + " without them in UTC, so it is skipped.");
            assertThat(reader.errors()).isEmpty();
        });
    }

    @Test
    void requiredTextReadsAnUnquotedDateAndReportsADateWithATimeAsAnError() throws Exception {
        forEachZone(() -> {
            ConfigReader reader = reader(DATES);

            assertThat(reader.requiredText("day", "set it")).isEqualTo("2026-12-31");
            assertThat(reader.requiredText("moment", "set it")).isNull();
            assertThat(reader.requiredText("quoted", "set it")).isEqualTo("2026-12-31T10:00:00");

            assertThat(reader.errors()).containsExactly(
                "moment must be in quotes, because YAML reads a date and time written without them in UTC.");
            assertThat(warnings).isEmpty();
        });
    }

    @Test
    void strictTextReadsAnUnquotedDateAndReportsADateWithATimeAsAnError() throws Exception {
        forEachZone(() -> {
            ConfigReader reader = reader(DATES);

            assertThat(reader.strictText("day", "never", "a date")).isEqualTo("2026-12-31");
            assertThat(reader.strictText("moment", "never", "a date")).isNull();
            assertThat(reader.strictText("quoted", "never", "a date")).isEqualTo("2026-12-31T10:00:00");

            assertThat(reader.errors()).containsExactly(
                "moment must be in quotes, because YAML reads a date and time written without them in UTC.");
            assertThat(warnings).isEmpty();
        });
    }

    @Test
    void quotedRefusesAnyUnquotedDate() throws Exception {
        forEachZone(() -> {
            ConfigReader reader = reader(DATES);

            assertThat(reader.quoted("day", "f")).isEqualTo("f");
            assertThat(reader.quoted("moment", "f")).isEqualTo("f");
            assertThat(reader.quoted("quoted", "f")).isEqualTo("2026-12-31T10:00:00");

            assertThat(reader.errors()).containsExactly(
                "day must be in quotes, because YAML changes an unquoted value it reads as a number, a date or"
                    + " true/false.",
                "moment must be in quotes, because YAML changes an unquoted value it reads as a number, a date or"
                    + " true/false.");
            assertThat(warnings).isEmpty();
        });
    }

    @Test
    void errorsAreCollectedApartFromWarnings() throws Exception {
        ConfigReader reader = reader("a: 1\n");

        reader.error("first");
        reader.error("second");

        assertThat(reader.errors()).containsExactly("first", "second").isUnmodifiable();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aWarningGoesStraightToTheConsumerAndIsNotAnError() throws Exception {
        ConfigReader reader = reader("a: 1\n");

        reader.warn("careful");

        assertThat(warnings).containsExactly("careful");
        assertThat(reader.errors()).isEmpty();
    }

    @Test
    void aListOfSingleValuesReadsAsTextAndAnythingElseUsesTheFallback() throws Exception {
        ConfigReader reader = reader("""
            names: [BEDROCK, 7, true, "nexo:*"]
            none: []
            single: BEDROCK
            group:
              a: 1
            """);
        List<String> fallback = List.of("X");

        assertThat(reader.strings("names", fallback)).containsExactly("BEDROCK", "7", "true", "nexo:*");
        assertThat(reader.strings("none", fallback)).isEmpty();
        assertThat(reader.strings("absent", fallback)).isSameAs(fallback);
        assertThat(warnings).isEmpty();
        assertThat(reader.strings("single", fallback)).isSameAs(fallback);
        assertThat(reader.strings("group", fallback)).isSameAs(fallback);
        assertThat(warnings).containsExactly(
            "single is \"BEDROCK\"; expected a list such as [A, B], so [X] is used.",
            "group is a group of keys; expected a list such as [A, B], so [X] is used.");
        assertThat(reader.readPaths()).contains("names", "none", "absent", "single", "group");
    }

    @Test
    void anEntryThatIsNotASingleValueIsSkippedAndTheRestKept() throws Exception {
        ConfigReader reader = reader("""
            ids:
              - "nexo:*"
              - oraxen: sword
              -
              - [a, b]
              - itemsadder:myitems:crown
            """);

        assertThat(reader.strings("ids", List.of("X"))).containsExactly("nexo:*", "itemsadder:myitems:crown");
        assertThat(warnings).containsExactly(
            "ids has {oraxen=sword}, which is not a single value, so it is skipped.",
            "ids has an empty entry, which is not a single value, so it is skipped.",
            "ids has [a, b], which is not a single value, so it is skipped.");
    }

    @Test
    void aDurationUnitReadsInAnyLetterCase() throws Exception {
        ConfigReader reader = reader("""
            wait: 15S
            pause: 250MS
            """);

        assertThat(reader.duration("wait", Duration.ZERO)).isEqualTo(Duration.ofSeconds(15));
        assertThat(reader.duration("pause", Duration.ZERO)).isEqualTo(Duration.ofMillis(250));
        assertThat(warnings).isEmpty();
    }

    @Test
    void parsedGivesTheParserTheTextOfANumberOrASwitch() throws Exception {
        ConfigReader reader = reader("""
            count: 99999999999
            word: lots
            flag: true
            """);
        List<String> seen = new ArrayList<>();
        Function<String, Long> parser = text -> {
            seen.add(text);
            return Long.valueOf(text);
        };

        assertThat(reader.parsed("count", 0L, "a whole number", parser)).isEqualTo(99_999_999_999L);
        assertThat(reader.parsed("word", 7L, "a whole number", parser)).isEqualTo(7L);
        assertThat(reader.parsed("flag", 8L, "a whole number", parser)).isEqualTo(8L);

        assertThat(seen).containsExactly("99999999999", "lots", "true");
        assertThat(warnings).containsExactly(
            "word is \"lots\"; expected a whole number, so 7 is used.",
            "flag is true; expected a whole number, so 8 is used.");
    }

    @Test
    void parsedRefusesAListOrAGroupOfKeysWithoutCallingTheParser() throws Exception {
        ConfigReader reader = reader("""
            listed: ["5%", "6%"]
            grouped:
              rate: "5%"
            """);
        List<String> seen = new ArrayList<>();
        Function<String, Percent> parser = text -> {
            seen.add(text);
            return Percent.parse(text);
        };
        Percent fallback = new Percent(2);

        assertThat(reader.parsed("listed", fallback, PERCENT_EXPECTED, parser)).isSameAs(fallback);
        assertThat(reader.parsed("grouped", fallback, PERCENT_EXPECTED, parser)).isSameAs(fallback);

        assertThat(seen).isEmpty();
        assertThat(warnings).containsExactly(
            "listed is [5%, 6%]; expected a percent from 0% to 100% such as 5%, so 2% is used.",
            "grouped is a group of keys; expected a percent from 0% to 100% such as 5%, so 2% is used.");
        assertThat(reader.readPaths()).containsExactly("listed", "grouped");
    }

    @Test
    void parsedGivesTheParserAnUnquotedDateAsItIsWritten() throws Exception {
        for (String zone : List.of("America/St_Johns", "Pacific/Auckland")) {
            warnings.clear();
            JvmDefaults.withTimeZone(zone, () -> {
                ConfigReader reader = reader("""
                    day: 2026-12-31
                    midnight: 2026-12-31T00:00:00Z
                    early: 2026-01-02
                    """);
                List<String> seen = new ArrayList<>();
                Function<String, String> parser = text -> {
                    seen.add(text);
                    if (text.startsWith("2026-01")) {
                        throw new IllegalArgumentException("too early: " + text);
                    }
                    return text;
                };

                assertThat(reader.value("day")).isInstanceOf(Date.class);
                assertThat(reader.parsed("day", "never", "a date in December", parser)).isEqualTo("2026-12-31");
                assertThat(reader.parsed("midnight", "never", "a date in December", parser))
                    .isEqualTo("2026-12-31");
                assertThat(reader.parsed("early", "never", "a date in December", parser)).isEqualTo("never");

                assertThat(seen).containsExactly("2026-12-31", "2026-12-31", "2026-01-02");
                assertThat(warnings)
                    .containsExactly("early is 2026-01-02; expected a date in December, so never is used.");
            });
        }
    }

    @Test
    void parsedRefusesAnUnquotedDateWithATimeAndAsksForQuotes() throws Exception {
        ConfigReader reader = reader("""
            zoned: 2026-12-31T23:59:59Z
            local: 2026-12-31T10:00:00
            spaced: 2026-12-31 10:00:00
            quoted: "2026-12-31T10:00:00"
            """);
        List<String> seen = new ArrayList<>();
        Function<String, String> parser = text -> {
            seen.add(text);
            return text;
        };

        assertThat(reader.parsed("zoned", "never", "a date", parser)).isEqualTo("never");
        assertThat(reader.parsed("local", "never", "a date", parser)).isEqualTo("never");
        assertThat(reader.parsed("spaced", "never", "a date", parser)).isEqualTo("never");
        assertThat(reader.parsed("quoted", "never", "a date", parser)).isEqualTo("2026-12-31T10:00:00");

        assertThat(seen).containsExactly("2026-12-31T10:00:00");
        assertThat(warnings).containsExactly(
            "zoned must be in quotes, because YAML reads a date and time written without them in UTC, so never is"
                + " used.",
            "local must be in quotes, because YAML reads a date and time written without them in UTC, so never is"
                + " used.",
            "spaced must be in quotes, because YAML reads a date and time written without them in UTC, so never is"
                + " used.");
    }

    @Test
    void parsedWritesTheFallbackTextItIsGivenInEveryWarning() throws Exception {
        ConfigReader reader = reader("""
            tax: 200%
            listed: [5%]
            moment: 2026-12-31T10:00:00Z
            kept: 7%
            """);
        Percent fallback = new Percent(2);

        assertThat(reader.parsed("tax", fallback, "no tax", PERCENT_EXPECTED, Percent::parse)).isSameAs(fallback);
        assertThat(reader.parsed("listed", fallback, "no tax", PERCENT_EXPECTED, Percent::parse))
            .isSameAs(fallback);
        assertThat(reader.parsed("moment", fallback, "no tax", PERCENT_EXPECTED, Percent::parse))
            .isSameAs(fallback);
        assertThat(reader.parsed("kept", fallback, "no tax", PERCENT_EXPECTED, Percent::parse))
            .isEqualTo(new Percent(7));
        assertThat(reader.parsed("absent", null, "never", PERCENT_EXPECTED, Percent::parse)).isNull();

        assertThat(warnings).containsExactly(
            "tax is \"200%\"; expected a percent from 0% to 100% such as 5%, so no tax is used.",
            "listed is [5%]; expected a percent from 0% to 100% such as 5%, so no tax is used.",
            "moment must be in quotes, because YAML reads a date and time written without them in UTC, so no tax"
                + " is used.");
        assertThat(reader.readPaths()).containsExactly("tax", "listed", "moment", "kept", "absent");
    }

    @Test
    void rootGivesTheSectionWithoutRecordingARead() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("a: 1\n");
        ConfigReader reader = new ConfigReader(yaml, warnings::add);

        assertThat(reader.root()).isSameAs(yaml);
        assertThat(reader.readPaths()).isEmpty();
        assertThat(reader.unreadKeys()).containsExactly("a");
    }

    @Test
    void aWholeNumberOutsideTheIntRangeIsToldItsRange() throws Exception {
        ConfigReader reader = reader("""
            big: 5000000000
            huge: 99999999999999999999
            negative: -5000000000
            word: lots
            """);

        assertThat(reader.integer("big", 1)).isEqualTo(1);
        assertThat(reader.integer("huge", 1)).isEqualTo(1);
        assertThat(reader.integer("big", 5, 1, 10)).isEqualTo(5);
        assertThat(reader.integer("negative", 5, 1, 10)).isEqualTo(5);
        assertThat(reader.integer("huge", 5, 1, 10)).isEqualTo(5);
        assertThat(reader.integer("word", 5, 1, 10)).isEqualTo(5);
        assertThat(reader.optionalInteger("big", "no limit")).isNull();

        assertThat(warnings).containsExactly(
            "big is 5000000000; expected a whole number from -2147483648 to 2147483647, so 1 is used.",
            "huge is 99999999999999999999; expected a whole number from -2147483648 to 2147483647, so 1 is used.",
            "big is 5000000000; expected 1 to 10, so 5 is used.",
            "negative is -5000000000; expected 1 to 10, so 5 is used.",
            "huge is 99999999999999999999; expected 1 to 10, so 5 is used.",
            "word is \"lots\"; expected a whole number, so 5 is used.",
            "big is 5000000000; expected a whole number from -2147483648 to 2147483647, so no limit is used.");
    }

    @Test
    void parsedTreatsADateTimeExceptionOfTheParserAsARejection() throws Exception {
        ConfigReader reader = reader("""
            reset-at: "25:99"
            opens-at: "18:30"
            zone: Mars/Phobos
            home: Europe/Berlin
            """);

        assertThat(reader.parsed("reset-at", LocalTime.NOON, "a time such as 18:30", LocalTime::parse))
            .isEqualTo(LocalTime.NOON);
        assertThat(reader.parsed("opens-at", LocalTime.NOON, "a time such as 18:30", LocalTime::parse))
            .isEqualTo(LocalTime.of(18, 30));
        assertThat(reader.<ZoneId>parsed("zone", ZoneOffset.UTC, "UTC", "a zone such as Europe/Berlin", ZoneId::of))
            .isEqualTo(ZoneOffset.UTC);
        assertThat(reader.<ZoneId>parsed("home", ZoneOffset.UTC, "UTC", "a zone such as Europe/Berlin", ZoneId::of))
            .isEqualTo(ZoneId.of("Europe/Berlin"));

        assertThat(warnings).containsExactly(
            "reset-at is \"25:99\"; expected a time such as 18:30, so 12:00 is used.",
            "zone is \"Mars/Phobos\"; expected a zone such as Europe/Berlin, so UTC is used.");
    }

    @Test
    void parsedLetsAnyOtherFailureOfTheParserThrough() throws Exception {
        ConfigReader reader = reader("tax: \"5%\"\n");
        Function<String, Percent> broken = text -> {
            throw new IllegalStateException("parser bug");
        };

        assertThatThrownBy(() -> reader.parsed("tax", new Percent(0), PERCENT_EXPECTED, broken))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("parser bug");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anOptionalIntegerIsNullWhenAbsentAndWarnsOnAnyOtherType() throws Exception {
        ConfigReader reader = reader("""
            limit: 12
            wrong: twelve
            half: 1.5
            """);

        assertThat(reader.optionalInteger("absent", "no limit")).isNull();
        assertThat(reader.optionalInteger("limit", "no limit")).isEqualTo(12);
        assertThat(warnings).isEmpty();
        assertThat(reader.optionalInteger("wrong", "no limit")).isNull();
        assertThat(reader.optionalInteger("half", "no limit")).isNull();

        assertThat(warnings).containsExactly(
            "wrong is \"twelve\"; expected a whole number, so no limit is used.",
            "half is 1.5; expected a whole number, so no limit is used.");
        assertThat(reader.readPaths()).containsExactly("absent", "limit", "wrong", "half");
    }

    @Test
    void requiredTextReturnsASingleValueAsText() throws Exception {
        ConfigReader reader = reader("""
            host: db.example.net
            port: 3306
            secure: true
            """);

        assertThat(reader.requiredText("host", "set it")).isEqualTo("db.example.net");
        assertThat(reader.requiredText("port", "set it")).isEqualTo("3306");
        assertThat(reader.requiredText("secure", "set it")).isEqualTo("true");
        assertThat(reader.errors()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void requiredTextReportsAMissingBlankOrCompoundValueAsAnError() throws Exception {
        ConfigReader reader = reader("""
            blank: "   "
            list: [a, b]
            group:
              a: 1
            """);
        String fix = "set it to the address of the database";

        assertThat(reader.requiredText("missing", fix)).isNull();
        assertThat(reader.requiredText("blank", fix)).isNull();
        assertThat(reader.requiredText("list", fix)).isNull();
        assertThat(reader.requiredText("group", fix)).isNull();

        assertThat(reader.errors()).containsExactly(
            "missing is missing; set it to the address of the database.",
            "blank is \"   \"; set it to the address of the database.",
            "list is [a, b]; set it to the address of the database.",
            "group is a group of keys; set it to the address of the database.");
        assertThat(warnings).isEmpty();
    }

    @Test
    void strictTextFallsBackOnlyWhenAbsentAndReportsACompoundValueAsAnError() throws Exception {
        ConfigReader reader = reader("""
            mode: fast
            level: 3
            blank: ""
            list: [a, b]
            group:
              a: 1
            """);

        assertThat(reader.strictText("absent", "slow", "fast or slow")).isEqualTo("slow");
        assertThat(reader.strictText("absent", null, "fast or slow")).isNull();
        assertThat(reader.strictText("mode", "slow", "fast or slow")).isEqualTo("fast");
        assertThat(reader.strictText("level", "slow", "fast or slow")).isEqualTo("3");
        assertThat(reader.strictText("blank", "slow", "fast or slow")).isEmpty();
        assertThat(reader.errors()).isEmpty();
        assertThat(reader.strictText("list", "slow", "fast or slow")).isNull();
        assertThat(reader.strictText("group", "slow", "fast or slow")).isNull();

        assertThat(reader.errors()).containsExactly(
            "list is [a, b]; expected fast or slow.",
            "group is a group of keys; expected fast or slow.");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aListOfDurationsReadsInOrder() throws Exception {
        ConfigReader reader = reader("steps: [2s, 5s, 1m]\n");
        List<Duration> fallback = List.of(Duration.ofSeconds(1));

        List<Duration> steps = reader.durations("steps", fallback);

        assertThat(steps)
            .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(1))
            .isUnmodifiable();
        assertThat(reader.durations("absent", fallback)).isSameAs(fallback);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aListOfDurationsFallsBackAsAWholeWhenEmptyBrokenOrNotAList() throws Exception {
        ConfigReader reader = reader("""
            empty: []
            broken: [2s, soon, 15s]
            single: 5s
            group:
              a: 1s
            """);
        List<Duration> fallback = List.of(Duration.ofSeconds(30), Duration.ofDays(1));

        assertThat(reader.durations("empty", fallback)).isSameAs(fallback);
        assertThat(reader.durations("broken", fallback)).isSameAs(fallback);
        assertThat(reader.durations("single", fallback)).isSameAs(fallback);
        assertThat(reader.durations("group", fallback)).isSameAs(fallback);

        assertThat(warnings).containsExactly(
            "empty is []; expected a list of durations such as [2s, 5s, 15s], so [30s, 1d] is used.",
            "broken is [2s, soon, 15s]; expected a list of durations such as [2s, 5s, 15s], so [30s, 1d] is used.",
            "single is \"5s\"; expected a list of durations such as [2s, 5s, 15s], so [30s, 1d] is used.",
            "group is a group of keys; expected a list of durations such as [2s, 5s, 15s], so [30s, 1d] is"
                + " used.");
    }

    @Test
    void absentOrSectionIsFalseOnlyForAValueThatIsNotAGroupOfKeys() throws Exception {
        ConfigReader reader = reader("""
            group:
              a: 1
            value: 5
            list: [a]
            """);

        assertThat(reader.absentOrSection("absent")).isTrue();
        assertThat(reader.absentOrSection("group")).isTrue();
        assertThat(reader.absentOrSection("value")).isFalse();
        assertThat(reader.absentOrSection("list")).isFalse();
        assertThat(reader.isSection("value")).isFalse();
        assertThat(reader.readPaths()).containsExactly("absent", "group", "value", "list");
        assertThat(warnings).isEmpty();
    }

    @Test
    void valueReturnsTheRawEntryAndRecordsThePath() throws Exception {
        ConfigReader reader = reader("""
            group:
              a: 1
            list: [a, 2]
            word: hello
            deep:
              inner: 7
            """);

        assertThat(reader.value("group")).isInstanceOfSatisfying(ConfigurationSection.class,
            section -> assertThat(section.getKeys(false)).containsExactly("a"));
        assertThat(reader.value("list")).isEqualTo(List.of("a", 2));
        assertThat(reader.value("word")).isEqualTo("hello");
        assertThat(reader.value("deep.inner")).isEqualTo(7);
        assertThat(reader.value("absent")).isNull();
        assertThat(reader.readPaths()).containsExactly("group", "list", "word", "deep.inner", "absent");
        assertThat(warnings).isEmpty();
    }

    @Test
    void readPathsListsEachPathOnceAndFollowsLaterReads() throws Exception {
        ConfigReader reader = reader("a: 1\nb: 2\n");

        var paths = reader.readPaths();
        reader.integer("a", 0);
        reader.integer("b", 0);
        reader.integer("a", 0);

        assertThat(paths).containsExactly("a", "b").isUnmodifiable();
    }

    @Test
    void pathsAreRelativeToTheSectionGiven() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            outer:
              size: 9
              flag: sometimes
            """);
        ConfigReader reader = new ConfigReader(yaml.getConfigurationSection("outer"), warnings::add);

        assertThat(reader.integer("size", 0)).isEqualTo(9);
        assertThat(reader.bool("flag", false)).isFalse();
        assertThat(warnings).containsExactly("flag is \"sometimes\"; expected true or false, so false is used.");
    }

    @Test
    void describeQuotesTextAndNamesAGroupOfKeys() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("group:\n  a: 1\n");

        assertThat(ConfigReader.describe(yaml.getConfigurationSection("group"))).isEqualTo("a group of keys");
        assertThat(ConfigReader.describe("hello")).isEqualTo("\"hello\"");
        assertThat(ConfigReader.describe(12)).isEqualTo("12");
        assertThat(ConfigReader.describe(true)).isEqualTo("true");
        assertThat(ConfigReader.describe(List.of("a", "b"))).isEqualTo("[a, b]");
        assertThat(ConfigReader.describe(null)).isEqualTo("null");
    }

    @Test
    void describeWritesADateInUtcWhateverTheDefaultTimeZone() throws Exception {
        for (String zone : List.of("America/St_Johns", "Pacific/Auckland")) {
            JvmDefaults.withTimeZone(zone, () -> {
                assertThat(ConfigReader.describe(Date.from(Instant.parse("2026-12-31T00:00:00Z"))))
                    .isEqualTo("2026-12-31");
                assertThat(ConfigReader.describe(Date.from(Instant.parse("2026-12-31T23:59:59Z"))))
                    .isEqualTo("2026-12-31T23:59:59Z");
            });
        }
    }

    @Test
    void describeWritesAListLongerThanAHundredCharactersAsItsEntryCount() throws Exception {
        List<String> exact = List.of("x".repeat(98));
        List<Integer> many = IntStream.range(0, 500).boxed().toList();

        assertThat(String.valueOf(exact)).hasSize(100);
        assertThat(ConfigReader.describe(exact)).isEqualTo(String.valueOf(exact));
        assertThat(ConfigReader.describe(List.of("x".repeat(48), "y".repeat(49)))).isEqualTo("a list of 2 entries");
        assertThat(ConfigReader.describe(many)).isEqualTo("a list of 500 entries");
        assertThat(ConfigReader.describe(List.of("z".repeat(120)))).isEqualTo("a list of 1 entry");
    }

    @Test
    void describeCutsATextLongerThanAHundredCharacters() throws Exception {
        String hundred = "a".repeat(100);

        assertThat(ConfigReader.describe(hundred)).isEqualTo("\"" + hundred + "\"");
        assertThat(ConfigReader.describe(hundred + "b")).isEqualTo("\"" + hundred + "\"...");
    }

    @Test
    void describeDoesNotSplitASurrogatePairWhereItCutsAText() throws Exception {
        String face = "😀";
        String straddling = "a".repeat(99) + face + "b";
        String inside = "a".repeat(98) + face + "bc";

        assertThat(ConfigReader.describe(straddling)).isEqualTo("\"" + "a".repeat(99) + "\"...");
        assertThat(ConfigReader.describe(inside)).isEqualTo("\"" + "a".repeat(98) + face + "\"...");
    }

    @Test
    void aWarningAboutALongListNamesItsEntryCount() throws Exception {
        String entries = IntStream.range(0, 500).mapToObj(Integer::toString).collect(Collectors.joining(", "));
        ConfigReader reader = reader("big: [" + entries + "]\n");

        assertThat(reader.integer("big", 4)).isEqualTo(4);

        assertThat(warnings).containsExactly("big is a list of 500 entries; expected a whole number, so 4 is used.");
    }

    @Test
    void describeWritesAMapOrOtherValueLongerThanAHundredCharactersInShortForm() throws Exception {
        Map<String, String> exact = Map.of("k", "x".repeat(96));
        String digits = "1".repeat(150);

        assertThat(String.valueOf(exact)).hasSize(100);
        assertThat(ConfigReader.describe(exact)).isEqualTo(String.valueOf(exact));
        assertThat(ConfigReader.describe(Map.of("token", "x".repeat(300)))).isEqualTo("a group of 1 key");
        assertThat(ConfigReader.describe(Map.of("a", "x".repeat(60), "b", "y".repeat(60))))
            .isEqualTo("a group of 2 keys");
        assertThat(ConfigReader.describe(new BigInteger(digits))).isEqualTo("1".repeat(100) + "...");
    }

    @Test
    void aWarningAboutALongGroupInsideAListNamesItsKeyCount() throws Exception {
        ConfigReader reader = reader("""
            motd:
              - hello
              - token: %s
            """.formatted("x".repeat(300)));

        assertThat(reader.strings("motd", List.of())).containsExactly("hello");
        assertThat(warnings)
            .containsExactly("motd has a group of 1 key, which is not a single value, so it is skipped.");
    }

    @Test
    void anIntegerInsideTheRangeOrOnABoundIsKept() throws Exception {
        ConfigReader reader = reader("""
            size: 8
            low: 1
            high: 100
            """);

        assertThat(reader.integer("size", 2, 1, 100)).isEqualTo(8);
        assertThat(reader.integer("low", 2, 1, 100)).isEqualTo(1);
        assertThat(reader.integer("high", 2, 1, 100)).isEqualTo(100);
        assertThat(reader.integer("absent", 2, 1, 100)).isEqualTo(2);
        assertThat(warnings).isEmpty();
        assertThat(reader.readPaths()).containsExactly("size", "low", "high", "absent");
    }

    @Test
    void anIntegerBelowOrAboveTheRangeUsesTheFallbackAndNamesTheBounds() throws Exception {
        ConfigReader reader = reader("""
            queue:
              max-size: 0
              threads: 99
              keep: -1
            """);

        assertThat(reader.integer("queue.max-size", 8, 1, 100)).isEqualTo(8);
        assertThat(reader.integer("queue.threads", 2, 1, 32)).isEqualTo(2);
        assertThat(reader.integer("queue.keep", 5, 0, 1000)).isEqualTo(5);

        assertThat(warnings).containsExactly(
            "queue.max-size is 0; expected 1 to 100, so 8 is used.",
            "queue.threads is 99; expected 1 to 32, so 2 is used.",
            "queue.keep is -1; expected 0 to 1000, so 5 is used.");
    }

    @Test
    void anIntegerRangeRejectsAFallbackOutsideItOrBoundsInTheWrongOrder() throws Exception {
        ConfigReader reader = reader("size: 5\n");

        assertThatThrownBy(() -> reader.integer("threads", 0, 1, 32))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 0 of threads is not within 1 to 32");
        assertThatThrownBy(() -> reader.integer("size", 33, 1, 32))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 33 of size is not within 1 to 32");
        assertThatThrownBy(() -> reader.integer("size", 5, 10, 1))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 5 of size is not within 10 to 1");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anIntegerRangeOnAValueOfTheWrongTypeWarnsOnce() throws Exception {
        ConfigReader reader = reader("""
            size: lots
            group:
              a: 1
            """);

        assertThat(reader.integer("size", 8, 1, 100)).isEqualTo(8);
        assertThat(reader.integer("group", 8, 1, 100)).isEqualTo(8);

        assertThat(warnings).containsExactly(
            "size is \"lots\"; expected a whole number, so 8 is used.",
            "group is a group of keys; expected a whole number, so 8 is used.");
    }

    @Test
    void aDurationInsideTheRangeOrOnABoundIsKept() throws Exception {
        ConfigReader reader = reader("""
            timeout: 30s
            low: 5s
            high: 10m
            """);
        Duration min = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(10);

        assertThat(reader.duration("timeout", Duration.ofMinutes(1), min, max)).isEqualTo(Duration.ofSeconds(30));
        assertThat(reader.duration("low", Duration.ofMinutes(1), min, max)).isEqualTo(min);
        assertThat(reader.duration("high", Duration.ofMinutes(1), min, max)).isEqualTo(max);
        assertThat(reader.duration("absent", Duration.ofMinutes(1), min, max)).isEqualTo(Duration.ofMinutes(1));
        assertThat(warnings).isEmpty();
    }

    @Test
    void aDurationBelowOrAboveTheRangeUsesTheFallbackAndWritesTheBoundsAsDurations() throws Exception {
        ConfigReader reader = reader("""
            cache:
              timeout: 1s
            retry:
              delay: 2m
            network:
              timeout: 1ms
            cleanup:
              interval: 0s
            """);

        assertThat(reader.duration("cache.timeout", Duration.ofSeconds(30), Duration.ofSeconds(5),
            Duration.ofMinutes(10))).isEqualTo(Duration.ofSeconds(30));
        assertThat(reader.duration("retry.delay", Duration.ofSeconds(3), Duration.ZERO,
            Duration.ofMinutes(1))).isEqualTo(Duration.ofSeconds(3));
        assertThat(reader.duration("network.timeout", Duration.ofSeconds(10), Duration.ofMillis(250),
            Duration.ofMinutes(10))).isEqualTo(Duration.ofSeconds(10));
        assertThat(reader.duration("cleanup.interval", Duration.ofSeconds(15), Duration.ofSeconds(1),
            Duration.ofMinutes(10))).isEqualTo(Duration.ofSeconds(15));

        assertThat(warnings).containsExactly(
            "cache.timeout is 1s; expected 5s to 10m, so 30s is used.",
            "retry.delay is 2m; expected 0s to 1m, so 3s is used.",
            "network.timeout is 1ms; expected 250ms to 10m, so 10s is used.",
            "cleanup.interval is 0s; expected 1s to 10m, so 15s is used.");
    }

    @Test
    void aDurationRangeRejectsAFallbackOutsideItOrBoundsInTheWrongOrder() throws Exception {
        ConfigReader reader = reader("wait: 5s\n");

        assertThatThrownBy(() -> reader.duration("delay", Duration.ZERO, Duration.ofSeconds(1), Duration.ofMinutes(1)))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 0s of delay is not within 1s to 1m");
        assertThatThrownBy(() -> reader.duration("wait", Duration.ofHours(1), Duration.ofSeconds(1),
            Duration.ofMinutes(1)))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 1h of wait is not within 1s to 1m");
        assertThatThrownBy(() -> reader.duration("wait", Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofSeconds(1)))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("the fallback 30s of wait is not within 1m to 1s");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aDurationRangeOnAValueThatIsNotADurationWarnsOnce() throws Exception {
        ConfigReader reader = reader("timeout: soon\n");

        assertThat(reader.duration("timeout", Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(10)))
            .isEqualTo(Duration.ofSeconds(30));

        assertThat(warnings).containsExactly(
            "timeout is \"soon\"; expected " + Durations.EXPECTED + ", so 30s is used.");
    }

    @Test
    void aBlankValueUsesTheFallbackOfEachTypedReaderWithoutAWarning() throws Exception {
        ConfigReader reader = reader("""
            whole: ""
            ranged: " "
            limit: ""
            flag: "  "
            wait: ""
            bounded: "\\t"
            steps: ""
            names: " "
            tax: ""
            """);
        List<Duration> steps = List.of(Duration.ofSeconds(1));
        List<String> names = List.of("X");
        Percent fallback = new Percent(3);
        List<String> seen = new ArrayList<>();

        assertThat(reader.integer("whole", 4)).isEqualTo(4);
        assertThat(reader.integer("ranged", 5, 1, 10)).isEqualTo(5);
        assertThat(reader.optionalInteger("limit", "no limit")).isNull();
        assertThat(reader.bool("flag", true)).isTrue();
        assertThat(reader.duration("wait", Duration.ofMinutes(2))).isEqualTo(Duration.ofMinutes(2));
        assertThat(reader.duration("bounded", Duration.ofSeconds(9), Duration.ofSeconds(1), Duration.ofMinutes(1)))
            .isEqualTo(Duration.ofSeconds(9));
        assertThat(reader.durations("steps", steps)).isSameAs(steps);
        assertThat(reader.strings("names", names)).isSameAs(names);
        assertThat(reader.parsed("tax", fallback, PERCENT_EXPECTED, text -> {
            seen.add(text);
            return Percent.parse(text);
        })).isSameAs(fallback);

        assertThat(seen).isEmpty();
        assertThat(warnings).isEmpty();
        assertThat(reader.errors()).isEmpty();
        assertThat(reader.readPaths())
            .containsExactly("whole", "ranged", "limit", "flag", "wait", "bounded", "steps", "names", "tax");
    }

    @Test
    void aBlankValueStaysAValueInTheTextReadersAndIsAnErrorWhereTextIsRequired() throws Exception {
        ConfigReader reader = reader("""
            password: ""
            prefix: "  "
            mode: " "
            host: ""
            """);

        assertThat(reader.text("password", "x")).isEmpty();
        assertThat(reader.quoted("prefix", "x")).isEqualTo("  ");
        assertThat(reader.strictText("mode", "fast", "fast or slow")).isEqualTo(" ");
        assertThat(reader.requiredText("host", "set it to the address of the database")).isNull();

        assertThat(reader.errors()).containsExactly("host is \"\"; set it to the address of the database.");
        assertThat(warnings).isEmpty();
    }

    @Test
    void unreadKeysListsTheLeavesNoCallReadInFileOrder() throws Exception {
        ConfigReader reader = reader("""
            version: 1
            storage:
              type: sqlite
              pool:
                size: 8
                timeout: 10s
            extra: true
            empty: {}
            names: [a, b]
            """);

        assertThat(reader.unreadKeys())
            .containsExactly("version", "storage.type", "storage.pool.size", "storage.pool.timeout", "extra", "names");

        reader.integer("version", 0);
        reader.integer("storage.pool.size", 0);
        reader.children("storage");

        assertThat(reader.unreadKeys())
            .containsExactly("storage.type", "storage.pool.timeout", "extra", "names")
            .isUnmodifiable();
    }

    @Test
    void unreadKeysIsEmptyOnceEveryLeafIsRead() throws Exception {
        ConfigReader reader = reader("""
            version: 1
            storage:
              type: sqlite
              pool:
                size: 8
            hooks: [a, b]
            """);

        reader.integer("version", 0);
        reader.text("storage.type", "");
        reader.integer("storage.pool.size", 0);
        reader.strings("hooks", List.of());

        assertThat(reader.unreadKeys()).isEmpty();
        assertThat(reader.readPaths()).doesNotContain("storage", "storage.pool");
    }

    @Test
    void unreadKeysListsTheLeavesOfAGroupReadWholeButNotTheEntriesOfAList() throws Exception {
        ConfigReader reader = reader("""
            levels:
              high: 2
              low: 1
            entries:
              - name: first
                count: 3
              - name: second
            """);

        assertThat(reader.value("levels")).isInstanceOf(ConfigurationSection.class);
        assertThat(reader.value("entries")).isInstanceOf(List.class);

        assertThat(reader.unreadKeys()).containsExactly("levels.high", "levels.low");
    }

    @Test
    void aWholeNumberReadsInsideAndPastTheIntRange() throws Exception {
        ConfigReader reader = reader("""
            small: 5
            big: 99999999999
            negative: -99999999999
            """);

        assertThat(reader.whole("small", 0L)).isEqualTo(5L);
        assertThat(reader.whole("big", 0L)).isEqualTo(99_999_999_999L);
        assertThat(reader.whole("negative", 0L)).isEqualTo(-99_999_999_999L);
        assertThat(reader.whole("absent", 7L)).isEqualTo(7L);
        assertThat(warnings).isEmpty();
        assertThat(reader.readPaths()).containsExactly("small", "big", "negative", "absent");
    }

    @Test
    void aWholeNumberOfTheWrongTypeOrPastTheLongRangeUsesTheFallback() throws Exception {
        ConfigReader reader = reader("""
            word: lots
            half: 1.5
            huge: 99999999999999999999
            quoted: "12"
            group:
              a: 1
            """);

        assertThat(reader.whole("word", 3L)).isEqualTo(3L);
        assertThat(reader.whole("half", 3L)).isEqualTo(3L);
        assertThat(reader.whole("huge", 3L)).isEqualTo(3L);
        assertThat(reader.whole("quoted", 3L)).isEqualTo(3L);
        assertThat(reader.whole("group", 3L)).isEqualTo(3L);

        assertThat(warnings).containsExactly(
            "word is \"lots\"; expected a whole number, so 3 is used.",
            "half is 1.5; expected a whole number, so 3 is used.",
            "huge is 99999999999999999999; expected a whole number from -9223372036854775808 to"
                + " 9223372036854775807, so 3 is used.",
            "quoted is \"12\"; expected a whole number, so 3 is used.",
            "group is a group of keys; expected a whole number, so 3 is used.");
    }

    @Test
    void aDecimalReadsFromADecimalOrAWholeNumber() throws Exception {
        ConfigReader reader = reader("""
            ratio: 0.25
            weight: 2
            big: 99999999999
            negative: -1.5
            """);

        assertThat(reader.decimal("ratio", 1.0)).isEqualTo(0.25);
        assertThat(reader.decimal("weight", 1.0)).isEqualTo(2.0);
        assertThat(reader.decimal("big", 1.0)).isEqualTo(99_999_999_999.0);
        assertThat(reader.decimal("negative", 1.0)).isEqualTo(-1.5);
        assertThat(reader.decimal("absent", 1.5)).isEqualTo(1.5);
        assertThat(warnings).isEmpty();
        assertThat(reader.readPaths()).containsExactly("ratio", "weight", "big", "negative", "absent");
    }

    @Test
    void aDecimalThatIsTextOrNotFiniteUsesTheFallback() throws Exception {
        ConfigReader reader = reader("""
            quoted: "1.5"
            word: half
            up: .inf
            down: -.inf
            nothing: .nan
            list: [1, 2]
            """);

        assertThat(reader.decimal("quoted", 1.0)).isEqualTo(1.0);
        assertThat(reader.decimal("word", 1.0)).isEqualTo(1.0);
        assertThat(reader.decimal("up", 1.0)).isEqualTo(1.0);
        assertThat(reader.decimal("down", 1.0)).isEqualTo(1.0);
        assertThat(reader.decimal("nothing", 1.0)).isEqualTo(1.0);
        assertThat(reader.decimal("list", 0.5)).isEqualTo(0.5);

        assertThat(warnings).containsExactly(
            "quoted is \"1.5\"; expected a number, so 1.0 is used.",
            "word is \"half\"; expected a number, so 1.0 is used.",
            "up is Infinity; expected a number, so 1.0 is used.",
            "down is -Infinity; expected a number, so 1.0 is used.",
            "nothing is NaN; expected a number, so 1.0 is used.",
            "list is [1, 2]; expected a number, so 0.5 is used.");
    }

    @Test
    void aChoiceMatchesTheConstantNameInAnyLetterCaseAfterTrimming() throws Exception {
        ConfigReader reader = reader("""
            lower: public
            upper: HIDDEN
            mixed: PrIvAtE
            padded: "  public  "
            underscored: Oldest_First
            """);

        assertThat(reader.choice("lower", Visibility.PRIVATE)).isEqualTo(Visibility.PUBLIC);
        assertThat(reader.choice("upper", Visibility.PRIVATE)).isEqualTo(Visibility.HIDDEN);
        assertThat(reader.choice("mixed", Visibility.HIDDEN)).isEqualTo(Visibility.PRIVATE);
        assertThat(reader.choice("padded", Visibility.PRIVATE)).isEqualTo(Visibility.PUBLIC);
        assertThat(reader.choice("underscored", Order.NEWEST_FIRST)).isEqualTo(Order.OLDEST_FIRST);
        assertThat(reader.choice("absent", Visibility.HIDDEN)).isEqualTo(Visibility.HIDDEN);
        assertThat(warnings).isEmpty();
        assertThat(reader.readPaths()).containsExactly("lower", "upper", "mixed", "padded", "underscored", "absent");
    }

    @Test
    void aChoiceThatNamesNoConstantListsEveryConstantInLowerCase() throws Exception {
        ConfigReader reader = reader("""
            visibility: secret
            side: middle
            order: oldest-first
            only: other
            """);

        assertThat(reader.choice("visibility", Visibility.PUBLIC)).isEqualTo(Visibility.PUBLIC);
        assertThat(reader.choice("side", Side.LEFT)).isEqualTo(Side.LEFT);
        assertThat(reader.choice("order", Order.NEWEST_FIRST)).isEqualTo(Order.NEWEST_FIRST);
        assertThat(reader.choice("only", Single.ONLY)).isEqualTo(Single.ONLY);

        assertThat(warnings).containsExactly(
            "visibility is \"secret\"; expected private, public or hidden, so public is used.",
            "side is \"middle\"; expected left or right, so left is used.",
            "order is \"oldest-first\"; expected oldest_first or newest_first, so newest_first is used.",
            "only is \"other\"; expected only, so only is used.");
    }

    @Test
    void aChoiceThatIsNotTextUsesTheFallback() throws Exception {
        ConfigReader reader = reader("""
            number: 1
            flag: true
            list: [left]
            group:
              left: true
            """);

        assertThat(reader.choice("number", Side.RIGHT)).isEqualTo(Side.RIGHT);
        assertThat(reader.choice("flag", Side.RIGHT)).isEqualTo(Side.RIGHT);
        assertThat(reader.choice("list", Side.RIGHT)).isEqualTo(Side.RIGHT);
        assertThat(reader.choice("group", Side.RIGHT)).isEqualTo(Side.RIGHT);

        assertThat(warnings).containsExactly(
            "number is 1; expected left or right, so right is used.",
            "flag is true; expected left or right, so right is used.",
            "list is [left]; expected left or right, so right is used.",
            "group is a group of keys; expected left or right, so right is used.");
    }

    @Test
    void aChoiceNamedLikeAYamlSwitchWordMatchesInQuotesAndAsksForQuotesOtherwise() throws Exception {
        ConfigReader reader = reader("""
            quoted: "off"
            mode: off
            power: on
            agree: yes
            decline: no
            """);

        assertThat(reader.choice("quoted", Switch.AUTO)).isEqualTo(Switch.OFF);
        assertThat(reader.choice("mode", Switch.AUTO)).isEqualTo(Switch.AUTO);
        assertThat(reader.choice("power", Switch.AUTO)).isEqualTo(Switch.AUTO);
        assertThat(reader.choice("agree", Answer.LATER)).isEqualTo(Answer.LATER);
        assertThat(reader.choice("decline", Answer.LATER)).isEqualTo(Answer.LATER);

        String hint = " YAML reads an unquoted yes, no, on or off as true or false, so write the value in quotes,"
            + " for example ";
        assertThat(warnings).containsExactly(
            "mode is false; expected on, off or auto, so auto is used." + hint + "\"off\".",
            "power is true; expected on, off or auto, so auto is used." + hint + "\"on\".",
            "agree is true; expected yes or later, so later is used." + hint + "\"yes\".",
            "decline is false; expected yes or later, so later is used.");
    }

    @Test
    void aChoiceReadsTheEnumOfAFallbackWithItsOwnBody() throws Exception {
        ConfigReader reader = reader("""
            shape: square
            other: oval
            """);

        assertThat(Shape.ROUND.getClass()).isNotEqualTo(Shape.class);
        assertThat(reader.choice("shape", Shape.ROUND)).isEqualTo(Shape.SQUARE);
        assertThat(reader.choice("other", Shape.ROUND)).isSameAs(Shape.ROUND);
        assertThat(warnings).containsExactly("other is \"oval\"; expected round or square, so round is used.");
    }

    @Test
    void aChoiceMatchesAndWritesTheLetterIUnderATurkishDefaultLocale() throws Exception {
        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            ConfigReader reader = reader("""
                lower: private
                upper: HIDDEN
                wrong: visible
                """);

            assertThat(reader.choice("lower", Visibility.PUBLIC)).isEqualTo(Visibility.PRIVATE);
            assertThat(reader.choice("upper", Visibility.PUBLIC)).isEqualTo(Visibility.HIDDEN);
            assertThat(reader.choice("wrong", Visibility.PUBLIC)).isEqualTo(Visibility.PUBLIC);
        });

        assertThat(warnings).containsExactly(
            "wrong is \"visible\"; expected private, public or hidden, so public is used.");
    }

    @Test
    void aBlankValueUsesTheFallbackOfTheWholeDecimalAndChoiceReaders() throws Exception {
        ConfigReader reader = reader("""
            count: ""
            ratio: " "
            side: "  "
            """);

        assertThat(reader.whole("count", 9L)).isEqualTo(9L);
        assertThat(reader.decimal("ratio", 0.5)).isEqualTo(0.5);
        assertThat(reader.choice("side", Side.RIGHT)).isEqualTo(Side.RIGHT);
        assertThat(warnings).isEmpty();
        assertThat(reader.readPaths()).containsExactly("count", "ratio", "side");
    }

    private enum Visibility { PRIVATE, PUBLIC, HIDDEN }

    private enum Side { LEFT, RIGHT }

    private enum Order { OLDEST_FIRST, NEWEST_FIRST }

    private enum Single { ONLY }

    private enum Switch { ON, OFF, AUTO }

    private enum Answer { YES, LATER }

    private enum Shape {
        ROUND { },
        SQUARE
    }

    private void forEachZone(JvmDefaults.Block block) throws Exception {
        for (String zone : List.of("America/St_Johns", "Pacific/Auckland")) {
            warnings.clear();
            JvmDefaults.withTimeZone(zone, block);
        }
    }

    private ConfigReader reader(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return new ConfigReader(yaml, warnings::add);
    }
}
