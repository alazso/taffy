package so.alaz.taffy.hooks;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import io.papermc.paper.plugin.configuration.PluginMeta;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class PlaceholderExpansionBuilderTest {

    private final Plugin plugin = mock(Plugin.class);
    private final PluginMeta meta = mock(PluginMeta.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<Map.Entry<String, Throwable>> reports = new ArrayList<>();

    PlaceholderExpansionBuilderTest() {
        errors.install((source, error) -> reports.add(Map.entry(source, error)));
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Shop");
        when(plugin.getLogger()).thenReturn(log.logger);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getAuthors()).thenReturn(List.of("Ana", "Bo"));
        when(meta.getVersion()).thenReturn("2.1");
    }

    @AfterEach
    void unregisterLeftovers() {
        for (String name : List.of("shop", "market", "titan", "market-2")) {
            Expansion left = MiniPlaceholders.expansionByName(name);
            if (left != null) {
                left.unregister();
            }
        }
    }

    // A plugin that is enabled is installed, of version 2.11; one that is not enabled is not installed either. Both
    // backends are the one returned plugin.
    private Plugin enabled(boolean placeholderApi, boolean miniPlaceholders) {
        when(plugins.isPluginEnabled("PlaceholderAPI")).thenReturn(placeholderApi);
        when(plugins.isPluginEnabled("MiniPlaceholders")).thenReturn(miniPlaceholders);
        Plugin installed = mock(Plugin.class);
        PluginMeta installedMeta = mock(PluginMeta.class);
        when(installedMeta.getVersion()).thenReturn("2.11");
        when(installed.getPluginMeta()).thenReturn(installedMeta);
        when(installed.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("PlaceholderAPI")).thenReturn(placeholderApi ? installed : null);
        when(plugins.getPlugin("MiniPlaceholders")).thenReturn(miniPlaceholders ? installed : null);
        return installed;
    }

    private PlaceholderRegistration register(PlaceholderExpansionBuilder builder) {
        return builder.register(plugin, errors, true);
    }

    private static HookStatus status(String backend, HookState state, String version) {
        return new HookStatus(backend, state, version, null);
    }

    private static List<HookState> states(PlaceholderRegistration registration) {
        return registration.statuses().stream().map(HookStatus::state).toList();
    }

    private static MockedConstruction<PapiExpansion> acceptingPlaceholderApi() {
        return mockConstruction(PapiExpansion.class, (mock, context) -> {
            when(mock.register()).thenReturn(true);
            when(mock.holdsIdentifier()).thenReturn(true);
        });
    }

    @Test
    void theDefaultIdentifierIsThePluginNameInLowerCaseKeepingLettersAndDigits() {
        assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");

        assertThat(PlaceholderExpansionBuilder.defaultIdentifier("TITAN-Shop_2")).isEqualTo("titanshop2");
        assertThat(PlaceholderExpansionBuilder.defaultIdentifier("Café İnn")).isEqualTo("cafinn");
    }

    @Test
    void theDefaultIdentifierUnderTurkishRulesNamesTheMiniPlaceholdersExpansion() {
        when(plugin.getName()).thenReturn("TITAN");
        enabled(false, true);

        register(new PlaceholderExpansionBuilder().global("open", () -> "yes"));

        assertThat(MiniPlaceholders.expansionByName("titan")).isNotNull();
        assertThat(MiniPlaceholders.globalPlaceholders().has("titan_open")).isTrue();
    }

    @Test
    void anIdentifierWithAnUnderscoreOrNothingIsRefused() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        assertThatThrownBy(() -> builder.identifier("my_shop"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid identifier 'my_shop': expected one or more of the letters a to z in either case,"
                + " the digits and '-'");
        assertThatThrownBy(() -> builder.identifier(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid identifier '': expected one or more of the letters a to z in either case, the digits"
                + " and '-'");
    }

    @Test
    void anIdentifierThatNoTagCanHoldIsRefused() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        for (String identifier : List.of("my.shop", "my shop", "shop:x", "sh%op", "caf\u00e9", "\u0130nn")) {
            assertThatThrownBy(() -> builder.identifier(identifier))
                .as(identifier)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid identifier '" + identifier + "': expected one or more of the letters a to z in"
                    + " either case, the digits and '-'");
        }
    }

    @Test
    void anIdentifierOfLettersDigitsAndHyphensIsAcceptedInLowerCaseAndResolvesInMiniPlaceholders() {
        enabled(false, true);

        register(new PlaceholderExpansionBuilder().identifier("Market-2").global("open", () -> "yes"));

        Expansion expansion = MiniPlaceholders.expansionByName("market-2");
        assertThat(expansion).isNotNull();
        assertThat(MiniPlaceholders.expansionByName("Market-2")).isNull();
        assertThat(PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage()
            .deserialize("<market-2_open>", MiniPlaceholders.globalPlaceholders()))).isEqualTo("yes");
    }

    @Test
    void aNameOutsideTheLowerCaseTagCharactersIsRefusedForEveryKind() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        for (String name : List.of("", " ", "Balance", "Item", "balance.top", "top 10", "a%b", "ı")) {
            String message = nameMessage(name);
            assertThatThrownBy(() -> builder.player(name, player -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
            assertThatThrownBy(() -> builder.offline(name, player -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
            assertThatThrownBy(() -> builder.global(name, () -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        }
        assertThatCode(() -> builder.player("balance_top-2", player -> "x")
            .offline("balance_top-2", player -> "x")
            .global("balance_top-2", () -> "x")).doesNotThrowAnyException();
    }

    @Test
    void anUpperCaseVariantOfAnAddedNameIsRefusedByTheCharacterRule() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder().player("balance", player -> "x");

        assertThatThrownBy(() -> builder.player("Balance", player -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(nameMessage("Balance"));
        assertThatThrownBy(() -> builder.global("BALANCE", () -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(nameMessage("BALANCE"));
    }

    @Test
    void aPrefixHoldsTheSameCharactersAsAName() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        assertThatThrownBy(() -> builder.playerPrefix("Keys", (player, rest) -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid placeholder prefix 'Keys': expected one or more of the letters a to z in lower"
                + " case, the digits, '_' and '-'");
        assertThatThrownBy(() -> builder.offlinePrefix("", (player, rest) -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid placeholder prefix '': expected one or more of the letters a to z in lower case,"
                + " the digits, '_' and '-'");
        assertThatThrownBy(() -> builder.globalPrefix("stock:low", rest -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("invalid placeholder prefix 'stock:low'");
        assertThatThrownBy(() -> builder.playerPrefix("keys_", (player, rest) -> "y"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid placeholder prefix 'keys_': expected no '_' at the end, since the '_' between the"
                + " prefix and the rest of the name is added");
        assertThatThrownBy(() -> builder.globalPrefix(null, rest -> "y"))
            .isInstanceOf(NullPointerException.class).hasMessage("prefix");
        assertThatThrownBy(() -> builder.playerPrefix("keys", null))
            .isInstanceOf(NullPointerException.class).hasMessage("handler");
        assertThatCode(() -> builder.playerPrefix("keys_virtual-2", (player, rest) -> "y"))
            .doesNotThrowAnyException();
    }

    @Test
    void prefixPlaceholdersReachBothBackendsAndALaterOneOfTheSamePrefixReplacesTheFirst() {
        enabled(true, true);
        Player online = mock(Player.class);
        when(online.getName()).thenReturn("Alex");
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(away.getName()).thenReturn("Sam");
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                PlaceholderPrefixes prefixes = (PlaceholderPrefixes) context.arguments().get(6);
                assertThat(prefixes.players().keySet()).containsExactly("keys");
                assertThat(prefixes.players().get("keys").apply(online, "vote")).isEqualTo("Alex has vote");
                assertThat(prefixes.offline().keySet()).containsExactly("spent");
                assertThat(prefixes.offline().get("spent").apply(away, "gems")).isEqualTo("Sam spent gems");
                assertThat(prefixes.globals().keySet()).containsExactly("stock");
                assertThat(prefixes.globals().get("stock").apply("iron")).isEqualTo("stock of iron");
                assertThat(prefixes.longestFirst()).containsExactly("spent", "stock", "keys");
            })) {
            register(new PlaceholderExpansionBuilder()
                .playerPrefix("keys", (player, rest) -> "replaced")
                .playerPrefix("keys", (player, rest) -> player.getName() + " has " + rest)
                .offlinePrefix("spent", (player, rest) -> player.getName() + " spent " + rest)
                .globalPrefix("stock", rest -> "stock of " + rest));

            assertThat(constructed.constructed()).hasSize(1);
        }
        String rendered = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(
            "<shop_keys:vote>|<shop_stock:iron>|<shop_spent:gems>", online,
            MiniPlaceholders.audienceGlobalPlaceholders()));

        assertThat(rendered).isEqualTo("Alex has vote|stock of iron|<shop_spent:gems>");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aThrowingPrefixHandlerGivesNoValueAndIsWarnedOncePerWindowForThePrefix() {
        enabled(false, true);
        Player player = mock(Player.class);
        new PlaceholderExpansionBuilder()
            .playerPrefix("keys", (viewer, rest) -> {
                throw new IllegalStateException("closed");
            })
            .globalPrefix("stock", rest -> {
                throw new IllegalArgumentException("bad " + rest);
            })
            .register(plugin, errors, true);

        String rendered = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(
            "<shop_keys:vote>|<shop_keys:daily>|<shop_stock:iron>", player,
            MiniPlaceholders.audienceGlobalPlaceholders()));

        assertThat(rendered).isEqualTo("<shop_keys:vote>|<shop_keys:daily>|<shop_stock:iron>");
        assertThat(log.lines()).containsExactly(
            "WARNING The prefix placeholder shop_keys_ failed, so it gives no value"
                + " (java.lang.IllegalStateException: closed).",
            "WARNING The prefix placeholder shop_stock_ failed, so it gives no value"
                + " (java.lang.IllegalArgumentException: bad iron).");
        assertThat(reports).isEmpty();
    }

    private static String nameMessage(String name) {
        return "invalid placeholder name '" + name + "': expected one or more of the letters a to z in lower case,"
            + " the digits, '_' and '-'";
    }

    @Test
    void aPluginNameWithoutLettersOrDigitsNeedsAnIdentifier() {
        when(plugin.getName()).thenReturn("___");
        enabled(true, true);
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        for (boolean switchedOn : new boolean[] {true, false}) {
            assertThatThrownBy(() -> builder.register(plugin, errors, switchedOn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no identifier: the plugin name holds no letter a to z and no digit, so set one with"
                    + " identifier(String)");
        }

        builder.identifier("shop").global("open", () -> "yes");
        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = register(builder);

            assertThat(registration.statuses().getFirst()).isEqualTo(status("PlaceholderAPI", HookState.ON, "2.11"));
            assertThat(constructed.constructed()).hasSize(1);
            registration.unregister();
        }
    }

    @Test
    void registerAndUnregisterDoNotThrowWhenPlaceholderApiCannotRegister() {
        enabled(true, true);
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder()
            .player("balance", player -> "100")
            .global("online", () -> "5");

        assertThatCode(() -> {
            PlaceholderRegistration registration = register(builder);
            assertThat(states(registration)).containsExactly(HookState.FAILED, HookState.ON);
            HookStatus failed = registration.statuses().getFirst();
            assertThat(failed.version()).isEqualTo("2.11");
            assertThat(failed.describe()).isEqualTo("PlaceholderAPI 2.11 could not be hooked");
            assertThat(failed.reason()).startsWith("PlaceholderAPI could not register the placeholders (java.lang.")
                .endsWith(").");
            assertThat(log.lines()).singleElement().isEqualTo("WARNING " + failed.reason());
            registration.unregister();
        }).doesNotThrowAnyException();
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING PlaceholderAPI could not register the placeholders (java.lang.");
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.getKey()).isEqualTo("hook.placeholderapi");
            assertThat(log.records.getFirst().getThrown()).isSameAs(report.getValue());
        });
        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
    }

    @Test
    void anIdentifierThatDiffersOnlyInCaseFromARegisteredOneIsRefusedByMiniPlaceholders() {
        enabled(false, true);
        register(new PlaceholderExpansionBuilder().identifier("market").global("open", () -> "yes"));

        PlaceholderRegistration second = register(new PlaceholderExpansionBuilder().identifier("Market")
            .global("open", () -> "no"));

        assertThat(states(second)).containsExactly(HookState.NOT_INSTALLED, HookState.FAILED);
        assertThat(second.statuses().get(1).reason()).startsWith("MiniPlaceholders could not register the"
            + " placeholders (");
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING MiniPlaceholders could not register the placeholders (");
        assertThat(reports).extracting(Map.Entry::getKey).containsExactly("hook.miniplaceholders");
    }

    @Test
    void nothingIsRegisteredOrLoggedWhenNeitherPluginIsInstalled() {
        enabled(false, false);
        int before = MiniPlaceholders.expansionCount();

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = register(new PlaceholderExpansionBuilder()
                .global("online", () -> "5"));

            assertThat(registration.statuses()).containsExactly(status("PlaceholderAPI", HookState.NOT_INSTALLED, null),
                status("MiniPlaceholders", HookState.NOT_INSTALLED, null));
            assertThat(constructed.constructed()).isEmpty();
        }
        assertThat(MiniPlaceholders.expansionCount()).isEqualTo(before);
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void nothingIsRegisteredWhenTheApiClassesAreAbsent() {
        FakePresence presence = new FakePresence();
        presence.absentClasses.add("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        presence.absentClasses.add("io.github.miniplaceholders.api.Expansion");

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
                .global("online", () -> "5")
                .register(plugin, errors, true, presence, log.logger);

            assertThat(registration.statuses()).containsExactly(status("PlaceholderAPI", HookState.API_MISSING, "1.0"),
                status("MiniPlaceholders", HookState.API_MISSING, "1.0"));
            assertThat(constructed.constructed()).isEmpty();
        }
        assertThat(reports).isEmpty();
        assertThat(presence.probes).containsExactly("me.clip.placeholderapi.expansion.PlaceholderExpansion",
            "io.github.miniplaceholders.api.Expansion");
        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI 1.0 lacks the API that is read, so it stays off.",
            "WARNING MiniPlaceholders 1.0 lacks the API that is read, so it stays off.");
    }

    @Test
    void anInstalledPluginThatIsNotEnabledIsSkippedWithAWarning() {
        FakePresence presence = new FakePresence();
        presence.disabled.add("PlaceholderAPI");
        presence.disabled.add("MiniPlaceholders");

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
                .global("online", () -> "5")
                .register(plugin, errors, true, presence, log.logger);

            assertThat(registration.statuses()).containsExactly(status("PlaceholderAPI", HookState.NOT_ENABLED, null),
                status("MiniPlaceholders", HookState.NOT_ENABLED, null));
            assertThat(constructed.constructed()).isEmpty();
        }
        assertThat(presence.probes).isEmpty();
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI is installed but not enabled, so the placeholders are not registered with it.",
            "WARNING MiniPlaceholders is installed but not enabled, so the placeholders are not registered with it.");
    }

    @Test
    void anEnabledPluginWhoseApiThePluginCannotSeeIsSkippedWithAWarningNamingTheMissingEntry() {
        FakePresence presence = new FakePresence();
        presence.invisibleClasses.add("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        presence.invisibleClasses.add("io.github.miniplaceholders.api.Expansion");

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
                .global("online", () -> "5")
                .register(plugin, errors, true, presence, log.logger);

            assertThat(registration.statuses()).containsExactly(
                new HookStatus("PlaceholderAPI", HookState.NOT_VISIBLE, "1.0", Detection.notVisible("PlaceholderAPI")),
                new HookStatus("MiniPlaceholders", HookState.NOT_VISIBLE, "1.0",
                    Detection.notVisible("MiniPlaceholders")));
            assertThat(constructed.constructed()).isEmpty();
        }
        assertThat(reports).isEmpty();
        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI 1.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml"
                + " does not list PlaceholderAPI under dependencies.server.",
            "WARNING MiniPlaceholders 1.0 is not visible to this plugin, so it stays off: this plugin's"
                + " paper-plugin.yml does not list MiniPlaceholders under dependencies.server.");
    }

    @Test
    void bothAreRegisteredWhenEnabledAndUnregisterRemovesBothOnce() {
        enabled(true, true);
        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = register(new PlaceholderExpansionBuilder()
                .player("balance", player -> "100")
                .global("online", () -> "5"));

            List<HookStatus> registered = List.of(status("PlaceholderAPI", HookState.ON, "2.11"),
                status("MiniPlaceholders", HookState.ON, "2.11"));
            assertThat(registration.statuses()).isEqualTo(registered);
            PapiExpansion expansion = constructed.constructed().getFirst();
            assertThat(constructed.constructed()).hasSize(1);
            assertThat(MiniPlaceholders.expansionByName("shop")).isNotNull();

            registration.unregister();
            registration.unregister();

            verify(expansion, times(1)).unregister();
            verify(expansion, times(1)).holdsIdentifier();
            assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
            assertThat(registration.statuses()).isEqualTo(registered);
        }
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void theExpansionCarriesTheIdentifierAuthorsVersionAndHandlers() {
        enabled(true, false);
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(away.getName()).thenReturn("Sam");
        Player online = mock(Player.class);
        when(online.getName()).thenReturn("Alex");
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                assertThat(List.<Object>copyOf(context.arguments().subList(0, 3)))
                    .containsExactly("market", "Ana, Bo", "2.1");
                @SuppressWarnings("unchecked")
                Map<String, Function<Player, String>> players = (Map<String, Function<Player, String>>)
                    context.arguments().get(3);
                @SuppressWarnings("unchecked")
                Map<String, Function<OfflinePlayer, String>> offline = (Map<String, Function<OfflinePlayer, String>>)
                    context.arguments().get(4);
                @SuppressWarnings("unchecked")
                Map<String, Supplier<String>> globals = (Map<String, Supplier<String>>) context.arguments().get(5);
                assertThat(players.keySet()).containsExactly("name");
                assertThat(players.get("name").apply(online)).isEqualTo("Alex");
                assertThat(offline.keySet()).containsExactly("last_seen");
                assertThat(offline.get("last_seen").apply(away)).isEqualTo("seen Sam");
                assertThat(globals.keySet()).containsExactly("online");
                assertThat(globals.get("online").get()).isEqualTo("5");
            })) {
            PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
                .identifier("market")
                .player("name", Player::getName)
                .offline("last_seen", player -> "seen " + player.getName())
                .global("online", () -> "5")
                .register(plugin, errors, true);

            assertThat(constructed.constructed()).hasSize(1);
            assertThat(states(registration)).containsExactly(HookState.ON, HookState.NOT_INSTALLED);
        }
    }

    @Test
    void theAuthorIsThePluginNameWhenTheMetaListsNone() {
        when(meta.getAuthors()).thenReturn(List.of());
        enabled(false, true);

        register(new PlaceholderExpansionBuilder().global("open", () -> "yes"));

        Expansion expansion = MiniPlaceholders.expansionByName("shop");
        assertThat(expansion).isNotNull();
        assertThat(expansion.author()).isEqualTo("Shop");
        assertThat(expansion.version()).isEqualTo("2.1");
    }

    @Test
    void placeholdersAddedAfterRegisterAreNotRegistered() {
        enabled(false, true);
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder().global("open", () -> "yes");

        register(builder);
        builder.global("late", () -> "no");

        Expansion expansion = MiniPlaceholders.expansionByName("shop");
        assertThat(expansion).isNotNull();
        assertThat(expansion.hasGlobalPlaceholder("open")).isTrue();
        assertThat(expansion.hasGlobalPlaceholder("late")).isFalse();
    }

    @Test
    void aThrowingHandlerGivesNoValueAndIsWarnedOncePerWindow() {
        enabled(false, true);
        Player player = mock(Player.class);
        new PlaceholderExpansionBuilder()
            .player("balance", viewer -> {
                throw new IllegalStateException("closed");
            })
            .global("online", () -> {
                throw new IllegalArgumentException("bad");
            })
            .register(plugin, errors, true);
        Expansion expansion = MiniPlaceholders.expansionByName("shop");
        assertThat(expansion).isNotNull();

        String first = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(
            "<shop_balance>|<shop_online>", player, MiniPlaceholders.audienceGlobalPlaceholders()));
        MiniMessage.miniMessage().deserialize("<shop_balance>", player, MiniPlaceholders.audienceGlobalPlaceholders());

        assertThat(first).isEqualTo("<shop_balance>|<shop_online>");
        assertThat(log.lines()).containsExactly(
            "WARNING The placeholder shop_balance failed, so it gives no value"
                + " (java.lang.IllegalStateException: closed).",
            "WARNING The placeholder shop_online failed, so it gives no value"
                + " (java.lang.IllegalArgumentException: bad).");
        assertThat(log.records.getFirst().getThrown()).hasMessage("closed");
    }

    @Test
    void anOfflineHandlerThatThrowsGivesNoValue() {
        enabled(true, false);
        OfflinePlayer away = mock(OfflinePlayer.class);
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                @SuppressWarnings("unchecked")
                Map<String, Function<OfflinePlayer, String>> offline = (Map<String, Function<OfflinePlayer, String>>)
                    context.arguments().get(4);
                assertThat(offline.get("last_seen").apply(away)).isNull();
            })) {
            new PlaceholderExpansionBuilder()
                .offline("last_seen", player -> {
                    throw new IllegalStateException("gone");
                })
                .register(plugin, errors, true);

            assertThat(constructed.constructed()).hasSize(1);
        }
        assertThat(log.lines()).containsExactly(
            "WARNING The placeholder shop_last_seen failed, so it gives no value"
                + " (java.lang.IllegalStateException: gone).");
    }

    @Test
    void aFailedMiniPlaceholdersRegistrationIsWarnedAndLeavesPlaceholderApiRegistered() {
        enabled(false, true);
        register(new PlaceholderExpansionBuilder().global("open", () -> "yes"));
        Expansion first = MiniPlaceholders.expansionByName("shop");
        enabled(true, true);

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration second = register(new PlaceholderExpansionBuilder()
                .global("open", () -> "again"));

            assertThat(states(second)).containsExactly(HookState.ON, HookState.FAILED);
            assertThat(second.statuses().get(1).reason()).startsWith("MiniPlaceholders could not register the"
                + " placeholders (java.lang.IllegalStateException: ");
            verify(constructed.constructed().getFirst()).register();
        }
        assertThat(reports).extracting(Map.Entry::getKey).containsExactly("hook.miniplaceholders");
        assertThat(MiniPlaceholders.expansionByName("shop")).isSameAs(first);
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING MiniPlaceholders could not register the placeholders"
                + " (java.lang.IllegalStateException: ");
    }

    @Test
    void aFailedUnregisterIsWarnedAndNotRepeated() {
        enabled(true, false);
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                when(mock.holdsIdentifier()).thenReturn(true);
                when(mock.unregister()).thenThrow(new IllegalStateException("not loaded"));
            })) {
            PlaceholderRegistration registration = register(new PlaceholderExpansionBuilder()
                .global("open", () -> "yes"));

            registration.unregister();
            registration.unregister();

            verify(constructed.constructed().getFirst(), times(1)).unregister();
            assertThat(registration.statuses().getFirst()).isEqualTo(status("PlaceholderAPI", HookState.ON, "2.11"));
        }
        assertThat(log.lines()).containsExactly(
            "WARNING PlaceholderAPI could not unregister the placeholders (java.lang.IllegalStateException: not"
                + " loaded).");
    }

    @Test
    void aRefusedPlaceholderApiRegistrationLeavesItUnregistered() {
        enabled(true, false);
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class)) {
            PlaceholderRegistration registration = register(new PlaceholderExpansionBuilder()
                .global("open", () -> "yes"));

            assertThat(registration.statuses().getFirst()).isEqualTo(new HookStatus("PlaceholderAPI",
                HookState.FAILED, "2.11", "PlaceholderAPI refused the placeholders under the identifier shop."));
            registration.unregister();
            verify(constructed.constructed().getFirst(), never()).unregister();
        }
        assertThat(log.lines()).containsExactly("WARNING PlaceholderAPI refused the placeholders under the"
            + " identifier shop.");
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.getKey()).isEqualTo("hook.placeholderapi");
            assertThat(report.getValue()).isInstanceOf(IllegalStateException.class)
                .hasMessage("PlaceholderAPI refused the placeholders under the identifier shop.");
        });
    }

    @Test
    void aSwitchedOffRegistrationLooksAtNeitherBackendAndLogsNothing() {
        FakePresence presence = new FakePresence();

        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
                .global("online", () -> "5")
                .register(plugin, errors, false, presence, log.logger);

            assertThat(registration.statuses()).containsExactly(status("PlaceholderAPI", HookState.SWITCHED_OFF, null),
                status("MiniPlaceholders", HookState.SWITCHED_OFF, null));
            assertThat(constructed.constructed()).isEmpty();
            assertThatCode(registration::unregister).doesNotThrowAnyException();
        }
        assertThat(presence.probes).isEmpty();
        assertThat(presence.plugins).isEmpty();
        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void thePublicRegisterSwitchedOffNeverReadsThePluginManager() {
        PlaceholderRegistration registration = new PlaceholderExpansionBuilder()
            .global("online", () -> "5")
            .register(plugin, errors, false);

        assertThat(states(registration)).containsExactly(HookState.SWITCHED_OFF, HookState.SWITCHED_OFF);
        verifyNoInteractions(plugins);
    }

    @Test
    void aRegisteredBackendReadsNotEnabledWhileItIsDisabled() {
        Plugin installed = enabled(true, true);
        try (MockedConstruction<PapiExpansion> constructed = acceptingPlaceholderApi()) {
            PlaceholderRegistration registration = register(new PlaceholderExpansionBuilder()
                .global("online", () -> "5"));

            when(installed.isEnabled()).thenReturn(false);
            assertThat(registration.statuses()).containsExactly(status("PlaceholderAPI", HookState.NOT_ENABLED, null),
                status("MiniPlaceholders", HookState.NOT_ENABLED, null));

            when(installed.isEnabled()).thenReturn(true);
            assertThat(states(registration)).containsExactly(HookState.ON, HookState.ON);
            registration.unregister();
        }
    }

    @Test
    void theDefaultIdentifierIsTakenFromTheOwnerGivenToRegister() {
        enabled(false, true);
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder().global("open", () -> "yes");
        when(plugin.getName()).thenReturn("Market");

        register(builder);

        assertThat(MiniPlaceholders.expansionByName("market")).isNotNull();
        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
    }

    @Test
    void registerRefusesAMissingOwnerOrErrorSink() {
        PlaceholderExpansionBuilder builder = new PlaceholderExpansionBuilder();

        assertThatThrownBy(() -> builder.register(null, errors, true))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> builder.register(plugin, null, true))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
