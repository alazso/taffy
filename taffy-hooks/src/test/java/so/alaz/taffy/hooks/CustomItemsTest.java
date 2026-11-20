package so.alaz.taffy.hooks;

import dev.lone.itemsadder.api.ItemsAdder;
import io.papermc.paper.plugin.configuration.PluginMeta;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.FakeItems;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class CustomItemsTest {

    private static final Set<CustomItemPlugin> ALL = EnumSet.allOf(CustomItemPlugin.class);

    private final CapturedLog log = new CapturedLog();
    private final Plugin owner = mock(Plugin.class);
    private final ErrorReports errors = new ErrorReports();
    private final List<Map.Entry<String, Throwable>> reports = new CopyOnWriteArrayList<>();
    private final FakePresence presence = new FakePresence();
    private final Map<CustomItemPlugin, FakeProvider> providers = new EnumMap<>(CustomItemPlugin.class);
    private final Map<CustomItemPlugin, Integer> factoryCalls = new EnumMap<>(CustomItemPlugin.class);
    private final Map<CustomItemPlugin, Throwable> factoryFailures = new EnumMap<>(CustomItemPlugin.class);
    private final Map<CustomItemPlugin, Plugin> builtWith = new EnumMap<>(CustomItemPlugin.class);
    private final ItemStack gem = FakeItems.item(Material.EMERALD, 1);
    private final ItemStack dirt = FakeItems.item(Material.DIRT, 1);

    @BeforeEach
    void owner() {
        when(owner.getLogger()).thenReturn(log.logger);
        errors.install((source, error) -> reports.add(Map.entry(source, error)));
    }

    // The factories are handed over in reverse plugin order.
    private CustomItems detect(Set<CustomItemPlugin> switchedOn) {
        Map<CustomItemPlugin, Function<Plugin, CustomItemProvider>> factories = new LinkedHashMap<>();
        for (CustomItemPlugin plugin : List.of(CustomItemPlugin.values()).reversed()) {
            factories.put(plugin, bound -> {
                factoryCalls.merge(plugin, 1, Integer::sum);
                builtWith.put(plugin, bound);
                Throwable failure = factoryFailures.get(plugin);
                if (failure != null) {
                    throw FakeProvider.<RuntimeException>sneaky(failure);
                }
                return provider(plugin);
            });
        }
        return CustomItems.detect(owner, errors, switchedOn, presence, factories);
    }

    // Detects every plugin and forgets the detection log.
    private CustomItems detected() {
        CustomItems items = detect(ALL);
        log.records.clear();
        return items;
    }

    private FakeProvider provider(CustomItemPlugin plugin) {
        return providers.computeIfAbsent(plugin, key -> new FakeProvider());
    }

    private Plugin bound(CustomItemPlugin plugin) {
        return presence.plugins.get(plugin.plugin());
    }

    private static HookStatus status(CustomItemPlugin plugin, HookState state) {
        boolean read = state != HookState.SWITCHED_OFF && state != HookState.NOT_INSTALLED
            && state != HookState.NOT_ENABLED;
        String reason = state == HookState.NOT_VISIBLE ? Detection.notVisible(plugin.plugin()) : null;
        return new HookStatus(plugin.plugin(), state, read ? "1.0" : null, reason);
    }

    @Test
    void everyPluginOnGivesOneLineAndAllProvidersBuiltWithTheirOwnPlugin() {
        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.values());
        assertThat(items.statuses()).containsExactly(status(CustomItemPlugin.ITEMSADDER, HookState.ON),
            status(CustomItemPlugin.NEXO, HookState.ON), status(CustomItemPlugin.ORAXEN, HookState.ON),
            status(CustomItemPlugin.HEADDATABASE, HookState.ON));
        assertThat(log.lines()).containsExactly(
            "INFO Custom item plugins: ItemsAdder on; Nexo on; Oraxen on; HeadDatabase on.");
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            assertThat(factoryCalls.get(plugin)).as(plugin.plugin()).isEqualTo(1);
            assertThat(builtWith.get(plugin)).as(plugin.plugin()).isSameAs(bound(plugin));
        }
        assertThat(presence.probes).containsExactly(CustomItemPlugin.ITEMSADDER.probe(),
            CustomItemPlugin.NEXO.probe(), CustomItemPlugin.ORAXEN.probe(), CustomItemPlugin.HEADDATABASE.probe());
    }

    @Test
    void aSwitchedOffPluginIsNeitherProbedNorBuilt() {
        CustomItems items = detect(EnumSet.complementOf(EnumSet.of(CustomItemPlugin.NEXO)));

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE);
        assertThat(items.statuses()).contains(status(CustomItemPlugin.NEXO, HookState.SWITCHED_OFF));
        assertThat(factoryCalls).doesNotContainKey(CustomItemPlugin.NEXO);
        assertThat(presence.probes).doesNotContain(CustomItemPlugin.NEXO.probe());
        assertThat(presence.plugins).doesNotContainKey("Nexo");
        assertThat(log.lines()).containsExactly(
            "INFO Custom item plugins: ItemsAdder on; Nexo switched off; Oraxen on; HeadDatabase on.");
    }

    @Test
    void withNothingSwitchedOnNoPluginIsLookedAtAndNothingResolves() {
        CustomItems items = detect(Set.of());

        assertThat(items.active()).isEmpty();
        assertThat(presence.probes).isEmpty();
        assertThat(factoryCalls).isEmpty();
        assertThat(items.ready()).isTrue();
        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution(null, false));
        assertThat(items.create("ruby")).isNull();
        assertThat(items.available("nexo")).isFalse();
        assertThat(log.lines()).containsExactly(
            "INFO Custom item plugins: ItemsAdder switched off; Nexo switched off; Oraxen switched off;"
                + " HeadDatabase switched off.");
    }

    @Test
    void aMissingOrDisabledPluginGetsNoProvider() {
        presence.missing.add("Oraxen");
        presence.disabled.add("ItemsAdder");

        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.NEXO, CustomItemPlugin.HEADDATABASE);
        assertThat(factoryCalls).containsOnlyKeys(CustomItemPlugin.NEXO, CustomItemPlugin.HEADDATABASE);
        assertThat(items.statuses()).containsExactly(status(CustomItemPlugin.ITEMSADDER, HookState.NOT_ENABLED),
            status(CustomItemPlugin.NEXO, HookState.ON), status(CustomItemPlugin.ORAXEN, HookState.NOT_INSTALLED),
            status(CustomItemPlugin.HEADDATABASE, HookState.ON));
        assertThat(log.lines()).containsExactly("INFO Custom item plugins: ItemsAdder installed but not enabled;"
            + " Nexo on; Oraxen not installed; HeadDatabase on.");
    }

    @Test
    void anEnabledPluginWithoutTheApiIsNamedInAWarning() {
        presence.absentClasses.add(CustomItemPlugin.NEXO.probe());

        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE);
        assertThat(factoryCalls).doesNotContainKey(CustomItemPlugin.NEXO);
        assertThat(items.statuses()).contains(status(CustomItemPlugin.NEXO, HookState.API_MISSING));
        assertThat(log.lines()).containsExactly(
            "WARNING Nexo 1.0 lacks the API that is read, so it stays off.",
            "INFO Custom item plugins: ItemsAdder on; Nexo 1.0 lacks the API that is read; Oraxen on;"
                + " HeadDatabase on.");
    }

    @Test
    void aFactoryThatFailsLeavesItsPluginOffAndTheOthersOn() {
        factoryFailures.put(CustomItemPlugin.ITEMSADDER, new NoClassDefFoundError("dev/lone/itemsadder/api/CustomStack"));

        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.NEXO, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE);
        assertThat(items.statuses().getFirst()).isEqualTo(status(CustomItemPlugin.ITEMSADDER, HookState.FAILED));
        assertThat(log.lines()).containsExactly(
            "WARNING ItemsAdder 1.0 could not be hooked (java.lang.NoClassDefFoundError:"
                + " dev/lone/itemsadder/api/CustomStack), so it stays off.",
            "INFO Custom item plugins: ItemsAdder 1.0 could not be hooked; Nexo on; Oraxen on; HeadDatabase on.");
        assertThat(log.records.getFirst().getThrown()).isInstanceOf(NoClassDefFoundError.class);
        assertThat(reports).containsExactly(
            Map.entry("hook.itemsadder", factoryFailures.get(CustomItemPlugin.ITEMSADDER)));
    }

    @Test
    void aFactoryThatThrowsACheckedExceptionLeavesItsPluginOffAndTheOthersOn() {
        factoryFailures.put(CustomItemPlugin.NEXO, new IOException("kotlin"));

        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE);
        assertThat(log.lines()).containsExactly(
            "WARNING Nexo 1.0 could not be hooked (java.io.IOException: kotlin), so it stays off.",
            "INFO Custom item plugins: ItemsAdder on; Nexo 1.0 could not be hooked; Oraxen on; HeadDatabase on.");
        assertThat(log.records.getFirst().getThrown()).isInstanceOf(IOException.class);
        assertThat(reports).containsExactly(Map.entry("hook.nexo", factoryFailures.get(CustomItemPlugin.NEXO)));
    }

    @Test
    void aPluginThatIsNotVisibleOrLacksTheApiIsNotReported() {
        presence.invisibleClasses.add(CustomItemPlugin.ORAXEN.probe());
        presence.absentClasses.add(CustomItemPlugin.NEXO.probe());

        CustomItems items = detect(ALL);

        assertThat(items.statuses().get(1).reason()).isNull();
        assertThat(items.statuses().get(2).reason()).isEqualTo("Oraxen is installed, but this plugin cannot see it,"
            + " because its paper-plugin.yml does not list Oraxen under dependencies.server.");
        assertThat(reports).isEmpty();
    }

    @Test
    void aPluginStillLoadingItsItemsSaysSo() {
        provider(CustomItemPlugin.ITEMSADDER).ready = false;

        CustomItems items = detect(EnumSet.of(CustomItemPlugin.ITEMSADDER));

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER);
        assertThat(items.statuses().getFirst()).isEqualTo(status(CustomItemPlugin.ITEMSADDER, HookState.LOADING));
        assertThat(log.lines()).containsExactly("INFO Custom item plugins: ItemsAdder on, waiting for its items to"
            + " load; Nexo switched off; Oraxen switched off; HeadDatabase switched off.");
    }

    @Test
    void theStatusesFollowEachPluginAfterDetection() {
        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        presence.missing.add("Oraxen");
        CustomItems items = detect(EnumSet.complementOf(EnumSet.of(CustomItemPlugin.HEADDATABASE)));

        assertThat(items.statuses()).containsExactly(status(CustomItemPlugin.ITEMSADDER, HookState.LOADING),
            status(CustomItemPlugin.NEXO, HookState.ON), status(CustomItemPlugin.ORAXEN, HookState.NOT_INSTALLED),
            status(CustomItemPlugin.HEADDATABASE, HookState.SWITCHED_OFF));

        provider(CustomItemPlugin.ITEMSADDER).ready = true;
        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(false);

        assertThat(items.statuses()).containsExactly(status(CustomItemPlugin.ITEMSADDER, HookState.ON),
            status(CustomItemPlugin.NEXO, HookState.NOT_ENABLED),
            status(CustomItemPlugin.ORAXEN, HookState.NOT_INSTALLED),
            status(CustomItemPlugin.HEADDATABASE, HookState.SWITCHED_OFF));
        assertThat(items.statuses().get(1).describe()).isEqualTo("Nexo installed but not enabled");
        assertThatThrownBy(() -> items.statuses().add(status(CustomItemPlugin.NEXO, HookState.ON)))
            .isInstanceOf(UnsupportedOperationException.class);

        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(true);
        assertThat(items.statuses().get(1)).isEqualTo(status(CustomItemPlugin.NEXO, HookState.ON));
    }

    @Test
    void aPluginLoadedAgainAsANewInstanceStaysOffWithAReason() {
        provider(CustomItemPlugin.NEXO).id("ruby", "ruby");
        CustomItems items = detected();
        Plugin old = bound(CustomItemPlugin.NEXO);
        Plugin loadedAgain = mock(Plugin.class);
        when(loadedAgain.isEnabled()).thenReturn(true);

        when(old.isEnabled()).thenReturn(false);
        presence.plugins.put("Nexo", loadedAgain);

        assertThat(items.statuses().get(1)).isEqualTo(
            new HookStatus("Nexo", HookState.NOT_ENABLED, null, Detected.replaced("Nexo")));
        assertThat(items.available("nexo")).isFalse();
        assertThat(items.create("nexo:ruby")).isNull();
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(0);
    }

    @Test
    void anEnabledPluginWhoseApiThePluginCannotSeeIsNamedInAWarning() {
        presence.invisibleClasses.add(CustomItemPlugin.ORAXEN.probe());

        CustomItems items = detect(ALL);

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.NEXO,
            CustomItemPlugin.HEADDATABASE);
        assertThat(items.statuses()).contains(status(CustomItemPlugin.ORAXEN, HookState.NOT_VISIBLE));
        assertThat(log.lines()).containsExactly(
            "WARNING Oraxen 1.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml does"
                + " not list Oraxen under dependencies.server.",
            "INFO Custom item plugins: ItemsAdder on; Nexo on; Oraxen 1.0 not visible to this plugin;"
                + " HeadDatabase on.");
    }

    @Test
    void eachProviderResolvesThroughItsOwnPluginUntilThatPluginDisables() {
        ItemStack paper = FakeItems.item(Material.PAPER, 1);
        provider(CustomItemPlugin.NEXO).id("paper", "x");
        CustomItems items = detected();

        assertThat(items.idOrNull(paper)).isEqualTo("nexo:x");
        assertThat(items.resolve(paper).failed()).isFalse();

        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(false);
        assertThat(items.idOrNull(paper)).as("ItemsAdder disabled").isEqualTo("nexo:x");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(2);

        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(true);
        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(false);
        CustomItems.Resolution resolution = items.resolve(paper);
        assertThat(resolution.id()).isNull();
        assertThat(resolution.failed()).isTrue();
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(3);
    }

    @Test
    void providersRunInPluginOrderAndTheFirstIdWins() {
        provider(CustomItemPlugin.ORAXEN).id("emerald", "gem");
        provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        CustomItems items = detected();

        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution("nexo:gem", false));
        assertThat(items.idOrNull(gem)).isEqualTo("nexo:gem");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(2);
        assertThat(provider(CustomItemPlugin.ORAXEN).calls).hasValue(0);
        assertThat(items.idOrNull(dirt)).isNull();
        assertThat(provider(CustomItemPlugin.HEADDATABASE).calls).hasValue(1);
    }

    @Test
    void anIdIsPrefixedWithTheKeyOfThePluginThatReadIt() {
        provider(CustomItemPlugin.ITEMSADDER).id("emerald", "gems:emerald");
        provider(CustomItemPlugin.HEADDATABASE).id("dirt", "1234");
        CustomItems items = detected();

        assertThat(items.idOrNull(gem)).isEqualTo("itemsadder:gems:emerald");
        assertThat(items.idOrNull(dirt)).isEqualTo("headdatabase:1234");
    }

    @Test
    void aThrowingProviderIsSkippedAndFailsOnlyWhenNoOtherGivesAnId() {
        provider(CustomItemPlugin.ITEMSADDER).failure = new IllegalStateException("registry busy");
        provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        CustomItems items = detected();

        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution("nexo:gem", false));
        assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, true));
        assertThat(items.idOrNull(dirt)).isNull();
    }

    @Test
    void aCheckedExceptionOrALinkageErrorCountsAsAFailure() {
        CustomItems items = detected();

        provider(CustomItemPlugin.NEXO).failure = new IOException("kotlin");
        assertThat(items.resolve(gem).failed()).isTrue();
        provider(CustomItemPlugin.NEXO).failure = new NoSuchMethodError("idFromItem");
        assertThat(items.resolve(gem).failed()).isTrue();
    }

    @Test
    void anErrorOtherThanALinkageErrorIsRethrownAndNotLogged() {
        provider(CustomItemPlugin.NEXO).failure = new StackOverflowError();
        CustomItems items = detected();

        assertThatThrownBy(() -> items.resolve(gem)).isInstanceOf(StackOverflowError.class);
        assertThat(log.records).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void theFirstReadFailureIsLoggedWithItsStackTheNextOnesAreHeldBackAndNoneIsReported() {
        provider(CustomItemPlugin.ITEMSADDER).failure = new IllegalStateException("registry busy");
        CustomItems items = detected();

        items.resolve(gem);
        items.resolve(gem);

        assertThat(log.lines()).containsExactly("WARNING ItemsAdder could not read the id of a EMERALD"
            + " (java.lang.IllegalStateException: registry busy).");
        assertThat(log.records.getFirst().getThrown()).hasMessage("registry busy");
        assertThat(reports).isEmpty();
    }

    @Test
    void twoFailingProvidersAreEachLoggedWithTheirStack() {
        provider(CustomItemPlugin.ITEMSADDER).failure = new IllegalStateException("a");
        provider(CustomItemPlugin.NEXO).failure = new IllegalStateException("b");
        CustomItems items = detected();

        assertThat(items.resolve(gem).failed()).isTrue();

        assertThat(log.lines()).containsExactly(
            "WARNING ItemsAdder could not read the id of a EMERALD (java.lang.IllegalStateException: a).",
            "WARNING Nexo could not read the id of a EMERALD (java.lang.IllegalStateException: b).");
        assertThat(log.records.get(0).getThrown()).hasMessage("a");
        assertThat(log.records.get(1).getThrown()).hasMessage("b");
    }

    @Test
    void aProviderThatIsNotReadyIsNotCalledAndCountsAsAFailure() {
        provider(CustomItemPlugin.ITEMSADDER).id("emerald", "shop:gem").ready = false;
        CustomItems items = detected();

        assertThat(items.ready()).isFalse();
        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution(null, true));
        assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, true));
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(0);
        provider(CustomItemPlugin.ITEMSADDER).ready = true;
        assertThat(items.ready()).isTrue();
        assertThat(items.idOrNull(gem)).isEqualTo("itemsadder:shop:gem");
    }

    @Test
    void anIdReadByAnotherPluginWinsOverAPluginStillLoading() {
        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        CustomItems items = detected();

        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution("nexo:gem", false));
    }

    @Test
    void aPluginThatReadsIdsWhileLoadingAnswersAndBuildsNothingUntilItsItemsLoaded() {
        FakeProvider nexo = provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        nexo.ready = false;
        nexo.idsWhileLoading = true;
        CustomItems items = detect(EnumSet.of(CustomItemPlugin.NEXO));
        CustomItems.Built notReady = new CustomItems.Built(null, CustomItems.Built.Reason.NOT_READY);

        assertThat(log.lines()).containsExactly("INFO Custom item plugins: ItemsAdder switched off; Nexo on,"
            + " waiting for its items to load; Oraxen switched off; HeadDatabase switched off.");
        assertThat(items.ready()).isFalse();
        assertThat(items.statuses().get(1)).isEqualTo(status(CustomItemPlugin.NEXO, HookState.LOADING));
        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution("nexo:gem", false));
        assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, false));
        assertThat(items.idOrNull(gem)).isEqualTo("nexo:gem");
        assertThat(nexo.calls).hasValue(3);
        assertThat(items.create("nexo:gem")).isNull();
        assertThat(items.create("gem")).isNull();
        assertThat(items.build("nexo:gem")).isEqualTo(notReady);
        assertThat(items.build("gem")).isEqualTo(notReady);
        assertThat(nexo.calls).hasValue(3);

        nexo.ready = true;

        assertThat(items.ready()).isTrue();
        assertThat(items.statuses().get(1)).isEqualTo(status(CustomItemPlugin.NEXO, HookState.ON));
        assertThat(FakeItems.type(items.create("nexo:gem"))).isEqualTo("emerald");
        assertThat(items.build("gem").reason()).isNull();
        assertThat(items.idOrNull(gem)).isEqualTo("nexo:gem");
    }

    @Test
    void aNullOrEmptyItemIsNoCustomItemAndNoPluginIsAsked() {
        provider(CustomItemPlugin.ITEMSADDER).failure = new IllegalStateException("null item");
        provider(CustomItemPlugin.NEXO).ready = false;
        CustomItems items = detected();
        ItemStack empty = FakeItems.item(Material.EMERALD, 0);

        assertThat(items.resolve(null)).isEqualTo(new CustomItems.Resolution(null, false));
        assertThat(items.resolve(empty)).isEqualTo(new CustomItems.Resolution(null, false));
        assertThat(items.idOrNull(null)).isNull();
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(0);
        assertThat(log.records).isEmpty();
    }

    @Test
    void aPluginThatDisabledIsNotCalledAndCountsAsAFailure() {
        provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        CustomItems items = detected();
        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(false);

        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution(null, true));
        assertThat(items.idOrNull(gem)).isNull();
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(0);
        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(true);
        assertThat(items.idOrNull(gem)).isEqualTo("nexo:gem");
        assertThat(log.records).isEmpty();
    }

    @Test
    void aPluginThatReadsNoIdsIsNeverAskedAndNeverCountsAsAFailure() {
        provider(CustomItemPlugin.NEXO).id("emerald", "gem");
        provider(CustomItemPlugin.HEADDATABASE).readsIds = false;
        provider(CustomItemPlugin.HEADDATABASE).id("dirt", "1234");
        CustomItems items = detected();

        assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, false));
        when(bound(CustomItemPlugin.HEADDATABASE).isEnabled()).thenReturn(false);
        assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, false));
        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution("nexo:gem", false));
        assertThat(provider(CustomItemPlugin.HEADDATABASE).calls).hasValue(0);
        assertThat(items.active()).contains(CustomItemPlugin.HEADDATABASE);
        assertThat(log.records).isEmpty();
    }

    @Test
    void aPluginThatDisabledBeforeItsItemsLoadedDoesNotHoldUpReadiness() {
        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        CustomItems items = detect(EnumSet.of(CustomItemPlugin.ITEMSADDER));
        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(false);

        assertThat(items.ready()).isTrue();
        assertThat(items.resolve(gem)).isEqualTo(new CustomItems.Resolution(null, true));
        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(true);
        assertThat(items.ready()).isFalse();
    }

    @Test
    void aQualifiedReferenceGoesOnlyToTheNamedPlugin() {
        provider(CustomItemPlugin.NEXO).id("nexo_ruby", "ruby");
        provider(CustomItemPlugin.ORAXEN).id("oraxen_ruby", "ruby");
        CustomItems items = detected();

        assertThat(FakeItems.type(items.create("oraxen:ruby"))).isEqualTo("oraxen_ruby");
        assertThat(items.create("oraxen:opal")).isNull();
        assertThat(provider(CustomItemPlugin.ORAXEN).calls).hasValue(2);
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(0);
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(0);
        assertThat(provider(CustomItemPlugin.HEADDATABASE).calls).hasValue(0);
    }

    @Test
    void anAliasOrAKeyInAnyCaseNamesThePlugin() {
        provider(CustomItemPlugin.ITEMSADDER).id("ruby", "gems:ruby");
        provider(CustomItemPlugin.HEADDATABASE).id("head", "1234");
        provider(CustomItemPlugin.NEXO).id("opal", "opal");
        CustomItems items = detected();

        assertThat(FakeItems.type(items.create("ia:gems:ruby"))).isEqualTo("ruby");
        assertThat(FakeItems.type(items.create("IA:gems:ruby"))).isEqualTo("ruby");
        assertThat(FakeItems.type(items.create("itemsadder:gems:ruby"))).isEqualTo("ruby");
        assertThat(FakeItems.type(items.create("hdb:1234"))).isEqualTo("head");
        assertThat(FakeItems.type(items.create("HeadDatabase:1234"))).isEqualTo("head");
        assertThat(FakeItems.type(items.create("NEXO:opal"))).isEqualTo("opal");
    }

    @Test
    void anUnqualifiedReferenceIsOfferedInPluginOrderAndTheFirstItemWins() {
        provider(CustomItemPlugin.ORAXEN).id("oraxen_ruby", "ruby");
        provider(CustomItemPlugin.HEADDATABASE).id("head_ruby", "ruby");
        CustomItems items = detected();

        assertThat(FakeItems.type(items.create("ruby"))).isEqualTo("oraxen_ruby");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.HEADDATABASE).calls).hasValue(0);
        assertThat(items.create("opal")).isNull();
        assertThat(provider(CustomItemPlugin.HEADDATABASE).calls).hasValue(1);
    }

    @Test
    void aReferenceStartingWithAColonGivesEachPluginTheBareId() {
        provider(CustomItemPlugin.NEXO).id("ruby", "ruby");
        CustomItems items = detected();

        assertThat(FakeItems.type(items.create(":ruby"))).isEqualTo("ruby");
        assertThat(FakeItems.type(items.create(" :ruby"))).isEqualTo("ruby");
    }

    @Test
    void aReferenceWhoseHintNamesNoPluginIsOfferedWholeInPluginOrder() {
        provider(CustomItemPlugin.ITEMSADDER).id("ia_ruby", "gems:ruby");
        provider(CustomItemPlugin.NEXO).id("nexo_ruby", "ruby").id("nexo_opal", "gems:opal");
        CustomItems items = detected();

        assertThat(FakeItems.type(items.create("gems:ruby"))).isEqualTo("ia_ruby");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(0);

        assertThat(FakeItems.type(items.create("gems:opal"))).isEqualTo("nexo_opal");
        assertThat(FakeItems.type(items.create("gems:pack:ruby"))).isNull();
        assertThat(items.create("mmoitems:ruby")).as("no plugin is given the id without the hint").isNull();
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            assertThat(provider(plugin).calls).as(plugin.plugin())
                .hasValue(plugin == CustomItemPlugin.ITEMSADDER ? 4 : plugin == CustomItemPlugin.NEXO ? 3 : 2);
        }
    }

    @Test
    void aHintThatNamesAPluginNeverFallsBackToTheWholeReference() {
        provider(CustomItemPlugin.ITEMSADDER).id("ia_ruby", "nexo:ruby").id("ia_opal", "ia:opal");
        provider(CustomItemPlugin.ORAXEN).id("oraxen_ruby", "nexo:ruby");
        CustomItems items = detected();

        assertThat(items.create("nexo:ruby")).isNull();
        assertThat(items.create("ia:opal")).isNull();
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.ORAXEN).calls).hasValue(0);

        presence.missing.add("Nexo");
        CustomItems withoutNexo = detected();
        assertThat(withoutNexo.create("nexo:ruby")).isNull();
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(1);
        assertThat(provider(CustomItemPlugin.ORAXEN).calls).hasValue(0);
    }

    @Test
    void aReferenceToAPluginThatIsOffGivesNull() {
        presence.missing.add("Nexo");
        provider(CustomItemPlugin.NEXO).id("ruby", "ruby");
        provider(CustomItemPlugin.ORAXEN).id("ruby", "ruby");
        CustomItems items = detected();

        assertThat(items.create("nexo:ruby")).isNull();
        assertThat(provider(CustomItemPlugin.NEXO).calls).hasValue(0);
        assertThat(provider(CustomItemPlugin.ORAXEN).calls).hasValue(0);
    }

    @Test
    void aPluginStillLoadingItsItemsIsSkippedWhenCreating() {
        provider(CustomItemPlugin.ITEMSADDER).id("ia_ruby", "ruby").ready = false;
        provider(CustomItemPlugin.NEXO).id("nexo_ruby", "ruby");
        CustomItems items = detected();

        assertThat(items.create("ia:ruby")).isNull();
        assertThat(FakeItems.type(items.create("ruby"))).isEqualTo("nexo_ruby");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(0);
        provider(CustomItemPlugin.ITEMSADDER).ready = true;
        assertThat(FakeItems.type(items.create("ruby"))).isEqualTo("ia_ruby");
    }

    @Test
    void aDisabledPluginIsSkippedWhenCreating() {
        provider(CustomItemPlugin.ITEMSADDER).id("ia_ruby", "ruby");
        provider(CustomItemPlugin.NEXO).id("nexo_ruby", "ruby");
        CustomItems items = detected();
        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(false);

        assertThat(items.create("itemsadder:ruby")).isNull();
        assertThat(FakeItems.type(items.create("ruby"))).isEqualTo("nexo_ruby");
        assertThat(provider(CustomItemPlugin.ITEMSADDER).calls).hasValue(0);
        assertThat(reports).isEmpty();
    }

    @Test
    void aThrowingProviderIsReportedUnderItsKeyAndGivesNull() {
        IllegalStateException failure = new IllegalStateException("broken");
        provider(CustomItemPlugin.ORAXEN).id("oraxen_ruby", "ruby").failure = failure;
        provider(CustomItemPlugin.HEADDATABASE).id("head_ruby", "ruby");
        CustomItems items = detected();

        assertThat(items.create("oraxen:ruby")).isNull();
        assertThat(FakeItems.type(items.create("ruby"))).isEqualTo("head_ruby");

        assertThat(reports).containsExactly(Map.entry("hook.oraxen", failure), Map.entry("hook.oraxen", failure));
        assertThat(log.lines()).containsExactly(
            "WARNING Oraxen could not create ruby (java.lang.IllegalStateException: broken).");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void aCreateFailureDoesNotHoldBackTheWarningOfAReadFailure() {
        provider(CustomItemPlugin.NEXO).failure = new IllegalStateException("broken");
        CustomItems items = detected();

        items.create("nexo:ruby");
        items.resolve(gem);

        assertThat(log.lines()).containsExactly(
            "WARNING Nexo could not create ruby (java.lang.IllegalStateException: broken).",
            "WARNING Nexo could not read the id of a EMERALD (java.lang.IllegalStateException: broken).");
        assertThat(reports).hasSize(1);
    }

    @Test
    void aCheckedExceptionOrALinkageErrorFromCreateIsReported() {
        IOException checked = new IOException("kotlin");
        NoSuchMethodError linkage = new NoSuchMethodError("itemFromId");
        CustomItems items = detected();

        provider(CustomItemPlugin.NEXO).failure = checked;
        assertThat(items.create("nexo:ruby")).isNull();
        provider(CustomItemPlugin.HEADDATABASE).failure = linkage;
        assertThat(items.create("hdb:1234")).isNull();

        assertThat(reports).containsExactly(Map.entry("hook.nexo", checked), Map.entry("hook.headdatabase", linkage));
    }

    @Test
    void anErrorOtherThanALinkageErrorFromCreatePropagatesAndIsNotReported() {
        provider(CustomItemPlugin.NEXO).failure = new OutOfMemoryError("heap");
        CustomItems items = detected();

        assertThatThrownBy(() -> items.create("nexo:ruby")).isInstanceOf(OutOfMemoryError.class);
        assertThat(reports).isEmpty();
        assertThat(log.records).isEmpty();
    }

    private static CustomItems.Built none(CustomItems.Built.Reason reason) {
        return new CustomItems.Built(null, reason);
    }

    @Test
    void buildGivesTheItemWithNoReasonAndCreateGivesTheSameItem() {
        provider(CustomItemPlugin.NEXO).id("nexo_ruby", "ruby");
        provider(CustomItemPlugin.ITEMSADDER).id("ia_ruby", "gems:ruby");
        CustomItems items = detected();

        for (String reference : List.of("nexo:ruby", "ruby", "gems:ruby", "ia:gems:ruby")) {
            CustomItems.Built built = items.build(reference);
            assertThat(built.reason()).as(reference).isNull();
            assertThat(FakeItems.type(built.item())).as(reference)
                .isEqualTo(FakeItems.type(items.create(reference))).isNotNull();
        }
    }

    @Test
    void buildNamesTheCauseForAReferenceThatNamesAPlugin() {
        presence.missing.add("Oraxen");
        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        provider(CustomItemPlugin.HEADDATABASE).failure = new IllegalStateException("boom");
        CustomItems items = detected();

        assertThat(items.build("oraxen:ruby")).isEqualTo(none(CustomItems.Built.Reason.PLUGIN_OFF));
        assertThat(items.build("ia:gems:ruby")).isEqualTo(none(CustomItems.Built.Reason.NOT_READY));
        assertThat(items.build("nexo:ruby")).isEqualTo(none(CustomItems.Built.Reason.UNKNOWN_ID));
        assertThat(items.build("hdb:1234")).isEqualTo(none(CustomItems.Built.Reason.FAILED));
        assertThat(reports).extracting(Map.Entry::getKey).containsExactly("hook.headdatabase");

        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(false);
        assertThat(items.build("nexo:ruby")).isEqualTo(none(CustomItems.Built.Reason.PLUGIN_OFF));
        when(bound(CustomItemPlugin.ITEMSADDER).isEnabled()).thenReturn(false);
        assertThat(items.build("ia:gems:ruby")).as("a disabled plugin is off, loading or not")
            .isEqualTo(none(CustomItems.Built.Reason.PLUGIN_OFF));
        for (String reference : List.of("oraxen:ruby", "ia:gems:ruby", "nexo:ruby", "hdb:1234")) {
            assertThat(items.create(reference)).as(reference).isNull();
        }
    }

    @Test
    void buildNamesTheCauseForAReferenceWithoutAHint() {
        CustomItems items = detected();

        assertThat(items.build("ruby")).isEqualTo(none(CustomItems.Built.Reason.UNKNOWN_ID));

        provider(CustomItemPlugin.ORAXEN).failure = new IllegalStateException("boom");
        assertThat(items.build("ruby")).isEqualTo(none(CustomItems.Built.Reason.FAILED));

        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        assertThat(items.build("ruby")).isEqualTo(none(CustomItems.Built.Reason.NOT_READY));

        provider(CustomItemPlugin.HEADDATABASE).id("head", "ruby");
        assertThat(FakeItems.type(items.build("ruby").item())).as("a later plugin still builds it").isEqualTo("head");

        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            when(bound(plugin).isEnabled()).thenReturn(false);
        }
        assertThat(items.build("ruby")).isEqualTo(none(CustomItems.Built.Reason.PLUGIN_OFF));
        assertThat(detect(Set.of()).build("ruby")).isEqualTo(none(CustomItems.Built.Reason.PLUGIN_OFF));
    }

    @Test
    void buildNamesTheCauseForAReferenceWhoseHintNamesNoPlugin() {
        CustomItems items = detected();

        assertThat(items.build("mmoitems:ruby")).isEqualTo(none(CustomItems.Built.Reason.UNKNOWN_PLUGIN));

        provider(CustomItemPlugin.ORAXEN).failure = new IllegalStateException("boom");
        assertThat(items.build("gems:ruby")).isEqualTo(none(CustomItems.Built.Reason.FAILED));

        provider(CustomItemPlugin.ITEMSADDER).ready = false;
        assertThat(items.build("gems:ruby")).isEqualTo(none(CustomItems.Built.Reason.NOT_READY));

        assertThat(detect(Set.of()).build("gems:ruby")).isEqualTo(none(CustomItems.Built.Reason.UNKNOWN_PLUGIN));
    }

    @Test
    void buildRefusesAReferenceWithoutAnIdAsCreateDoes() {
        CustomItems items = detected();

        assertThatThrownBy(() -> items.build("nexo:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> items.build(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBuiltHoldsEitherAnItemOrAReason() {
        assertThat(new CustomItems.Built(gem, null).item()).isSameAs(gem);
        assertThatThrownBy(() -> new CustomItems.Built(null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a result without an item needs a reason");
        assertThatThrownBy(() -> new CustomItems.Built(gem, CustomItems.Built.Reason.FAILED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a result with an item takes no reason");
    }

    @Test
    void aReferenceWithoutAnIdIsRefused() {
        CustomItems items = detected();

        assertThatThrownBy(() -> items.create("ia:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> items.create(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> items.create(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableResolvesAliasesAndNeedsTheIntegrationOnAndItsPluginEnabled() {
        presence.missing.add("Oraxen");
        CustomItems items = detected();

        assertThat(items.available("ia")).isTrue();
        assertThat(items.available("ItemsAdder")).isTrue();
        assertThat(items.available(" HDB ")).isTrue();
        assertThat(items.available("nexo")).isTrue();
        assertThat(items.available("oraxen")).isFalse();
        assertThat(items.available("mmoitems")).isFalse();
        assertThat(items.available("")).isFalse();

        when(bound(CustomItemPlugin.NEXO).isEnabled()).thenReturn(false);
        assertThat(items.available("nexo")).isFalse();
    }

    @Test
    void theStatusesAndTheActivePluginsCannotBeChanged() {
        presence.missing.add("ItemsAdder");
        CustomItems items = detected();

        assertThat(items.statuses()).hasSize(CustomItemPlugin.values().length);
        assertThatThrownBy(() -> items.statuses().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> items.active().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(items.active()).containsExactly(CustomItemPlugin.NEXO, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE);
    }

    @Test
    void detectRefusesAMissingOwnerByName() {
        assertThatThrownBy(() -> CustomItems.detect(null, errors, ALL))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
    }

    @Test
    void detectRefusesAMissingErrorSinkOrSet() {
        assertThatThrownBy(() -> CustomItems.detect(owner, null, ALL, presence, Map.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CustomItems.detect(owner, errors, null, presence, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    private PluginManager server() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        return manager;
    }

    @Test
    void thePublicDetectFindsNoPluginWhenNoneIsInstalled() {
        PluginManager manager = server();

        CustomItems items = CustomItems.detect(owner, errors, ALL);

        assertThat(items.active()).isEmpty();
        assertThat(items.statuses()).extracting(HookStatus::state).containsOnly(HookState.NOT_INSTALLED);
        assertThat(log.lines()).containsExactly("INFO Custom item plugins: ItemsAdder not installed;"
            + " Nexo not installed; Oraxen not installed; HeadDatabase not installed.");
        verify(manager, never()).registerEvents(any(), any());
    }

    @Test
    @SuppressWarnings("deprecation")
    void thePublicDetectBuildsEachIntegrationThroughItsOwnAdapter() {
        PluginManager manager = server();
        List<Listener> listeners = new ArrayList<>();
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            Plugin installed = mock(Plugin.class);
            PluginMeta meta = mock(PluginMeta.class);
            when(meta.getVersion()).thenReturn("2.0");
            when(installed.getPluginMeta()).thenReturn(meta);
            when(installed.isEnabled()).thenReturn(true);
            when(manager.getPlugin(plugin.plugin())).thenReturn(installed);
            when(manager.isPluginEnabled(plugin.plugin())).thenReturn(true);
        }
        doAnswer(call -> listeners.add(call.getArgument(0)))
            .when(manager).registerEvents(any(Listener.class), eq(owner));
        ItemStack head = mock(ItemStack.class);

        try (MockedStatic<ItemsAdder> itemsAdder = mockStatic(ItemsAdder.class);
             MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
                 when(api.isHead("1234")).thenReturn(true);
                 when(api.getItemHead("1234")).thenReturn(head);
             })) {
            itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(false);

            CustomItems items = CustomItems.detect(owner, errors, ALL);

            assertThat(items.active()).containsExactly(CustomItemPlugin.values());
            assertThat(items.statuses()).containsExactly(new HookStatus("ItemsAdder", HookState.LOADING, "2.0", null),
                new HookStatus("Nexo", HookState.LOADING, "2.0", null),
                new HookStatus("Oraxen", HookState.LOADING, "2.0", null),
                new HookStatus("HeadDatabase", HookState.ON, "2.0", null));
            assertThat(log.lines()).filteredOn(line -> line.startsWith("INFO")).containsExactly("INFO Custom item"
                + " plugins: ItemsAdder on, waiting for its items to load; Nexo on, waiting for its items to load;"
                + " Oraxen on, waiting for its items to load; HeadDatabase on.");
            assertThat(listeners).extracting(listener -> (Object) listener.getClass())
                .containsExactly(ItemsAdderProvider.class, NexoProvider.class, OraxenProvider.class);
            assertThat(items.ready()).isFalse();
            assertThat(items.create("hdb:1234")).isSameAs(head);
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    // Registers each listener the manager is given in a handler list of its own, as the server does per event.
    private HandlerList registering(PluginManager manager) {
        HandlerList handlers = new HandlerList();
        doAnswer(call -> {
            handlers.register(new RegisteredListener(call.getArgument(0), (listener, event) -> { },
                EventPriority.MONITOR, owner, false));
            return null;
        }).when(manager).registerEvents(any(Listener.class), eq(owner));
        return handlers;
    }

    private void install(PluginManager manager, CustomItemPlugin plugin) {
        Plugin installed = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.0");
        when(installed.getPluginMeta()).thenReturn(meta);
        when(installed.isEnabled()).thenReturn(true);
        when(manager.getPlugin(plugin.plugin())).thenReturn(installed);
        when(manager.isPluginEnabled(plugin.plugin())).thenReturn(true);
    }

    @Test
    @SuppressWarnings("deprecation")
    void closeUnregistersTheListenerDetectionRegisteredSoASecondDetectLeavesOne() {
        PluginManager manager = server();
        install(manager, CustomItemPlugin.ITEMSADDER);
        HandlerList handlers = registering(manager);

        try (MockedStatic<ItemsAdder> itemsAdder = mockStatic(ItemsAdder.class)) {
            itemsAdder.when(ItemsAdder::areItemsLoaded).thenReturn(false);

            CustomItems first = CustomItems.detect(owner, errors, EnumSet.of(CustomItemPlugin.ITEMSADDER));
            assertThat(handlers.getRegisteredListeners()).hasSize(1);

            first.close();
            assertThat(handlers.getRegisteredListeners()).isEmpty();
            assertThat(first.statuses().getFirst().state()).isEqualTo(HookState.LOADING);
            assertThat(first.active()).containsExactly(CustomItemPlugin.ITEMSADDER);
            first.close();

            CustomItems second = CustomItems.detect(owner, errors, EnumSet.of(CustomItemPlugin.ITEMSADDER));
            assertThat(handlers.getRegisteredListeners()).singleElement()
                .extracting(RegisteredListener::getListener).isInstanceOf(ItemsAdderProvider.class);
            second.close();
            assertThat(handlers.getRegisteredListeners()).isEmpty();
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void closeUnregistersTheListenersOfNexoAndOraxen() {
        PluginManager manager = server();
        install(manager, CustomItemPlugin.NEXO);
        install(manager, CustomItemPlugin.ORAXEN);
        HandlerList handlers = registering(manager);

        try {
            CustomItems items = CustomItems.detect(owner, errors,
                EnumSet.of(CustomItemPlugin.NEXO, CustomItemPlugin.ORAXEN));
            assertThat(handlers.getRegisteredListeners())
                .extracting(registered -> (Object) registered.getListener().getClass())
                .containsExactly(NexoProvider.class, OraxenProvider.class);

            items.close();

            assertThat(handlers.getRegisteredListeners()).isEmpty();
            assertThat(items.statuses()).extracting(HookStatus::state).containsExactly(HookState.SWITCHED_OFF,
                HookState.LOADING, HookState.LOADING, HookState.SWITCHED_OFF);
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void closeLeavesTheListenersOfOtherCodeAndAnIntegrationWithoutOneAlone() {
        PluginManager manager = server();
        HandlerList handlers = registering(manager);
        Listener other = new Listener() { };
        manager.registerEvents(other, owner);
        CustomItems items = detected();

        try {
            items.close();

            assertThat(handlers.getRegisteredListeners()).singleElement()
                .extracting(RegisteredListener::getListener).isSameAs(other);
            assertThat(items.active()).containsExactly(CustomItemPlugin.values());
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void theHeadDatabaseAdapterStaysOutOfTheIdPathAfterItsPluginDisables() {
        PluginManager manager = server();
        Plugin headDatabase = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.0");
        when(headDatabase.getPluginMeta()).thenReturn(meta);
        when(headDatabase.isEnabled()).thenReturn(true);
        when(manager.getPlugin("HeadDatabase")).thenReturn(headDatabase);
        when(manager.isPluginEnabled("HeadDatabase")).thenReturn(true);

        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            CustomItems items = CustomItems.detect(owner, errors, EnumSet.of(CustomItemPlugin.HEADDATABASE));

            assertThat(items.active()).containsExactly(CustomItemPlugin.HEADDATABASE);
            assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, false));
            when(headDatabase.isEnabled()).thenReturn(false);
            assertThat(items.resolve(dirt)).isEqualTo(new CustomItems.Resolution(null, false));
            assertThat(apis.constructed()).isEmpty();
        }
    }
}
