package so.alaz.taffy.comficonf;

import com.comficonf.api.ConfigProvider;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.ApiRules;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.SimulatedClasspath;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Loads the module's classes on a class loader from which the ComfiConf API is absent, as on a server without the
 * bridge. A bridge plugin whose own class loader lacks the API too stands for a bridge that lacks it; one whose class
 * loader has the API stands for a bridge this plugin was not given access to.
 */
final class AbsentBridgeTest {

    private static final String OWN = "so.alaz.taffy.comficonf.";

    private static final String API = "com.comficonf.";

    private static final String PROBE = "com.comficonf.api.ComfiConfApi";

    private static final String API_BYTES = "com/comficonf/api";

    /** The classes that name a {@code com.comficonf.api} type, by the start of their binary simple name. */
    private static final List<String> API_TYPED = List.of("ComfiConfRegistration", "YamlFileProvider");

    private final CapturedLog log = new CapturedLog();

    private final ErrorReports errors = new ErrorReports();

    private final List<Map.Entry<String, Throwable>> reports = new CopyOnWriteArrayList<>();

    private final AtomicInteger registrations = new AtomicInteger();

    AbsentBridgeTest() {
        errors.install((source, error) -> reports.add(Map.entry(source, error)));
    }

    private static ClassLoader hidden() {
        return SimulatedClasspath.hiding(OWN, API);
    }

    private static ClassLoader withApi() {
        return AbsentBridgeTest.class.getClassLoader();
    }

    /** An enabled plugin whose class is defined by {@code loader}, so the classes it sees are those of the loader. */
    private static Plugin bridgeOn(ClassLoader loader, String version) {
        return bridgeOn(loader, version, true);
    }

    private static Plugin bridgeOn(ClassLoader loader, String version, boolean enabled) {
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn(version);
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getPluginMeta" -> meta;
                case "isEnabled" -> enabled;
                case "getName" -> "ComfiConf";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "ComfiConf " + version;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private Plugin ownerWith(Plugin bridge) {
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugin("ComfiConf")).thenReturn(bridge);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        return owner;
    }

    private static Class<?> copyOf(ClassLoader loader, Class<?> type) throws ClassNotFoundException {
        Class<?> copy = Class.forName(type.getName(), true, loader);
        assertThat(copy.getClassLoader()).isSameAs(loader);
        return copy;
    }

    /** Calls the copy's {@code ComfiConfConnection.connect} and returns the copy's status of the connection. */
    private static Object connectOn(ClassLoader loader, Plugin owner, ErrorReports errors,
                                    Supplier<?> registration) throws Throwable {
        Class<?> connectionType = copyOf(loader, ComfiConfConnection.class);
        Class<?> statusType = copyOf(loader, ComfiConfStatus.class);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle connect = lookup.findStatic(connectionType, "connect",
            MethodType.methodType(connectionType, Plugin.class, ErrorReports.class, boolean.class, Supplier.class));
        MethodHandle status = lookup.findVirtual(connectionType, "status", MethodType.methodType(statusType));
        MethodHandle resourceId = lookup.findVirtual(connectionType, "resourceId",
            MethodType.methodType(String.class));
        Object connection = connect.invoke(owner, errors, true, registration);
        assertThat(connection.getClass().getClassLoader()).isSameAs(loader);
        assertThat(resourceId.invoke(connection)).isNull();
        return status.invoke(connection);
    }

    private static Object read(Object status, String component) throws Throwable {
        Class<?> statusType = status.getClass();
        Class<?> type = component.equals("state")
            ? Class.forName(ComfiConfState.class.getName(), false, statusType.getClassLoader())
            : String.class;
        return MethodHandles.publicLookup().findVirtual(statusType, component, MethodType.methodType(type))
            .invoke(status);
    }

    private static String stateOf(Object status) throws Throwable {
        return ((Enum<?>) read(status, "state")).name();
    }

    private Supplier<Object> neverCalled() {
        return () -> {
            registrations.incrementAndGet();
            throw new AssertionError("registered without the API");
        };
    }

    private static boolean apiTyped(Class<?> type) {
        String binarySimpleName = type.getName().substring(OWN.length());
        return API_TYPED.stream().anyMatch(binarySimpleName::startsWith);
    }

    private static void readMembers(Class<?> type) {
        type.getDeclaredFields();
        type.getDeclaredMethods();
        type.getDeclaredConstructors();
        if (type.isRecord()) {
            type.getRecordComponents();
        }
    }

    @Test
    void theApiIsOnTheTestClasspathAndAbsentFromTheCopy() throws Exception {
        ClassLoader loader = hidden();

        assertThatCode(() -> Class.forName(PROBE, false, withApi())).doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName(PROBE, false, loader)).isInstanceOf(ClassNotFoundException.class);
        assertThat(copyOf(loader, ComfiConfConnection.class)).isNotSameAs(ComfiConfConnection.class);
        assertThat(bridgeOn(loader, "0.1.0").getClass().getClassLoader()).isSameAs(loader);
        assertThat(bridgeOn(withApi(), "0.1.0").getClass().getClassLoader()).isSameAs(withApi());
    }

    @Test
    void theClassesAPluginLoadsBeforeTheCheckLoadAndReflectOnTheCopy() {
        ClassLoader loader = hidden();

        for (Class<?> type : List.of(ComfiConfState.class, ComfiConfStatus.class, ComfiConfLink.class,
            ComfiConfConnection.class, YamlBytes.class)) {
            assertThatCode(() -> readMembers(copyOf(loader, type))).as(type.getName()).doesNotThrowAnyException();
        }
    }

    @Test
    void theApiTypedClassesFailOnTheCopyWhenTheirMembersAreRead() {
        ClassLoader loader = hidden();

        for (Class<?> type : List.of(ComfiConfRegistration.class, YamlFileProvider.class,
            YamlFileProvider.Builder.class)) {
            assertThatThrownBy(() -> readMembers(Class.forName(type.getName(), false, loader)))
                .as(type.getName())
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessageStartingWith(API_BYTES);
        }
    }

    @Test
    void theSplitIsExact() throws Exception {
        ClassLoader loader = hidden();
        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> expectedToFail = new ArrayList<>();

        for (Class<?> type : ApiRules.classes(ComfiConfStatus.class)) {
            if (apiTyped(type)) {
                expectedToFail.add(type.getName());
            }
            try {
                readMembers(Class.forName(type.getName(), false, loader));
                loaded.add(type.getName());
            } catch (NoClassDefFoundError absent) {
                failed.add(type.getName());
            }
        }

        assertThat(failed).containsExactlyInAnyOrderElementsOf(expectedToFail);
        assertThat(failed).contains(ComfiConfRegistration.class.getName(), YamlFileProvider.class.getName());
        assertThat(loaded).contains(ComfiConfState.class.getName(), ComfiConfStatus.class.getName(),
            ComfiConfLink.class.getName(), ComfiConfConnection.class.getName(), YamlBytes.class.getName());
    }

    @Test
    void aServerWithoutTheBridgeIsNotInstalledAndLogsNothing() throws Throwable {
        Object status = connectOn(hidden(), ownerWith(null), errors, neverCalled());

        assertThat(stateOf(status)).isEqualTo(ComfiConfState.NOT_INSTALLED.name());
        assertThat(read(status, "version")).isNull();
        assertThat(log.lines()).isEmpty();
        assertThat(registrations).hasValue(0);
        assertThat(reports).isEmpty();
    }

    @Test
    void aDisabledBridgeWithoutTheApiIsNotEnabledAndLogsNothing() throws Throwable {
        ClassLoader loader = hidden();

        Object status = connectOn(loader, ownerWith(bridgeOn(loader, "0.0.9", false)), errors, neverCalled());

        assertThat(stateOf(status)).isEqualTo(ComfiConfState.NOT_ENABLED.name());
        assertThat(read(status, "version")).isNull();
        assertThat(log.lines()).isEmpty();
        assertThat(registrations).hasValue(0);
        assertThat(reports).isEmpty();
    }

    @Test
    void aBridgeThisPluginCannotSeeIsNotVisibleWithOneWarningAndNoRegistration() throws Throwable {
        ClassLoader loader = hidden();
        Plugin owner = ownerWith(bridgeOn(withApi(), "0.1.0"));

        Object status = connectOn(loader, owner, errors, neverCalled());

        assertThat(stateOf(status)).isEqualTo(ComfiConfState.NOT_VISIBLE.name());
        assertThat(read(status, "version")).isEqualTo("0.1.0");
        assertThat(read(status, "reason")).isEqualTo("This plugin's paper-plugin.yml does not list ComfiConf under"
            + " dependencies.server with join-classpath: true, so this plugin's settings are edited in their files"
            + " only.");
        assertThat(read(status, "describe")).isEqualTo("ComfiConf 0.1.0 not visible to this plugin");
        assertThat(log.lines()).containsExactly("WARNING ComfiConf 0.1.0 is not visible to this plugin, so this"
            + " plugin's settings are edited in their files only: this plugin's paper-plugin.yml does not list"
            + " ComfiConf under dependencies.server with join-classpath: true.");
        assertThat(registrations).hasValue(0);
        assertThat(reports).isEmpty();
    }

    @Test
    void aBridgeWithoutTheApiIsApiMissingAtTheProbeWithOneWarningAndNoRegistration() throws Throwable {
        ClassLoader loader = hidden();
        Plugin owner = ownerWith(bridgeOn(loader, "0.0.9"));

        Object status = connectOn(loader, owner, errors, neverCalled());

        assertThat(stateOf(status)).isEqualTo(ComfiConfState.API_MISSING.name());
        assertThat(read(status, "version")).isEqualTo("0.0.9");
        assertThat(read(status, "describe")).isEqualTo("ComfiConf 0.0.9 lacks the API this plugin needs");
        String reason = (String) read(status, "reason");
        assertThat(reason).isEqualTo(ComfiConfConnection.incompatibleBridgeMessage("0.0.9", "missing " + PROBE))
            .contains("(missing com.comficonf.api.ComfiConfApi)");
        assertThat(log.lines()).containsExactly("WARNING " + reason);
        assertThat(log.records.getFirst().getThrown()).isNull();
        assertThat(registrations).hasValue(0);
        assertThat(reports).isEmpty();
    }

    @Test
    void aBridgeThatLacksOneApiClassIsApiMissingThroughTheRegistrationAndNothingEscapes() throws Throwable {
        String absent = "com.comficonf.api.ConfigResource";
        ClassLoader loader = SimulatedClasspath.hiding(OWN, absent);
        assertThatCode(() -> Class.forName(PROBE, false, loader)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName(absent, false, loader)).isInstanceOf(ClassNotFoundException.class);
        Plugin owner = ownerWith(bridgeOn(loader, "0.0.9"));
        ConfigProvider provider = mock(ConfigProvider.class);
        Supplier<Object> registration = () -> {
            registrations.incrementAndGet();
            try {
                Class<?> registrationType = Class.forName(ComfiConfRegistration.class.getName(), false, loader);
                Class<?> linkType = copyOf(loader, ComfiConfLink.class);
                return MethodHandles.publicLookup().findStatic(registrationType, "register",
                        MethodType.methodType(linkType, Plugin.class, String.class, String.class, String.class,
                            ConfigProvider.class))
                    .invoke(owner, "taffy:test", "Test settings", "Settings of the test plugin.", provider);
            } catch (Error | RuntimeException thrown) {
                throw thrown;
            } catch (Throwable checked) {
                throw new IllegalStateException(checked);
            }
        };

        Object status = connectOn(loader, owner, errors, registration);

        assertThat(stateOf(status)).isEqualTo(ComfiConfState.API_MISSING.name());
        String reason = (String) read(status, "reason");
        assertThat(reason).startsWith("The installed ComfiConf 0.0.9 does not include")
            .contains("(missing " + absent);
        assertThat(log.lines()).containsExactly("WARNING " + reason);
        assertThat(registrations).hasValue(1);
        assertThat(reports).isEmpty();
        verify(owner.getServer(), never()).getServicesManager();
    }

    @Test
    void onlyTheApiTypedClassFilesNameTheApi() throws Exception {
        Path classes = Path.of(ComfiConfConnection.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        TreeSet<String> naming = new TreeSet<>();
        TreeSet<String> apiTyped = new TreeSet<>();

        for (Path file : files) {
            String name = file.getFileName().toString();
            if (API_TYPED.stream().anyMatch(name::startsWith)) {
                apiTyped.add(name);
            }
            if (new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(API_BYTES)) {
                naming.add(name);
            }
        }

        assertThat(files).hasSizeGreaterThanOrEqualTo(API_TYPED.size() + 5);
        assertThat(naming).isEqualTo(apiTyped);
        assertThat(naming).contains("ComfiConfRegistration.class", "YamlFileProvider.class");
        assertThat(naming).doesNotContain("ComfiConfConnection.class", "ComfiConfLink.class",
            "ComfiConfState.class", "ComfiConfStatus.class", "YamlBytes.class");
    }
}
