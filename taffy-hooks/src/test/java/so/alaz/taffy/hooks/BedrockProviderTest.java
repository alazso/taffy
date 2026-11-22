package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.SimulatedClasspath;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class BedrockProviderTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6");
    private static final UUID ALEX = UUID.fromString("7c1a3a52-6a0f-4f0b-9d55-0c3a5e7b9d11");

    private final Plugin plugin = mock(Plugin.class);
    @SuppressWarnings("unchecked")
    private final Predicate<UUID> check = mock(Predicate.class);
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final CapturedLog logged = new CapturedLog();

    @BeforeEach
    void bedrockPlugin() {
        when(plugin.isEnabled()).thenReturn(true);
        when(check.test(STEVE)).thenReturn(true);
        errors.install((source, error) -> {
            reported.add(source);
            failures.add(error);
        });
        unsetApis();
    }

    @AfterEach
    void unsetApis() {
        FloodgateApi.INSTANCE.set(null);
        GeyserApi.REGISTERED.set(null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> boolean raise(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * A class loader on which {@code name} is a public class that declares only a static {@code accessor} that
     * returns {@code null}, or nothing when {@code accessor} is {@code null}, and on which no other class of
     * Floodgate or Geyser is found.
     */
    private static ClassLoader changed(String name, @Nullable String accessor) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String asked) throws ClassNotFoundException {
                if (!asked.equals(name)) {
                    throw new ClassNotFoundException(asked);
                }
                ClassDesc type = ClassDesc.of(name);
                byte[] bytes = ClassFile.of().build(type, builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC);
                    if (accessor != null) {
                        builder.withMethodBody(accessor, MethodTypeDesc.of(type),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn());
                    }
                });
                return defineClass(asked, bytes, 0, bytes.length);
            }
        };
    }

    private PluginBedrock floodgate() {
        return new PluginBedrock(plugin, PluginBedrock.FLOODGATE, check, logged.logger, errors);
    }

    @Test
    void aPlayerTheBedrockPluginNamesIsABedrockPlayerAndAnyOtherIsNot() {
        PluginBedrock bedrock = floodgate();

        assertThat(bedrock.bedrock(STEVE)).isTrue();
        assertThat(bedrock.bedrock(ALEX)).isFalse();
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aDisabledBedrockPluginNamesNoBedrockPlayerWithoutACheck() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(floodgate().bedrock(STEVE)).isFalse();
        verifyNoInteractions(check);
        assertThat(reported).isEmpty();
    }

    @Test
    void aNullPlayerThrowsWithoutAReport() {
        PluginBedrock bedrock = floodgate();

        assertThatThrownBy(() -> bedrock.bedrock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("player");
        verifyNoInteractions(check);
        assertThat(reported).isEmpty();
    }

    @Test
    void aFailingCheckAnswersFalseWithOneWarningAndOneReport() {
        IllegalStateException broken = new IllegalStateException("handshake cache closed");
        when(check.test(STEVE)).thenThrow(broken);

        assertThat(floodgate().bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.floodgate");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING floodgate could not tell whether a player joined from"
            + " Bedrock (java.lang.IllegalStateException: handshake cache closed).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void repeatedFailuresAreLoggedOnceAMinuteAndEachIsReported() {
        when(check.test(any())).thenThrow(new IllegalStateException("handshake cache closed"));
        PluginBedrock bedrock = new PluginBedrock(plugin, PluginBedrock.GEYSER, check, logged.logger, errors);

        assertThat(bedrock.bedrock(STEVE)).isFalse();
        assertThat(bedrock.bedrock(ALEX)).isFalse();
        assertThat(bedrock.bedrock(STEVE)).isFalse();

        assertThat(reported).containsExactly("hook.geyser-spigot", "hook.geyser-spigot", "hook.geyser-spigot");
        assertThat(logged.lines()).containsExactly("WARNING Geyser-Spigot could not tell whether a player joined from"
            + " Bedrock (java.lang.IllegalStateException: handshake cache closed).");
    }

    @Test
    void aLinkageErrorInTheCheckAnswersFalseWithOneReport() {
        when(check.test(STEVE)).thenThrow(new NoClassDefFoundError("org/geysermc/floodgate/api/FloodgateApi"));

        assertThat(floodgate().bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.floodgate");
    }

    @Test
    void anErrorOtherThanALinkageErrorIsRethrownAndNotReported() {
        when(check.test(STEVE)).thenThrow(new OutOfMemoryError("heap"));
        PluginBedrock bedrock = floodgate();

        assertThatThrownBy(() -> bedrock.bedrock(STEVE)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void theFloodgateFactoryReadsAnUnsetApiAsNoBedrockPlayer() {
        BedrockProvider provider = FloodgateBedrock.create(plugin, logged.logger, errors);

        assertThat(provider).isInstanceOf(PluginBedrock.class);
        assertThat(provider.bedrock(STEVE)).isFalse();
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void theFloodgateFactoryAsksTheApiFloodgateSetAfterTheProviderWasBuilt() {
        BedrockProvider provider = FloodgateBedrock.create(plugin, logged.logger, errors);

        FloodgateApi.INSTANCE.set(STEVE::equals);

        assertThat(provider.bedrock(STEVE)).isTrue();
        assertThat(provider.bedrock(ALEX)).isFalse();
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aFloodgateCheckThatThrowsAnswersFalseWithOneWarningAndOneReport() {
        IllegalStateException broken = new IllegalStateException("handshake cache closed");
        FloodgateApi.INSTANCE.set(player -> {
            throw broken;
        });

        assertThat(FloodgateBedrock.create(plugin, logged.logger, errors).bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.floodgate");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING floodgate could not tell whether a player joined from"
            + " Bedrock (java.lang.IllegalStateException: handshake cache closed).");
    }

    @Test
    void aCheckedExceptionFromTheFloodgateCheckIsReportedAsTheCauseOfAnIllegalStateException() {
        IOException broken = new IOException("link reset");
        FloodgateApi.INSTANCE.set(player -> BedrockProviderTest.<RuntimeException>raise(broken));

        assertThat(FloodgateBedrock.create(plugin, logged.logger, errors).bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.floodgate");
        assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(failures.getFirst()).hasCause(broken);
    }

    @Test
    void anErrorFromTheFloodgateCheckOtherThanALinkageErrorIsRethrown() {
        FloodgateApi.INSTANCE.set(player -> {
            throw new OutOfMemoryError("heap");
        });
        BedrockProvider provider = FloodgateBedrock.create(plugin, logged.logger, errors);

        assertThatThrownBy(() -> provider.bedrock(STEVE)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
    }

    @Test
    void aDisabledFloodgateIsNotAsked() {
        FloodgateApi.INSTANCE.set(STEVE::equals);
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(FloodgateBedrock.create(plugin, logged.logger, errors).bedrock(STEVE)).isFalse();
        assertThat(reported).isEmpty();
    }

    @Test
    void theFloodgateFactoryFailsToLinkWhenTheApiClassIsNotFound() {
        ClassLoader hiding = SimulatedClasspath.hiding("so.alaz.taffy.hooks.none.", "org.geysermc.floodgate.");

        assertThatThrownBy(() -> FloodgateBedrock.create(hiding, plugin, logged.logger, errors))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessage("org.geysermc.floodgate.api.FloodgateApi")
            .hasCauseInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void theFloodgateFactoryFailsToLinkWhenAMethodItReadsIsGone() {
        ClassLoader noAccessor = changed(PluginBedrock.FLOODGATE_PROBE, null);
        ClassLoader noCheck = changed(PluginBedrock.FLOODGATE_PROBE, "getInstance");

        assertThatThrownBy(() -> FloodgateBedrock.create(noAccessor, plugin, logged.logger, errors))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage("org.geysermc.floodgate.api.FloodgateApi.getInstance()FloodgateApi")
            .hasCauseInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> FloodgateBedrock.create(noCheck, plugin, logged.logger, errors))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage("org.geysermc.floodgate.api.FloodgateApi.isFloodgatePlayer(UUID)boolean");
    }

    @Test
    void theGeyserFactoryReportsAnApiGeyserHasNotRegistered() {
        BedrockProvider provider = GeyserBedrock.create(plugin, logged.logger, errors);

        assertThat(provider).isInstanceOf(PluginBedrock.class);
        assertThat(provider.bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.geyser-spigot");
        assertThat(logged.lines()).containsExactly("WARNING Geyser-Spigot could not tell whether a player joined from"
            + " Bedrock (java.lang.RuntimeException: Api has not been registered yet!).");
    }

    @Test
    void theGeyserFactoryAsksTheApiGeyserRegisteredThroughTheMethodOfItsBaseInterface() {
        BedrockProvider provider = GeyserBedrock.create(plugin, logged.logger, errors);

        GeyserApi.REGISTERED.set(STEVE::equals);

        assertThat(provider.bedrock(STEVE)).isTrue();
        assertThat(provider.bedrock(ALEX)).isFalse();
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aGeyserCheckThatThrowsAnswersFalseWithOneReport() {
        IllegalStateException broken = new IllegalStateException("session map closed");
        GeyserApi.REGISTERED.set(player -> {
            throw broken;
        });

        assertThat(GeyserBedrock.create(plugin, logged.logger, errors).bedrock(STEVE)).isFalse();
        assertThat(reported).containsExactly("hook.geyser-spigot");
        assertThat(failures).containsExactly(broken);
    }

    @Test
    void theGeyserFactoryFailsToLinkWhenTheApiClassOrAMethodItReadsIsGone() {
        ClassLoader hiding = SimulatedClasspath.hiding("so.alaz.taffy.hooks.none.", "org.geysermc.geyser.");
        ClassLoader noAccessor = changed(PluginBedrock.GEYSER_PROBE, null);
        ClassLoader noCheck = changed(PluginBedrock.GEYSER_PROBE, "api");

        assertThatThrownBy(() -> GeyserBedrock.create(hiding, plugin, logged.logger, errors))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessage("org.geysermc.geyser.api.GeyserApi");
        assertThatThrownBy(() -> GeyserBedrock.create(noAccessor, plugin, logged.logger, errors))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage("org.geysermc.geyser.api.GeyserApi.api()GeyserApi");
        assertThatThrownBy(() -> GeyserBedrock.create(noCheck, plugin, logged.logger, errors))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage("org.geysermc.geyser.api.GeyserApi.isBedrockPlayer(UUID)boolean");
    }

    @Test
    void aFloodgateWhoseApiChangedCouldNotBeHookedAndGeyserIsUsed() {
        FakePresence presence = new FakePresence();
        ClassLoader noCheck = changed(PluginBedrock.FLOODGATE_PROBE, "getInstance");
        GeyserApi.REGISTERED.set(STEVE::equals);

        Detected<BedrockProvider> detected = PluginBedrock.detect(true, presence,
            bound -> FloodgateBedrock.create(noCheck, bound, logged.logger, errors),
            bound -> GeyserBedrock.create(bound, logged.logger, errors), logged.logger, errors);

        assertThat(detected.status()).isEqualTo(new HookStatus("Geyser-Spigot", HookState.ON, "1.0", null));
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
        assertThat(reported).containsExactly("hook.floodgate");
        assertThat(failures).singleElement().isInstanceOf(NoSuchMethodError.class);
        assertThat(logged.lines()).containsExactly("WARNING floodgate 1.0 could not be hooked"
            + " (java.lang.NoSuchMethodError: org.geysermc.floodgate.api.FloodgateApi.isFloodgatePlayer(UUID)boolean),"
            + " so it stays off.");
    }

    private Function<Plugin, BedrockProvider> named(String name, List<String> built) {
        return bound -> {
            built.add(name);
            return new PluginBedrock(bound, name, check, logged.logger, errors);
        };
    }

    private Detected<BedrockProvider> detect(FakePresence presence, boolean switchedOn, List<String> built) {
        return PluginBedrock.detect(switchedOn, presence, named(PluginBedrock.FLOODGATE, built),
            named(PluginBedrock.GEYSER, built), logged.logger, errors);
    }

    @Test
    void floodgateIsUsedWhenBothAreInstalledAndGeyserIsNotLookedAt() {
        FakePresence presence = new FakePresence();
        List<String> built = new ArrayList<>();

        Detected<BedrockProvider> detected = detect(presence, true, built);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.ON, "1.0", null));
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
        assertThat(built).containsExactly("floodgate");
        assertThat(presence.probes).containsExactly(PluginBedrock.FLOODGATE_PROBE);
        assertThat(presence.plugins).containsOnlyKeys("floodgate");
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void geyserIsUsedWhenFloodgateIsNotInstalled() {
        FakePresence presence = new FakePresence();
        presence.missing.add("floodgate");
        List<String> built = new ArrayList<>();

        Detected<BedrockProvider> detected = detect(presence, true, built);

        assertThat(detected.status()).isEqualTo(new HookStatus("Geyser-Spigot", HookState.ON, "1.0", null));
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
        assertThat(built).containsExactly("Geyser-Spigot");
        assertThat(presence.probes).containsExactly(PluginBedrock.GEYSER_PROBE);
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void geyserIsUsedWhenFloodgateCannotBeHooked() {
        FakePresence presence = new FakePresence();
        NoSuchMethodError removed = new NoSuchMethodError("FloodgateApi.isFloodgatePlayer(UUID)boolean");
        List<String> built = new ArrayList<>();

        Detected<BedrockProvider> detected = PluginBedrock.detect(true, presence, bound -> {
            throw removed;
        }, named(PluginBedrock.GEYSER, built), logged.logger, errors);

        assertThat(detected.status()).isEqualTo(new HookStatus("Geyser-Spigot", HookState.ON, "1.0", null));
        assertThat(detected.provider()).isNotNull();
        assertThat(built).containsExactly("Geyser-Spigot");
        assertThat(reported).containsExactly("hook.floodgate");
        assertThat(failures).containsExactly(removed);
        assertThat(logged.lines()).containsExactly("WARNING floodgate 1.0 could not be hooked"
            + " (java.lang.NoSuchMethodError: FloodgateApi.isFloodgatePlayer(UUID)boolean), so it stays off.");
    }

    @Test
    void geyserIsUsedWhenFloodgateIsInstalledButNotEnabled() {
        FakePresence presence = new FakePresence();
        presence.disabled.add("floodgate");

        Detected<BedrockProvider> detected = detect(presence, true, new ArrayList<>());

        assertThat(detected.status()).isEqualTo(new HookStatus("Geyser-Spigot", HookState.ON, "1.0", null));
        assertThat(detected.provider()).isNotNull();
    }

    @Test
    void withNeitherInstalledTheStatusIsFloodgatesNotInstalled() {
        FakePresence presence = new FakePresence();
        presence.missing.add("floodgate");
        presence.missing.add("Geyser-Spigot");
        List<String> built = new ArrayList<>();

        Detected<BedrockProvider> detected = detect(presence, true, built);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.NOT_INSTALLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(built).isEmpty();
        assertThat(presence.probes).isEmpty();
        assertThat(logged.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void whenNeitherTurnsOnTheStatusIsFloodgatesUnlessOnlyGeyserIsInstalled() {
        FakePresence floodgateOnly = new FakePresence();
        floodgateOnly.absentClasses.add(PluginBedrock.FLOODGATE_PROBE);
        floodgateOnly.missing.add("Geyser-Spigot");
        FakePresence both = new FakePresence();
        both.absentClasses.add(PluginBedrock.FLOODGATE_PROBE);
        both.disabled.add("Geyser-Spigot");
        FakePresence geyserOnly = new FakePresence();
        geyserOnly.missing.add("floodgate");
        geyserOnly.invisibleClasses.add(PluginBedrock.GEYSER_PROBE);
        List<String> built = new ArrayList<>();

        assertThat(detect(floodgateOnly, true, built).status())
            .isEqualTo(new HookStatus("floodgate", HookState.API_MISSING, "1.0", null));
        assertThat(detect(both, true, built).status())
            .isEqualTo(new HookStatus("floodgate", HookState.API_MISSING, "1.0", null));
        Detected<BedrockProvider> detected = detect(geyserOnly, true, built);

        assertThat(detected.status()).isEqualTo(new HookStatus("Geyser-Spigot", HookState.NOT_VISIBLE, "1.0",
            "Geyser-Spigot is installed, but this plugin cannot see it, because its paper-plugin.yml does not list"
                + " Geyser-Spigot under dependencies.server."));
        assertThat(detected.provider()).isNull();
        assertThat(built).isEmpty();
        assertThat(logged.lines()).containsExactly(
            "WARNING floodgate 1.0 lacks the API that is read, so it stays off.",
            "WARNING floodgate 1.0 lacks the API that is read, so it stays off.",
            "WARNING Geyser-Spigot 1.0 is not visible to this plugin, so it stays off: this plugin's paper-plugin.yml"
                + " does not list Geyser-Spigot under dependencies.server.");
        assertThat(reported).isEmpty();
    }

    @Test
    void switchedOffLooksAtNeitherPlugin() {
        FakePresence presence = new FakePresence();
        List<String> built = new ArrayList<>();

        Detected<BedrockProvider> detected = detect(presence, false, built);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.SWITCHED_OFF, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(built).isEmpty();
        assertThat(presence.probes).isEmpty();
        assertThat(presence.plugins).isEmpty();
    }

    @Test
    void anOnFloodgateReadsNotEnabledAndNamesNoBedrockPlayerWhileItIsDisabled() {
        FakePresence presence = new FakePresence();
        Detected<BedrockProvider> detected = detect(presence, true, new ArrayList<>());
        Plugin floodgate = presence.plugins.get("floodgate");

        when(floodgate.isEnabled()).thenReturn(false);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.NOT_ENABLED, null, null));
        assertThat(detected.provider().bedrock(STEVE)).isFalse();

        when(floodgate.isEnabled()).thenReturn(true);
        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.ON, "1.0", null));
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
    }

    private static PluginManager pluginManager(String... installed) {
        PluginManager plugins = mock(PluginManager.class);
        for (String name : installed) {
            Plugin found = mock(Plugin.class);
            PluginMeta meta = mock(PluginMeta.class);
            when(meta.getVersion()).thenReturn("2.2.5-SNAPSHOT (b121-a1b2c3d)");
            when(found.getPluginMeta()).thenReturn(meta);
            when(found.isEnabled()).thenReturn(true);
            when(plugins.getPlugin(name)).thenReturn(found);
            when(plugins.isPluginEnabled(name)).thenReturn(true);
        }
        return plugins;
    }

    private static Plugin owner(PluginManager plugins, CapturedLog log) {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        return owner;
    }

    @Test
    void detectingAnEnabledFloodgateTurnsItOnThroughItsApi() {
        PluginManager plugins = pluginManager("floodgate", "Geyser-Spigot");

        Detected<BedrockProvider> detected = BedrockProvider.detect(owner(plugins, logged), errors, true);

        assertThat(detected.status())
            .isEqualTo(new HookStatus("floodgate", HookState.ON, "2.2.5-SNAPSHOT (b121-a1b2c3d)", null));
        assertThat(detected.provider()).isInstanceOf(PluginBedrock.class);
        assertThat(detected.provider().bedrock(STEVE)).isFalse();
        FloodgateApi.INSTANCE.set(STEVE::equals);
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
        assertThat(detected.provider().bedrock(ALEX)).isFalse();
        verify(plugins, never()).getPlugin("Geyser-Spigot");
        assertThat(logged.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectingAnEnabledGeyserWithoutFloodgateTurnsItOnThroughItsApi() {
        Detected<BedrockProvider> detected = BedrockProvider.detect(owner(pluginManager("Geyser-Spigot"), logged),
            errors, true);

        assertThat(detected.status())
            .isEqualTo(new HookStatus("Geyser-Spigot", HookState.ON, "2.2.5-SNAPSHOT (b121-a1b2c3d)", null));
        assertThat(detected.provider()).isInstanceOf(PluginBedrock.class);
        GeyserApi.REGISTERED.set(STEVE::equals);
        assertThat(detected.provider().bedrock(STEVE)).isTrue();
        assertThat(detected.provider().bedrock(ALEX)).isFalse();
        assertThat(logged.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectingWithoutEitherLeavesItOff() {
        Detected<BedrockProvider> detected = BedrockProvider.detect(owner(pluginManager(), logged), errors, true);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.NOT_INSTALLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aSwitchedOffDetectionLooksAtNoPlugin() {
        PluginManager plugins = mock(PluginManager.class);

        Detected<BedrockProvider> detected = BedrockProvider.detect(owner(plugins, logged), errors, false);

        assertThat(detected.status()).isEqualTo(new HookStatus("floodgate", HookState.SWITCHED_OFF, null, null));
        assertThat(detected.provider()).isNull();
        verify(plugins, never()).getPlugin(any());
        verify(plugins, never()).isPluginEnabled(any(String.class));
        assertThat(logged.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectingRefusesAMissingOwnerOrErrorSink() {
        Plugin owner = owner(pluginManager("floodgate"), logged);

        assertThatThrownBy(() -> BedrockProvider.detect(null, errors, true))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> BedrockProvider.detect(owner, null, true))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
