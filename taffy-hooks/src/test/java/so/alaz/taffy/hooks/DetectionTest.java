package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class DetectionTest {

    private static final String PROBE = "net.example.api.Items";

    private final CapturedLog log = new CapturedLog();
    private final FakePresence presence = new FakePresence();
    private final List<Plugin> built = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final List<Map.Entry<String, Throwable>> reports = new ArrayList<>();

    @BeforeEach
    void errors() {
        errors.install((source, error) -> reports.add(Map.entry(source, error)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    private Detected<String> detect(String plugin) {
        return detect(plugin, true);
    }

    private Detected<String> detect(String plugin, boolean switchedOn) {
        return Detection.run(plugin, PROBE, switchedOn, presence, bound -> {
            built.add(bound);
            return plugin + " provider";
        }, log.logger, errors);
    }

    private Detected<String> detectFailing(Throwable failure) {
        return Detection.run("Nexo", PROBE, true, presence, bound -> {
            built.add(bound);
            throw DetectionTest.<RuntimeException>sneaky(failure);
        }, log.logger, errors);
    }

    @Test
    void anEnabledPluginWithItsApiIsBuiltWithTheBoundPlugin() {
        Detected<String> detected = detect("Vault");

        assertThat(detected.status()).isEqualTo(new HookStatus("Vault", HookState.ON, "1.0", null));
        assertThat(detected.provider()).isEqualTo("Vault provider");
        assertThat(detected.bound()).isSameAs(presence.plugins.get("Vault"));
        assertThat(built).containsExactly(presence.plugins.get("Vault"));
        assertThat(presence.probes).containsExactly(PROBE);
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aSwitchedOffPluginIsNeitherLookedAtNorBuiltNorLogged() {
        Detected<String> detected = detect("Nexo", false);

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.SWITCHED_OFF, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(detected.bound()).isNull();
        assertThat(presence.probes).isEmpty();
        assertThat(presence.plugins).isEmpty();
        assertThat(built).isEmpty();
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void anOnStatusReadsNotEnabledWhileTheBoundPluginIsDisabled() {
        Detected<String> detected = detect("Nexo");
        Plugin nexo = presence.plugins.get("Nexo");

        when(nexo.isEnabled()).thenReturn(false);
        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.NOT_ENABLED, null, null));
        assertThat(detected.provider()).isEqualTo("Nexo provider");

        when(nexo.isEnabled()).thenReturn(true);
        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.ON, "1.0", null));
    }

    @Test
    void aBoundPluginLoadedAgainUnderItsNameGivesTheNotEnabledStatusAReason() {
        Detected<String> detected = detect("Nexo");
        Plugin old = presence.plugins.get("Nexo");
        Plugin loadedAgain = mock(Plugin.class);
        when(loadedAgain.isEnabled()).thenReturn(true);

        when(old.isEnabled()).thenReturn(false);
        presence.plugins.put("Nexo", loadedAgain);

        assertThat(detected.status()).isEqualTo(
            new HookStatus("Nexo", HookState.NOT_ENABLED, null, Detected.replaced("Nexo")));
        assertThat(detected.bound()).isSameAs(old);
        assertThat(detected.provider()).isEqualTo("Nexo provider");
        assertThat(built).containsExactly(old);

        presence.unloaded.add("Nexo");
        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.NOT_ENABLED, null, null));
    }

    @Test
    void aMissingPluginIsNeitherProbedNorBuilt() {
        presence.missing.add("Oraxen");

        Detected<String> detected = detect("Oraxen");

        assertThat(detected.status()).isEqualTo(new HookStatus("Oraxen", HookState.NOT_INSTALLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(presence.probes).isEmpty();
        assertThat(built).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void anInstalledButDisabledPluginIsNeitherProbedNorBuilt() {
        presence.disabled.add("ItemsAdder");

        Detected<String> detected = detect("ItemsAdder");

        assertThat(detected.status()).isEqualTo(new HookStatus("ItemsAdder", HookState.NOT_ENABLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(presence.probes).isEmpty();
        assertThat(built).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void anEnabledPluginWithoutTheApiIsWarnedAboutAndNotBuiltOrReported() {
        presence.absentClasses.add(PROBE);

        Detected<String> detected = detect("Nexo");

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.API_MISSING, "1.0", null));
        assertThat(detected.provider()).isNull();
        assertThat(built).isEmpty();
        assertThat(log.lines()).containsExactly("WARNING Nexo 1.0 lacks the API that is read, so it stays off.");
        assertThat(reports).isEmpty();
    }

    @Test
    void anEnabledPluginWhoseApiOnlyItsOwnLoaderSeesIsNotVisibleAndNamesTheMissingEntry() {
        presence.invisibleClasses.add(PROBE);

        Detected<String> detected = detect("Nexo");

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.NOT_VISIBLE, "1.0",
            "Nexo is installed, but this plugin cannot see it, because its paper-plugin.yml does not list Nexo under"
                + " dependencies.server."));
        assertThat(detected.status().reason()).isEqualTo(Detection.notVisible("Nexo"));
        assertThat(detected.provider()).isNull();
        assertThat(built).isEmpty();
        assertThat(log.lines()).containsExactly("WARNING Nexo 1.0 is not visible to this plugin, so it stays off:"
            + " this plugin's paper-plugin.yml does not list Nexo under dependencies.server.");
        assertThat(reports).isEmpty();
    }

    @Test
    void aPluginUnloadedBeforeItsLoaderCouldBeAskedLacksTheApi() {
        presence.invisibleClasses.add(PROBE);
        presence.unloaded.add("Nexo");

        Detected<String> detected = detect("Nexo");

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.API_MISSING, "1.0", null));
        assertThat(log.lines()).containsExactly("WARNING Nexo 1.0 lacks the API that is read, so it stays off.");
    }

    @Test
    void aPluginUnloadedAfterTheChecksIsNotBuilt() {
        presence.unloaded.add("Vault");

        Detected<String> detected = detect("Vault");

        assertThat(detected.status()).isEqualTo(new HookStatus("Vault", HookState.NOT_INSTALLED, null, null));
        assertThat(built).isEmpty();
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void anUncheckedFactoryFailureLeavesItOffWithAWarningTheStackAndAReport() {
        IllegalStateException failure = new IllegalStateException("not loaded");

        Detected<String> detected = detectFailing(failure);

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.FAILED, "1.0", null));
        assertThat(detected.provider()).isNull();
        assertThat(detected.bound()).isNull();
        assertThat(built).hasSize(1);
        assertThat(log.lines()).containsExactly("WARNING Nexo 1.0 could not be hooked"
            + " (java.lang.IllegalStateException: not loaded), so it stays off.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(Map.entry("hook.nexo", failure));
    }

    @Test
    void aCheckedExceptionFromTheFactoryLeavesItOffAndIsReported() {
        IOException failure = new IOException("kotlin");

        Detected<String> detected = detectFailing(failure);

        assertThat(detected.status().state()).isEqualTo(HookState.FAILED);
        assertThat(log.lines()).containsExactly(
            "WARNING Nexo 1.0 could not be hooked (java.io.IOException: kotlin), so it stays off.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(Map.entry("hook.nexo", failure));
    }

    @Test
    void aLinkageErrorFromTheFactoryLeavesItOffAndIsReported() {
        NoClassDefFoundError failure = new NoClassDefFoundError("com/nexomc/nexo/api/NexoItems");

        Detected<String> detected = detectFailing(failure);

        assertThat(detected.status()).isEqualTo(new HookStatus("Nexo", HookState.FAILED, "1.0", null));
        assertThat(log.lines()).containsExactly("WARNING Nexo 1.0 could not be hooked"
            + " (java.lang.NoClassDefFoundError: com/nexomc/nexo/api/NexoItems), so it stays off.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(Map.entry("hook.nexo", failure));
    }

    @Test
    void anErrorOtherThanALinkageErrorPropagatesAndIsNeitherLoggedNorReported() {
        OutOfMemoryError failure = new OutOfMemoryError("heap");

        assertThatThrownBy(() -> detectFailing(failure)).isSameAs(failure);
        assertThat(log.lines()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aFactoryThatReturnsNullLeavesItOffAndIsReported() {
        Function<Plugin, String> factory = bound -> null;

        Detected<String> detected = Detection.run("Vault", PROBE, true, presence, factory, log.logger, errors);

        assertThat(detected.status()).isEqualTo(new HookStatus("Vault", HookState.FAILED, "1.0", null));
        assertThat(detected.provider()).isNull();
        assertThat(log.lines()).containsExactly("WARNING Vault 1.0 could not be hooked"
            + " (java.lang.NullPointerException: the factory returned null), so it stays off.");
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.getKey()).isEqualTo("hook.vault");
            assertThat(report.getValue()).isInstanceOf(NullPointerException.class)
                .hasMessage("the factory returned null");
        });
    }

    @Test
    void theReportSourceIsThePluginNameInLowerCase() {
        assertThat(Detection.source("LuckPerms")).isEqualTo("hook.luckperms");
        assertThat(Detection.source("WorldGuard")).isEqualTo("hook.worldguard");
        assertThat(Detection.source("PlaceholderAPI")).isEqualTo("hook.placeholderapi");
        assertThat(Detection.source("MiniPlaceholders")).isEqualTo("hook.miniplaceholders");
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            assertThat(Detection.source(plugin.plugin())).isEqualTo("hook." + plugin.key());
        }
    }

    @Test
    void theStatusesFromTheChecksCarryAReasonOnlyWhenNotVisible() {
        presence.missing.add("Oraxen");
        presence.disabled.add("Nexo");

        assertThat(Detection.check("Oraxen", PROBE, presence, log.logger).reason()).isNull();
        assertThat(Detection.check("Nexo", PROBE, presence, log.logger).reason()).isNull();
        presence.absentClasses.add(PROBE);
        assertThat(Detection.check("Vault", PROBE, presence, log.logger).reason()).isNull();
        presence.absentClasses.clear();
        presence.invisibleClasses.add(PROBE);
        assertThat(Detection.check("Vault", PROBE, presence, log.logger).reason())
            .isEqualTo(Detection.notVisible("Vault"));
        presence.invisibleClasses.clear();
        assertThat(Detection.check("Vault", PROBE, presence, log.logger)).isNull();
    }

    @Test
    void theSummaryJoinsEachDescriptionAfterTheLabel() {
        String summary = Detection.summary("Custom item plugins", List.of(
            new HookStatus("ItemsAdder", HookState.ON, "4.0.17", null),
            new HookStatus("Nexo", HookState.NOT_INSTALLED, null, null),
            new HookStatus("Oraxen", HookState.SWITCHED_OFF, null, null),
            new HookStatus("HeadDatabase", HookState.NOT_ENABLED, null, null)));

        assertThat(summary).isEqualTo("Custom item plugins: ItemsAdder on; Nexo not installed; Oraxen switched off;"
            + " HeadDatabase installed but not enabled.");
    }

    @Test
    void theSummaryOfOneStatusHasNoSeparator() {
        assertThat(Detection.summary("Economy", List.of(new HookStatus("Vault", HookState.FAILED, "1.7", null))))
            .isEqualTo("Economy: Vault 1.7 could not be hooked.");
    }
}
