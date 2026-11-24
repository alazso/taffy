package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.platform.Text;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ConditionRegistryTest {

    private record Problem(String path, String message, @Nullable String fix) {
    }

    private record Report(String source, Throwable error) {
    }

    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<Report> reports = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, String> placeholders = new LinkedHashMap<>();
    private final Player player = mock(Player.class);
    private @Nullable RegionProvider regions;

    // Stands in for Text and Messages: a deny text shows as deny:<text> and a message key as key:<key>, each followed
    // by the number of values it was given.
    private final ConditionRegistry.Denials denials = new ConditionRegistry.Denials() {
        @Override
        public Component custom(String template, Player viewer, TagResolver[] values) {
            assertThat(viewer).isSameAs(player);
            return Component.text("deny:" + template + ":" + values.length);
        }

        @Override
        public Component keyed(String key, Player viewer, TagResolver[] values) {
            assertThat(viewer).isSameAs(player);
            return Component.text("key:" + key + ":" + values.length);
        }
    };

    private final ConditionRegistry registry;

    ConditionRegistryTest() {
        errors.install((source, error) -> reports.add(new Report(source, error)));
        registry = new ConditionRegistry(log.logger, errors, denials,
            (input, viewer) -> placeholders.getOrDefault(input, input), () -> regions);
    }

    private static Object list(String yaml) {
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(yaml);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return file.get("conditions");
    }

    private Condition build(String yaml) {
        return registry.build(list(yaml), "conditions", (path, message, fix) ->
            problems.add(new Problem(path, message, fix)));
    }

    private static String reason(ConditionResult result) {
        assertThat(result.passed()).isFalse();
        return PlainTextComponentSerializer.plainText().serialize(result.reason());
    }

    private void inWorld(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(player.getWorld()).thenReturn(world);
    }

    private static RegionProvider regionsOf(boolean available, String... ids) {
        return new RegionProvider() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public List<String> regionsAt(Location location) {
                return List.of(ids);
            }
        };
    }

    @Test
    void aNewRegistryHoldsTheFourBuiltInTypes() {
        assertThat(registry.types()).containsExactly("papi", "permission", "region", "world");
        assertThat(registry.isRegistered("Permission")).isTrue();
        assertThat(registry.isRegistered("economy")).isFalse();
        assertThatThrownBy(() -> registry.types().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anAbsentAndAnEmptyListPass() {
        assertThat(registry.build(null, "conditions", (path, message, fix) -> {
            throw new AssertionError(path);
        }).test(player)).isSameAs(ConditionResult.pass());
        assertThat(build("conditions: []").test(player).passed()).isTrue();
        assertThat(problems).isEmpty();
    }

    @Test
    void aPermissionConditionPassesWithTheNodeAndDeniesWithItsMessageKeyWithoutIt() {
        Condition condition = build("""
            conditions:
              - type: permission
                permission: shop.vip
            """);

        assertThat(problems).isEmpty();
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.permission:0");

        when(player.hasPermission("shop.vip")).thenReturn(true);

        assertThat(condition.test(player)).isSameAs(ConditionResult.pass());
    }

    @Test
    void aDenyTextTakesThePlaceOfTheMessageKey() {
        Condition condition = build("""
            conditions:
              - type: Permission
                permission: shop.vip
                deny: "<red>VIP only."
            """);

        assertThat(problems).isEmpty();
        assertThat(reason(condition.test(player))).isEqualTo("deny:<red>VIP only.:0");
    }

    @Test
    void theConditionsAreTestedInFileOrderAndStopAtTheFirstDenial() {
        AtomicInteger tested = new AtomicInteger();
        registry.register("counted", config -> viewer -> {
            tested.incrementAndGet();
            return ConditionResult.pass();
        });
        Condition condition = build("""
            conditions:
              - type: counted
              - type: world
                worlds: [spawn]
                deny: first
              - type: permission
                permission: shop.vip
                deny: second
              - type: counted
            """);
        inWorld("nether");

        assertThat(reason(condition.test(player))).isEqualTo("deny:first:0");
        assertThat(tested).hasValue(1);

        inWorld("spawn");

        assertThat(reason(condition.test(player))).isEqualTo("deny:second:0");
        assertThat(tested).hasValue(2);

        when(player.hasPermission("shop.vip")).thenReturn(true);

        assertThat(condition.test(player).passed()).isTrue();
        assertThat(tested).hasValue(4);
    }

    @Test
    void aWorldConditionMatchesTheWorldNameExactly() {
        Condition condition = build("""
            conditions:
              - type: world
                worlds: [spawn, mine]
            """);

        inWorld("mine");
        assertThat(condition.test(player).passed()).isTrue();

        inWorld("Mine");
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.world:0");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "equals   | vip  | VIP   | true",
        "==       | vip  | vip   | true",
        "=        | vip  | vop   | false",
        "!=       | vip  | vop   | true",
        "NE       | vip  | VIP   | false",
        "not      | 10   | 10.0  | true",
        "contains | Vi   | a vip | true",
        "contains | x    | a vip | false",
        ">        | 10   | 10.5  | true",
        "gt       | 10   | 10    | false",
        ">=       | 10   | ' 10 '| true",
        "GTE      | 10   | 9     | false",
        "<        | 10   | 9     | true",
        "lt       | 10   | 10    | false",
        "<=       | 10   | 1e1   | true",
        "lte      | 10   | 11    | false",
        ">=       | 10   | 1,000 | false",
        ">=       | 10   | none  | false",
        "<        | 10   | NaN   | false"})
    void aPlaceholderComparisonAppliesItsOperator(String operator, String value, String resolved, boolean passes) {
        placeholders.put("%rank%", resolved);
        Condition condition = build("""
            conditions:
              - type: papi
                placeholder: "%%rank%%"
                operator: "%s"
                value: "%s"
            """.formatted(operator, value));

        assertThat(problems).isEmpty();
        ConditionResult result = condition.test(player);
        assertThat(result.passed()).isEqualTo(passes);
        if (!passes) {
            assertThat(reason(result)).isEqualTo("key:condition.requirement-not-met:0");
        }
    }

    @Test
    void aPlaceholderComparisonWithoutAnOperatorComparesForEqualityAndReadsANumberValueAsText() {
        placeholders.put("%level%", "30");
        Condition condition = build("""
            conditions:
              - type: papi
                placeholder: "%level%"
                value: 30
            """);

        assertThat(problems).isEmpty();
        assertThat(condition.test(player).passed()).isTrue();
    }

    @Test
    void aPlaceholderThatIsNotFilledInIsComparedAsWritten() {
        Condition equal = build("""
            conditions:
              - type: papi
                placeholder: "%level%"
                operator: ">="
                value: 30
            """);
        Condition unequal = build("""
            conditions:
              - type: papi
                placeholder: "%level%"
                operator: "!="
                value: 30
            """);

        assertThat(equal.test(player).passed()).isFalse();
        assertThat(unequal.test(player).passed()).isTrue();
    }

    @Test
    void aRegionConditionMatchesARegionIdWithoutRegardToCase() {
        Condition condition = build("""
            conditions:
              - type: region
                regions: [Spawn, market]
            """);
        when(player.getLocation()).thenReturn(mock(Location.class));

        regions = regionsOf(true, "wild", "spawn");
        assertThat(condition.test(player).passed()).isTrue();

        regions = regionsOf(true, "wild");
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.region:0");

        regions = regionsOf(true);
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.region:0");
    }

    @Test
    void aRegionConditionDeniesWhileNoRegionProviderIsAvailable() {
        Condition condition = build("""
            conditions:
              - type: region
                regions: [spawn]
            """);

        assertThat(reason(condition.test(player))).isEqualTo("key:condition.region-unavailable:0");

        regions = regionsOf(false, "spawn");

        assertThat(reason(condition.test(player))).isEqualTo("key:condition.region-unavailable:0");
    }

    @Test
    void anUnknownTypeIsAnErrorWithItsPathAndIsLeftOut() {
        Condition condition = build("""
            conditions:
              - type: permission
                permission: shop.vip
                deny: kept
              - type: economy
                amount: 100
            """);

        assertThat(problems).containsExactly(new Problem("conditions[1].type",
            "is \"economy\", which is not a registered condition type",
            "use one of papi, permission, region, world"));
        assertThat(reason(condition.test(player))).isEqualTo("deny:kept:0");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aListThatIsNotAListAndAnEntryThatIsNotAGroupOfKeysAreErrors() {
        assertThat(build("conditions: permission").test(player).passed()).isTrue();
        assertThat(build("""
            conditions:
              - permission
              -
              - [a]
              - 5
              - type: [permission]
              - permission: shop.vip
            """).test(player).passed()).isTrue();

        assertThat(problems).containsExactly(
            new Problem("conditions", "is \"permission\"; expected a list of conditions", null),
            new Problem("conditions[0]", "is \"permission\", which is not a group of keys", null),
            new Problem("conditions[1]", "is an empty entry, which is not a group of keys", null),
            new Problem("conditions[2]", "is a list, which is not a group of keys", null),
            new Problem("conditions[3]", "is 5, which is not a group of keys", null),
            new Problem("conditions[4].type", "is a list, which is not a registered condition type",
                "use one of papi, permission, region, world"),
            new Problem("conditions[5].type", "is missing", "name one of papi, permission, region, world"));
    }

    @Test
    void aBadKeyOfABuiltInTypeIsAnErrorAndItsEntryIsLeftOut() {
        Condition condition = build("""
            conditions:
              - type: permission
              - type: permission
                permission: ""
              - type: permission
                permission: [a, b]
              - type: permission
                permission: shop.vip
                deny: [a]
              - type: world
              - type: world
                worlds: []
              - type: world
                worlds: spawn
              - type: world
                worlds: [spawn, [a], {b: c}]
              - type: region
                regions: {a: b}
              - type: papi
              - type: papi
                placeholder: "%level%"
                operator: at-least
                value: 5
              - type: papi
                placeholder: "%level%"
                operator: ">="
                value: five
              - type: papi
                placeholder: "%level%"
                operator: [">="]
                value: {a: b}
            """);

        assertThat(problems).containsExactly(
            new Problem("conditions[0].permission", "is missing", null),
            new Problem("conditions[1].permission", "is missing", null),
            new Problem("conditions[2].permission", "is a list; expected a single value", null),
            new Problem("conditions[3].deny", "is a list; expected a text", null),
            new Problem("conditions[4].worlds", "is missing", "list at least one name"),
            new Problem("conditions[5].worlds", "is an empty list", "list at least one name"),
            new Problem("conditions[6].worlds", "is \"spawn\"; expected a list", null),
            new Problem("conditions[7].worlds[1]", "is a list; expected a single value", null),
            new Problem("conditions[7].worlds[2]", "is a group of keys; expected a single value", null),
            new Problem("conditions[8].regions", "is a group of keys; expected a list", null),
            new Problem("conditions[9].placeholder", "is missing", null),
            new Problem("conditions[9].value", "is missing", null),
            new Problem("conditions[10].operator", "is \"at-least\", which is not an operator",
                "use one of equals, !=, contains, >, >=, < and <="),
            new Problem("conditions[11].value",
                "is \"five\", which is not a number, and the operator >= compares numbers", null),
            new Problem("conditions[12].operator", "is a list; expected a single value", null),
            new Problem("conditions[12].value", "is a group of keys; expected a single value", null));
        assertThat(condition.test(player).passed()).isTrue();
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aSectionEntryIsReadLikeAMap() {
        YamlConfiguration file = new YamlConfiguration();
        file.set("entry.type", "permission");
        file.set("entry.permission", "shop.vip");
        List<Object> entries = List.of(file.getConfigurationSection("entry"));

        Condition condition = registry.build(entries, "conditions", (path, message, fix) ->
            problems.add(new Problem(path, message, fix)));

        assertThat(problems).isEmpty();
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.permission:0");
    }

    @Test
    void aPluginTypeReadsItsKeysAndDeniesWithItsOwnKeyAndValues() {
        registry.register("Level", config -> {
            assertThat(config.type()).isEqualTo("level");
            assertThat(config.path()).isEqualTo("conditions[0]");
            assertThat(config.value("missing")).isNull();
            assertThat(config.text("missing")).isNull();
            assertThat(config.texts("missing")).isEmpty();
            assertThat(config.texts("tags")).containsExactly("a", "2", "true");
            int level = Integer.parseInt(config.requiredText("level"));
            ConditionDenial denial = config.denial("condition.exp");
            return viewer -> viewer.getLevel() >= level ? ConditionResult.pass()
                : denial.result(viewer, Placeholder.unparsed("level", String.valueOf(level)));
        });
        Condition condition = build("""
            conditions:
              - type: level
                level: 30
                tags: [a, 2, true]
            """);

        assertThat(problems).isEmpty();
        assertThat(registry.isRegistered("LEVEL")).isTrue();
        assertThat(reason(condition.test(player))).isEqualTo("key:condition.exp:1");

        when(player.getLevel()).thenReturn(30);

        assertThat(condition.test(player).passed()).isTrue();
    }

    @Test
    void aRegisteredTypeTakesThePlaceOfABuiltInOne() {
        registry.register("permission", config -> viewer -> ConditionResult.pass());

        assertThat(build("""
            conditions:
              - type: permission
            """).test(player).passed()).isTrue();
        assertThat(problems).isEmpty();
    }

    @Test
    void aTypeThatReportsAnErrorIsLeftOutEvenWhenItBuildsACondition() {
        registry.register("strict", config -> {
            config.error("amount", "must be above 0", "write a positive amount");
            return viewer -> ConditionResult.denied(Component.text("never"));
        });

        assertThat(build("""
            conditions:
              - type: strict
            """).test(player).passed()).isTrue();
        assertThat(problems).containsExactly(
            new Problem("conditions[0].amount", "must be above 0", "write a positive amount"));
    }

    @Test
    void aTypeThatBuildsNothingWithoutAnErrorIsAnErrorOfTheEntry() {
        registry.register("empty", config -> null);

        assertThat(build("""
            conditions:
              - type: empty
            """).test(player).passed()).isTrue();
        assertThat(problems).containsExactly(
            new Problem("conditions[0]", "could not be built by the condition type empty", null));
    }

    @Test
    void aTypeThatThrowsWhileItBuildsIsAnErrorLoggedAndReported() {
        IllegalStateException thrown = new IllegalStateException("boom");
        registry.register("broken", config -> {
            throw thrown;
        });

        assertThat(build("""
            conditions:
              - type: broken
            """).test(player).passed()).isTrue();

        assertThat(problems).containsExactly(new Problem("conditions[0]",
            "could not be built, since the condition type broken failed (" + thrown + ")", null));
        assertThat(log.lines()).containsExactly(
            "WARNING The condition type broken could not build conditions[0] (" + thrown + ").");
        assertThat(log.records.getFirst().getThrown()).isSameAs(thrown);
        assertThat(reports).containsExactly(new Report("condition.broken", thrown));
    }

    @Test
    void aConditionThatThrowsDeniesWithTheDenyTextOrTheNotMetKeyAndIsLoggedOnce() {
        IllegalStateException thrown = new IllegalStateException("boom");
        registry.register("broken", config -> viewer -> {
            throw thrown;
        });
        Condition plain = build("""
            conditions:
              - type: broken
            """);
        Condition withDeny = build("""
            conditions:
              - type: broken
                deny: Not now.
            """);

        assertThat(reason(plain.test(player))).isEqualTo("key:condition.requirement-not-met:0");
        assertThat(reason(withDeny.test(player))).isEqualTo("deny:Not now.:0");

        assertThat(problems).isEmpty();
        assertThat(log.lines()).containsExactly("WARNING The condition conditions[0] of type broken could not be"
            + " tested, so it denies (" + thrown + ").");
        assertThat(log.records.getFirst().getThrown()).isSameAs(thrown);
        assertThat(reports).containsExactly(new Report("condition.broken", thrown),
            new Report("condition.broken", thrown));
    }

    @Test
    void aConditionThatReturnsNoResultDenies() {
        registry.register("silent", config -> viewer -> null);

        assertThat(reason(build("""
            conditions:
              - type: silent
            """).test(player))).isEqualTo("key:condition.requirement-not-met:0");
        assertThat(reports).singleElement()
            .satisfies(report -> assertThat(report.error()).isInstanceOf(NullPointerException.class));
    }

    @Test
    void aDenialThatCannotBeRenderedAfterAThrowIsAnEmptyReason() {
        ConditionRegistry unrendered = new ConditionRegistry(log.logger, errors, new ConditionRegistry.Denials() {
            @Override
            public Component custom(String template, Player viewer, TagResolver[] values) {
                throw new IllegalArgumentException(template);
            }

            @Override
            public Component keyed(String key, Player viewer, TagResolver[] values) {
                throw new IllegalArgumentException(key);
            }
        }, (input, viewer) -> input, () -> null);
        unrendered.register("broken", config -> viewer -> {
            throw new IllegalStateException("boom");
        });

        ConditionResult result = unrendered.build(list("""
            conditions:
              - type: broken
            """), "conditions", (path, message, fix) -> problems.add(new Problem(path, message, fix))).test(player);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo(Component.empty());
    }

    @Test
    void anErrorThatIsNotALinkageErrorPropagates() {
        registry.register("fatal", config -> viewer -> {
            throw new StackOverflowError();
        });
        Condition condition = build("""
            conditions:
              - type: fatal
            """);

        assertThatThrownBy(() -> condition.test(player)).isInstanceOf(StackOverflowError.class);
    }

    @Test
    void aLongValueIsCutInAnError() {
        build("conditions: " + "x".repeat(150));

        assertThat(problems).containsExactly(new Problem("conditions",
            "is \"" + "x".repeat(100) + "...\"; expected a list of conditions", null));
    }

    @Test
    void nullArgumentsAndABlankNameAreRefused() {
        assertThatThrownBy(() -> registry.register(null, config -> null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.register("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.register(" ", config -> null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.isRegistered(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.build(List.of(), null, (path, message, fix) -> { }))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.build(List.of(), "conditions", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> build("conditions: []").test(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConditionResult.denied(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConditionResult(true, Component.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRegistryOnTextAndMessagesRendersTheDenyTextAndTheMessageOfTheKey(@TempDir Path folder) {
        Messages messages = Messages.load(folder, () -> new ByteArrayInputStream("""
            condition:
              permission: "<red>No permission for <node>."
            """.getBytes(StandardCharsets.UTF_8)), log.logger);
        ConditionRegistry real = ConditionRegistry.create(log.logger, errors, new Text(log.logger), messages,
            () -> null);
        real.register("node", config -> {
            ConditionDenial denial = config.denial(ConditionRegistry.PERMISSION_KEY);
            return viewer -> denial.result(viewer, Placeholder.unparsed("node", "shop.vip"));
        });

        ConditionResult keyed = real.build(list("""
            conditions:
              - type: node
            """), "conditions", (path, message, fix) -> problems.add(new Problem(path, message, fix))).test(player);
        ConditionResult custom = real.build(list("""
            conditions:
              - type: node
                deny: "<green>Ask for {node}."
            """), "conditions", (path, message, fix) -> problems.add(new Problem(path, message, fix))).test(player);

        assertThat(problems).isEmpty();
        assertThat(reason(keyed)).isEqualTo("No permission for shop.vip.");
        assertThat(reason(custom)).isEqualTo("Ask for shop.vip.");
        assertThat(real.types()).containsExactly("node", "papi", "permission", "region", "world");
    }

    @Test
    void createRefusesNullArguments(@TempDir Path folder) {
        Text text = new Text(log.logger);
        Messages messages = Messages.load(folder,
            () -> new ByteArrayInputStream("a: b".getBytes(StandardCharsets.UTF_8)), log.logger);

        assertThatThrownBy(() -> ConditionRegistry.create(null, errors, text, messages, () -> null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConditionRegistry.create(log.logger, null, text, messages, () -> null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConditionRegistry.create(log.logger, errors, null, messages, () -> null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConditionRegistry.create(log.logger, errors, text, null, () -> null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConditionRegistry.create(log.logger, errors, text, messages, null))
            .isInstanceOf(NullPointerException.class);
    }
}
