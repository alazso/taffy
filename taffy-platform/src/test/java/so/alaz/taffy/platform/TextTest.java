package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TextTest {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PLACEHOLDER_API_WARNING =
        "WARNING PlaceholderAPI failed while resolving placeholders, so the text is shown without them.";
    private static final String MINI_PLACEHOLDERS_WARNING =
        "WARNING MiniPlaceholders failed, so the text is shown without its placeholders.";

    private final CapturedLog log = new CapturedLog();
    private final Player viewer = mock(Player.class);
    private final List<Player> passViewers = new ArrayList<>();
    private final List<String> passInputs = new ArrayList<>();

    // Stands in for PlaceholderAPI: records each call and resolves %rank% and %x%.
    private final BiFunction<Player, String, String> pass = (player, input) -> {
        passViewers.add(player);
        passInputs.add(input);
        return input.replace("%rank%", "<green>VIP").replace("%x%", "1");
    };

    private static final BiFunction<Player, String, String> THROWING_PASS = (player, input) -> {
        throw new IllegalStateException("expansion failed");
    };

    // Stands in for MiniPlaceholders: <who> names the parse target, <server> is a global tag.
    private TagResolver expansions() {
        return TagResolver.resolver(
            TagResolver.resolver("who", (arguments, context) ->
                Tag.selfClosingInserting(Component.text(context.target() == viewer ? "viewer" : "nobody"))),
            TagResolver.resolver("server", (arguments, context) -> Tag.selfClosingInserting(Component.text("lobby"))));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static final String INJECTED =
        "<click:run_command:'/pay Mallory 100000'><hover:show_text:'Free reward'>Click me";

    // Stands in for PlaceholderAPI with one expansion per map key.
    private static BiFunction<Player, String, String> expansions(Map<String, String> outputs) {
        return (player, input) -> {
            String result = input;
            for (Map.Entry<String, String> output : outputs.entrySet()) {
                result = result.replace(output.getKey(), output.getValue());
            }
            return result;
        };
    }

    /** The style each piece of text is shown in, parent styles applied, in reading order. */
    private static List<Map.Entry<String, Style>> runs(Component component) {
        List<Map.Entry<String, Style>> runs = new ArrayList<>();
        collectRuns(component, Style.empty(), runs);
        return runs;
    }

    private static void collectRuns(Component component, Style inherited, List<Map.Entry<String, Style>> runs) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            runs.add(Map.entry(text.content(), style));
        }
        component.children().forEach(child -> collectRuns(child, style, runs));
    }

    private static Style styleOf(Component component, String content) {
        return runs(component).stream()
            .filter(run -> run.getKey().equals(content))
            .findFirst().orElseThrow(() -> new AssertionError(content + " not in " + runs(component)))
            .getValue();
    }

    private static void assertNoEvents(Component component) {
        assertThat(runs(component)).isNotEmpty().allSatisfy(run -> {
            assertThat(run.getValue().clickEvent()).isNull();
            assertThat(run.getValue().hoverEvent()).isNull();
            assertThat(run.getValue().insertion()).isNull();
        });
    }

    private void assertOneWarning(String line, Class<? extends Throwable> thrown) {
        assertThat(log.lines()).containsExactly(line);
        LogRecord record = log.records.get(0);
        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
        assertThat(record.getThrown()).isInstanceOf(thrown);
    }

    @Test
    void rendersMiniMessageWhenNeitherIntegrationIsPresent() {
        Text text = new Text(log.logger, null, null);

        assertThat(text.render("<red>Hi")).isEqualTo(Component.text("Hi", NamedTextColor.RED));
        assertThat(text.render("<red>Hi", viewer)).isEqualTo(Component.text("Hi", NamedTextColor.RED));
        assertThat(text.resolve("%x%", viewer)).isEqualTo("%x%");
    }

    @Test
    void nullViewerSkipsThePlaceholderApiPass() {
        Text text = new Text(log.logger, pass, null);

        assertThat(plain(text.render("%x%"))).isEqualTo("%x%");
        assertThat(plain(text.render("%x%", null))).isEqualTo("%x%");
        assertThat(plain(text.render("%x% <name>", null, Placeholder.unparsed("name", "Ann")))).isEqualTo("%x% Ann");
        assertThat(text.resolve("%x%", null)).isEqualTo("%x%");
        assertThat(text.plain("%x%", null)).isEqualTo("%x%");
        assertThat(passInputs).isEmpty();
    }

    @Test
    void eachPlaceholderIsResolvedOnItsOwnForTheViewer() {
        Text text = new Text(log.logger, pass, null);

        Component rendered = text.render("<red>%x% joined %x%", viewer);

        assertThat(rendered).isEqualTo(Component.text("1 joined 1", NamedTextColor.RED));
        assertThat(passViewers).containsExactly(viewer, viewer);
        assertThat(passInputs).containsExactly("%x%", "%x%");
    }

    @Test
    void aRunPlaceholderApiReturnsUnchangedStaysAsWrittenAndItsClosingSignOpensTheNextRun() {
        Text text = new Text(log.logger, pass, null);

        assertThat(plain(text.render("50% off %x% and 20%% more %nope%", viewer)))
            .isEqualTo("50% off 1 and 20%% more %nope%");
        assertThat(passInputs).containsExactly("% off %", "%x%", "% more %", "%nope%");
    }

    @Test
    void aTagInPlaceholderOutputIsShownAsWritten() {
        Text text = new Text(log.logger, expansions(Map.of("%nick%", INJECTED, "%clan%", "<name><red>X")), null);

        Component injected = text.render("<gray>Hi %nick%", viewer);
        Component named = text.render("%clan% {name}", viewer, Placeholder.unparsed("name", "Ann"));

        assertThat(plain(injected)).isEqualTo("Hi " + INJECTED);
        assertNoEvents(injected);
        assertThat(injected).isEqualTo(Component.text("Hi " + INJECTED, NamedTextColor.GRAY));
        assertThat(named).isEqualTo(Component.text("<name><red>X Ann"));
        assertThat(plain(text.render(List.of("%nick%"), viewer).get(0))).isEqualTo(INJECTED);
        assertThat(text.plain("%clan%", viewer)).isEqualTo("<name><red>X");
    }

    @Test
    void aTagInPlaceholderOutputIsShownAsWrittenInThePrefix() {
        Text text = new Text(log.logger, expansions(Map.of("%nick%", INJECTED, "%clan%", "<name>")), null);

        Component rendered = text.renderWithPrefix(
            "{prefix}Hi <name>", "<gold>[%nick%|%clan%|{name}] ", viewer, Placeholder.unparsed("name", "Ann"));

        assertThat(rendered)
            .isEqualTo(Component.text("[" + INJECTED + "|<name>|Ann] Hi Ann", NamedTextColor.GOLD));
    }

    @Test
    void legacyColoursInPlaceholderOutputApplyToThatOutputOnly() {
        Text text = new Text(log.logger, expansions(Map.of(
            "%nick%", "§cAnn", "%hex%", "§x§F§F§8§8§0§0Bo", "%short%", "§#00ff00Cy", "%rank%", "§c§lADMIN §7Steve")),
            null);

        Component rendered = text.render("<gray>Hello %nick% and %hex% and %short%", viewer);
        Component ranked = text.render("%rank%: hi", viewer);
        Component prefixed = text.renderWithPrefix("<prefix>: hi", "%rank%", viewer, TagResolver.empty());

        assertThat(plain(rendered)).isEqualTo("Hello Ann and Bo and Cy");
        assertThat(styleOf(rendered, "Ann").color()).isEqualTo(NamedTextColor.RED);
        assertThat(styleOf(rendered, " and ").color()).isEqualTo(NamedTextColor.GRAY);
        assertThat(styleOf(rendered, "Bo").color()).isEqualTo(TextColor.color(0xFF8800));
        assertThat(styleOf(rendered, "Cy").color()).isEqualTo(TextColor.color(0x00FF00));
        for (Component component : List.of(ranked, prefixed)) {
            assertThat(styleOf(component, "ADMIN ").color()).isEqualTo(NamedTextColor.RED);
            assertThat(styleOf(component, "ADMIN ").hasDecoration(TextDecoration.BOLD)).isTrue();
            assertThat(styleOf(component, "Steve").color()).isEqualTo(NamedTextColor.GRAY);
            assertThat(styleOf(component, "Steve").hasDecoration(TextDecoration.BOLD)).isFalse();
            assertThat(styleOf(component, ": hi")).isEqualTo(Style.empty());
        }
    }

    @Test
    void placeholderFormattingParsesOnlyColoursDecorationsGradientRainbowAndReset() {
        Text text = new Text(log.logger, expansions(Map.of(
            "%rank%", "<green><bold>VIP</bold> <#ff8800>a<color:blue>b<reset>c",
            "%fade%", "<gradient:red:blue>xy</gradient><rainbow>zw</rainbow>",
            "%nick%", INJECTED + "<name><insert:x><newline><lang:block.minecraft.stone><key:key.jump><font:alt>",
            "%legacy%", "§c§lADMIN §7Steve")), null).withPlaceholderFormatting();

        Component rank = text.render("%rank%: hi", viewer);
        Component fade = text.render("%fade%", viewer);
        Component nick = text.render("<gray>%nick% {name}", viewer, Placeholder.unparsed("name", "Ann"));
        Component prefixed = text.renderWithPrefix(
            "<prefix>hi", "%rank%%nick% ", viewer, Placeholder.unparsed("name", "Ann"));
        Component legacy = text.render("%legacy%!", viewer);

        assertThat(plain(rank)).isEqualTo("VIP abc: hi");
        assertThat(styleOf(rank, "VIP").color()).isEqualTo(NamedTextColor.GREEN);
        assertThat(styleOf(rank, "VIP").hasDecoration(TextDecoration.BOLD)).isTrue();
        assertThat(styleOf(rank, "a").color()).isEqualTo(TextColor.color(0xFF8800));
        assertThat(styleOf(rank, "b").color()).isEqualTo(NamedTextColor.BLUE);
        assertThat(styleOf(rank, "c: hi")).isEqualTo(Style.empty());
        assertThat(plain(fade)).isEqualTo("xyzw");
        assertThat(runs(fade)).hasSize(4).allSatisfy(run -> assertThat(run.getValue().color()).isNotNull());
        assertThat(styleOf(fade, "x").color()).isNotEqualTo(styleOf(fade, "y").color());
        assertThat(styleOf(fade, "z").color()).isNotEqualTo(styleOf(fade, "w").color());
        String shown = INJECTED + "<name><insert:x><newline><lang:block.minecraft.stone><key:key.jump><font:alt>";
        assertThat(plain(nick)).isEqualTo(shown + " Ann");
        assertNoEvents(nick);
        assertThat(runs(nick)).allSatisfy(run -> {
            assertThat(run.getValue().color()).isEqualTo(NamedTextColor.GRAY);
            assertThat(run.getValue().font()).isNull();
        });
        assertThat(plain(prefixed)).isEqualTo("VIP abc" + shown + " hi");
        assertNoEvents(prefixed);
        assertThat(styleOf(prefixed, "VIP").color()).isEqualTo(NamedTextColor.GREEN);
        assertThat(styleOf(legacy, "Steve").color()).isEqualTo(NamedTextColor.GRAY);
        assertThat(styleOf(legacy, "Steve").hasDecoration(TextDecoration.BOLD)).isFalse();
        assertThat(styleOf(legacy, "!")).isEqualTo(Style.empty());
    }

    @Test
    void placeholderFormattingLeavesTheSourceInstanceInert() {
        Text inert = new Text(log.logger, expansions(Map.of("%rank%", "<green>VIP")), null);
        Text formatting = inert.withPlaceholderFormatting();

        assertThat(formatting.withPlaceholderFormatting()).isSameAs(formatting);
        assertThat(plain(inert.render("%rank%", viewer))).isEqualTo("<green>VIP");
        assertThat(formatting.render("%rank%", viewer)).isEqualTo(Component.text("VIP", NamedTextColor.GREEN));
        assertThat(formatting.resolve("%rank%", viewer)).isEqualTo("<green>VIP");
    }

    @Test
    void aPlaceholderInsideAClickValueStaysAsWrittenAndOneInsideHoverTextIsFilledIn() {
        Text text = new Text(log.logger, pass, null);

        Component click = text.render("<click:run_command:'/pay %x%'>x</click>", viewer);
        Component hover = text.render("<hover:show_text:'%x%'>x</hover>", viewer);

        assertThat(MINI.serialize(click)).isEqualTo("<click:run_command:'/pay %x%'>x");
        assertThat(MINI.serialize(hover)).isEqualTo("<hover:show_text:'1'>x");
    }

    @Test
    void aPlaceholderInsideAnUnparsedTagArgumentStaysAsWrittenBesideOneThatIsFilledIn() {
        Text text = new Text(log.logger, pass, null);

        assertThat(MINI.serialize(text.render("%x% <click:suggest_command:'/msg %x% '>p</click>", viewer)))
            .isEqualTo("1 <click:suggest_command:'/msg %x% '>p");
        assertThat(MINI.serialize(text.render("<click:copy_to_clipboard:'%x% and %rank%'>p</click>", viewer)))
            .isEqualTo("<click:copy_to_clipboard:'%x% and %rank%'>p");
        assertThat(MINI.serialize(text.render("<insert:'%x%'>p</insert>", viewer))).isEqualTo("<insert:%x%>p");
        assertThat(MINI.serialize(text.render(
            "<hover:show_text:\"<click:run_command:'/pay %x%'>%x%</click>\">p</hover>", viewer)))
            .isEqualTo("<hover:show_text:\"<click:run_command:'/pay %x%'>1\">p");
    }

    @Test
    void aPlaceholderAfterAnEscapingBackslashStaysAsWritten() {
        Text text = new Text(log.logger, pass, null);

        assertThat(plain(text.render("%x% \\%x%", viewer))).isEqualTo("1 %x%");
    }

    @Test
    void aPlaceholderInsideAUrlOrAsAnUnquotedTagArgumentLeavesATagShownAsText() {
        Text text = new Text(log.logger, pass, null);

        assertThat(plain(text.render("<click:open_url:'https://x/u/%x%'>p</click>", viewer)))
            .isEqualTo("<click:open_url:'https://x/u/%x%'>p</click>");

        assertThat(plain(text.render("<color:%x%>p", viewer))).doesNotContain("taffy_papi");
        assertThat(plain(text.render("<gradient:%x%:%x%>p", viewer))).doesNotContain("taffy_papi");
    }

    @Test
    void aPlaceholderInsideAClickValueOfThePrefixStaysAsWritten() {
        Text text = new Text(log.logger, pass, null);

        Component rendered = text.renderWithPrefix(
            "<prefix> <click:run_command:'/b %rank%'>%x%</click>", "<click:run_command:'/a %x%'>[P]</click>",
            viewer, TagResolver.empty());

        assertThat(MINI.serialize(rendered))
            .isEqualTo("<click:run_command:'/a %x%'>[P]</click> <click:run_command:'/b %rank%'>1");
    }

    @Test
    void resolveRunsThePlaceholderApiPassWithoutParsing() {
        Text text = new Text(log.logger, pass, null);

        assertThat(text.resolve("<red>%x% %rank%", viewer)).isEqualTo("<red>1 <green>VIP");
    }

    @Test
    void throwingPlaceholderApiPassReturnsTheInput() {
        Text text = new Text(log.logger, THROWING_PASS, null);

        assertThat(text.resolve("<red>%x%", viewer)).isEqualTo("<red>%x%");
        assertThat(text.render("<red>%x%", viewer)).isEqualTo(Component.text("%x%", NamedTextColor.RED));
        assertThat(plain(text.render("%x% {name}", viewer, Placeholder.unparsed("name", "Ann")))).isEqualTo("%x% Ann");
        assertThat(text.render("%x%", viewer)).isEqualTo(Component.text("%x%"));
        assertOneWarning(PLACEHOLDER_API_WARNING, IllegalStateException.class);
        assertThat(log.records.get(0).getThrown()).hasMessage("expansion failed");
    }

    @Test
    void placeholderApiPassThatFailsToLinkReturnsTheInput() {
        Text text = new Text(log.logger, (player, input) -> {
            throw new NoSuchMethodError("org.bukkit.OfflinePlayer.getName()");
        }, null);

        assertThat(text.resolve("%x% hi", viewer)).isEqualTo("%x% hi");
        assertThat(text.render("<red>%x% hi", viewer)).isEqualTo(Component.text("%x% hi", NamedTextColor.RED));
        assertThat(text.plain("%x% hi", viewer)).isEqualTo("%x% hi");
        assertOneWarning(PLACEHOLDER_API_WARNING, NoSuchMethodError.class);
    }

    @Test
    void sectionSignCodesThatPlaceholderApiReturnsAreRenderedAsTheirTags() {
        Text text = new Text(log.logger,
            (player, input) -> input.replace("%nick%", "§cAnn").replace("%hex%", "§x§F§F§8§8§0§0Bo"), null);

        assertThat(text.render("<gray>Hello %nick%", viewer)).isEqualTo(MINI.deserialize("<gray>Hello <red>Ann"));
        assertThat(text.render("%hex%", viewer)).isEqualTo(MINI.deserialize("<#FF8800>Bo"));
        assertThat(text.plain("%nick% & co", viewer)).isEqualTo("Ann & co");
        assertThat(text.render("{who} met %nick%", viewer, Placeholder.unparsed("who", "Bo")))
            .isEqualTo(MINI.deserialize("Bo met <red>Ann"));
        assertThat(text.render(List.of("%nick%"), viewer)).containsExactly(Component.text("Ann", NamedTextColor.RED));
        assertThat(log.records).isEmpty();
    }

    @Test
    void sectionSignCodesInTheInputAreRenderedAsTheirTags() {
        Text text = new Text(log.logger, null, this::expansions);

        assertThat(text.render("§aHi &b")).isEqualTo(Component.text("Hi &b", NamedTextColor.GREEN));
        assertThat(text.render("§aHi &b", viewer)).isEqualTo(Component.text("Hi &b", NamedTextColor.GREEN));
        assertThat(text.render("§aHi {name}", null, Placeholder.unparsed("name", "Ann")))
            .isEqualTo(Component.text("Hi Ann", NamedTextColor.GREEN));
        assertThat(plain(text.render("§", viewer))).isEqualTo("§");
    }

    @Test
    void aSectionSignColourCodeEndsTheFormatCodesBeforeIt() {
        Text text = new Text(log.logger, null, null);

        Component rendered = text.render("§c§lADMIN §7Steve");

        assertThat(rendered).isEqualTo(Component.text("ADMIN ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("Steve", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
    }

    @Test
    void resolveKeepsTheSectionSignCodesPlaceholderApiReturns() {
        Text text = new Text(log.logger, (player, input) -> input.replace("%nick%", "§cAnn"), null);

        assertThat(text.resolve("<gray>%nick%", viewer)).isEqualTo("<gray>§cAnn");
    }

    @Test
    void aParsedValueHasItsSectionSignCodesConvertedAndAnUnparsedOneKeepsThem() {
        Text text = new Text(log.logger, null, null);

        assertThat(text.render("<name>", viewer, Placeholder.parsed("name", "§cAnn")))
            .isEqualTo(MINI.deserialize("<red>Ann"));
        assertThat(text.render("<name>", null, Placeholder.parsed("name", "§cAnn")))
            .isEqualTo(MINI.deserialize("<red>Ann"));
        assertThat(plain(text.render("<name>", viewer, Placeholder.unparsed("name", "§cAnn")))).isEqualTo("§cAnn");
        assertThat(log.records).isEmpty();
    }

    @Test
    void aParsedValueWrittenAsABracedNameIsAliasedAndConverted() {
        Text text = new Text(log.logger, null, this::expansions);

        assertThat(text.render("{name} here", viewer, Placeholder.parsed("name", "§cAnn")))
            .isEqualTo(MINI.deserialize("<red>Ann here"));
    }

    @Test
    void aMiniPlaceholdersPreProcessTagHasItsSectionSignCodesConverted() {
        Text text = new Text(log.logger, null, () -> TagResolver.resolver("tag", Tag.preProcessParsed("§aX")));

        assertThat(text.render("<tag>", viewer)).isEqualTo(Component.text("X", NamedTextColor.GREEN));
        assertThat(log.records).isEmpty();
    }

    @Test
    void sectionSafeAsksTheWrappedResolverWhichNamesItHas() {
        TagResolver safe = Text.sectionSafe(Placeholder.unparsed("a", "§c1"));

        assertThat(safe.has("a")).isTrue();
        assertThat(safe.has("nope")).isFalse();
        assertThat(plain(MINI.deserialize("<a> <nope>", safe))).isEqualTo("§c1 <nope>");
    }

    @Test
    void miniPlaceholdersTagsAreResolvedWithTheViewerAsTarget() {
        AtomicInteger built = new AtomicInteger();
        Text text = new Text(log.logger, null, () -> {
            built.incrementAndGet();
            return expansions();
        });

        assertThat(plain(text.render("<who> on <server>", viewer))).isEqualTo("viewer on lobby");
        assertThat(plain(text.render("<who> on <server>", viewer, Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("viewer on lobby");
        assertThat(plain(text.render("<who> on <server>", null))).isEqualTo("nobody on lobby");
        assertThat(plain(text.render("<who> on <server>"))).isEqualTo("nobody on lobby");
        assertThat(built).hasValue(4);
    }

    @Test
    void throwingMiniPlaceholdersSupplierFallsBackToParsingWithoutIt() {
        Text text = new Text(log.logger, null, () -> {
            throw new IllegalStateException("not enabled");
        });

        assertThat(text.render("<red>Hi <server>", viewer)).isEqualTo(MINI.deserialize("<red>Hi <server>"));
        assertThat(plain(text.render("<red>Hi <server> {name}", viewer, Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("Hi <server> Ann");
        assertOneWarning(MINI_PLACEHOLDERS_WARNING, IllegalStateException.class);
    }

    @Test
    void miniPlaceholdersSupplierThatFailsToLinkFallsBackToParsingWithoutIt() {
        Text text = new Text(log.logger, null, () -> {
            throw new NoSuchMethodError("audienceGlobalPlaceholders");
        });

        assertThat(text.render("<red>Hi <server>", viewer)).isEqualTo(MINI.deserialize("<red>Hi <server>"));
        assertThat(plain(text.render("{name} on <server>", viewer, Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("Ann on <server>");
        assertOneWarning(MINI_PLACEHOLDERS_WARNING, NoSuchMethodError.class);
    }

    @Test
    void miniPlaceholdersSupplierReturningNullIsSkipped() {
        Text text = new Text(log.logger, null, () -> null);

        assertThat(plain(text.render("<red>Hi <server>", viewer))).isEqualTo("Hi <server>");
        assertThat(log.records).isEmpty();
    }

    @Test
    void miniPlaceholdersTagThatThrowsWhileResolvingStaysAsWritten() {
        TagResolver broken = TagResolver.resolver("boom", (arguments, context) -> {
            throw new IllegalStateException("expansion failed");
        });
        TagResolver expansions = TagResolver.resolver(broken, Placeholder.unparsed("server", "lobby"));
        Text text = new Text(log.logger, null, () -> expansions);

        assertThat(text.render("<boom><red>Hi <server>", viewer))
            .isEqualTo(Component.text("<boom>").append(Component.text("Hi lobby", NamedTextColor.RED)));
        assertThat(log.records).isEmpty();
    }

    @Test
    void throwingMiniPlaceholdersResolverFallsBackToParsingWithoutIt() {
        TagResolver broken = new TagResolver() {
            @Override
            public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context context) {
                return null;
            }

            @Override
            public boolean has(String name) {
                throw new IllegalStateException("expansion failed");
            }
        };
        TagResolver expansions = TagResolver.resolver(broken, Placeholder.unparsed("server", "lobby"));
        Text text = new Text(log.logger, null, () -> expansions);

        assertThat(text.render("<red>Hi <server>", viewer)).isEqualTo(Component.text("Hi <server>", NamedTextColor.RED));
        assertThat(plain(text.render("<red><server> <name>", null, Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("<server> Ann");
        assertOneWarning(MINI_PLACEHOLDERS_WARNING, IllegalStateException.class);
    }

    @Test
    void miniPlaceholdersTagThatFailsToLinkWhileParsingFallsBackToParsingWithoutIt() {
        TagResolver unlinked = new TagResolver() {
            @Override
            public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context context) {
                throw new NoSuchMethodError("org.bukkit.OfflinePlayer.getName()");
            }

            @Override
            public boolean has(String name) {
                return name.equals("boom");
            }
        };
        TagResolver expansions = TagResolver.resolver(unlinked, Placeholder.unparsed("server", "lobby"));
        Text text = new Text(log.logger, null, () -> expansions);

        assertThat(text.render("<red>Hi <boom> <server>", viewer))
            .isEqualTo(Component.text("Hi <boom> <server>", NamedTextColor.RED));
        assertThat(plain(text.render("<boom> {name}", null, Placeholder.unparsed("name", "Ann"))))
            .isEqualTo("<boom> Ann");
        assertOneWarning(MINI_PLACEHOLDERS_WARNING, NoSuchMethodError.class);
    }

    @Test
    void inputThatFailsWithAndWithoutMiniPlaceholdersThrowsAndLogsNothing() {
        Text text = new Text(log.logger, null, this::expansions);

        assertThatThrownBy(() -> text.render("<cut>cAnn <server>", viewer, Placeholder.parsed("cut", "§")))
            .isInstanceOf(ParsingException.class);
        assertThat(log.records).isEmpty();
    }

    @Test
    void unparsedAndComponentValuesAreInsertedAsTheyAre() {
        Text text = new Text(log.logger, pass, this::expansions);

        Component unparsed = text.render("Hello <name>", viewer, Placeholder.unparsed("name", "<red>%x%<server>"));
        Component component = text.render(
            "Hello <name>", viewer, Placeholder.component("name", Component.text("<red>%x%<server>")));

        assertThat(plain(unparsed)).isEqualTo("Hello <red>%x%<server>");
        assertThat(plain(component)).isEqualTo("Hello <red>%x%<server>");
        assertThat(MINI.serialize(unparsed)).isEqualTo("Hello \\<red>%x%\\<server>");
        assertThat(passInputs).isEmpty();
    }

    @Test
    void parsedValueIsReadAsMiniMessage() {
        Text text = new Text(log.logger, pass, null);

        Component rendered = text.render("Hello <name>", viewer, Placeholder.parsed("name", "<red>Ann"));

        assertThat(plain(rendered)).isEqualTo("Hello Ann");
        assertThat(MINI.serialize(rendered)).contains("<red>Ann");
    }

    @Test
    void bracedNameReadsAsATagOnlyForSuppliedNames() {
        Text text = new Text(log.logger, pass, this::expansions);

        Component rendered = text.render(
            "{player} has {count} of {max} on {server} %x%", viewer,
            Placeholder.unparsed("player", "<red>Ann"),
            Placeholder.unparsed("count", "3"));

        assertThat(plain(rendered)).isEqualTo("<red>Ann has 3 of {max} on {server} 1");
        assertThat(passInputs).containsExactly("%x%");
    }

    @Test
    void suppliedBracedNameInsideAPlaceholderApiPlaceholderDoesNotReachThePass() {
        Text text = new Text(log.logger, pass, null);

        Component rendered = text.render("%math_{count}*2% %x%", viewer, Placeholder.unparsed("count", "3"));

        assertThat(plain(rendered)).isEqualTo("%math_3*2% 1");
        assertThat(passInputs).containsExactly("%math_<count>*2%", "% %", "%x%");
    }

    @Test
    void bracedNameWithoutValuesStaysAsWritten() {
        Text text = new Text(log.logger, pass, this::expansions);

        assertThat(plain(text.render("{server} {name}", viewer))).isEqualTo("{server} {name}");
        assertThat(plain(text.render("{server} {name}", viewer, new TagResolver[0]))).isEqualTo("{server} {name}");
    }

    @Test
    void aliasBracesRewritesOnlyWellFormedSuppliedNames() {
        TagResolver values = TagResolver.resolver(
            Placeholder.unparsed("a", "1"), Placeholder.unparsed("item-name_2", "2"));

        assertThat(Text.aliasBraces("{a} {b} {A} {a b} {} {{a}} {item-name_2}", values))
            .isEqualTo("<a> {b} {A} {a b} {} {<a>} <item-name_2>");
        assertThat(Text.aliasBraces("{a} and <a>", TagResolver.empty())).isEqualTo("{a} and <a>");
        assertThat(Text.aliasBraces("no braces", values)).isEqualTo("no braces");
    }

    @Test
    void bracedNameIsFilledInInsideHoverTextButNotInsideAClickValue() {
        Text text = new Text(log.logger, null, null);

        Component hover = text.render("<hover:show_text:'{name}'>x</hover>", null, Placeholder.unparsed("name", "Ann"));
        Component click = text.render(
            "<click:run_command:'/pay {name}'>x</click>", null, Placeholder.unparsed("name", "Ann"));

        assertThat(MINI.serialize(hover)).isEqualTo("<hover:show_text:'Ann'>x");
        assertThat(MINI.serialize(click)).isEqualTo("<click:run_command:'/pay <name>'>x");
    }

    @Test
    void suppliedBracedNameAsAnUnquotedTagArgumentLeavesTheTagAsText() {
        Text text = new Text(log.logger, null, null);

        Component rendered = text.render("<hover:show_text:{name}>x</hover>", null, Placeholder.unparsed("name", "Ann"));

        assertThat(plain(rendered)).isEqualTo("<hover:show_text:Ann>x</hover>");
        assertThat(rendered.hoverEvent()).isNull();
    }

    @Test
    void suppliedValueWinsOverAMiniPlaceholdersTagOfTheSameName() {
        Text text = new Text(log.logger, null, this::expansions);

        assertThat(plain(text.render("<server> {server}", viewer, Placeholder.unparsed("server", "mine"))))
            .isEqualTo("mine mine");
    }

    @Test
    void plainFlattensTheRenderedInput() {
        Text text = new Text(log.logger, pass, this::expansions);

        assertThat(text.plain("<red>Hi <bold>%x%</bold> <who>", viewer)).isEqualTo("Hi 1 viewer");
    }

    @Test
    void rendersEachLineForTheViewer() {
        Text text = new Text(log.logger, pass, null);

        List<Component> lines = text.render(List.of("<red>a", "%rank%", ""), viewer);

        assertThat(lines).containsExactly(
            Component.text("a", NamedTextColor.RED), Component.text("<green>VIP"), Component.empty());
        assertThat(passViewers).containsExactly(viewer);
    }

    @Test
    void sendDeliversTheRenderedMessageToThePlayer() {
        Text text = new Text(log.logger, pass, null);

        text.send(viewer, "<red>You are %x%");

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(viewer).sendMessage(sent.capture());
        assertThat(sent.getValue()).isEqualTo(Component.text("You are 1", NamedTextColor.RED));
        assertThat(passViewers).containsExactly(viewer);
    }

    @Test
    void publicConstructorRendersWithNeitherPluginOnTheClasspath() {
        Text text = new Text(log.logger);

        assertThat(text.render("<red>%x%", viewer)).isEqualTo(Component.text("%x%", NamedTextColor.RED));
        assertThat(text.resolve("%x%", viewer)).isEqualTo("%x%");
        assertThat(plain(text.render("{name} <who>", viewer, Placeholder.unparsed("name", "Ann")))).isEqualTo("Ann <who>");
        assertThat(log.records).isEmpty();
    }
}
