package so.alaz.taffy.platform;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelocationTest {

    private static final String ORIGINAL = "so.alaz.taffy.platform";

    private static final String MESSAGE = "Demo ships Taffy in its original package so.alaz.taffy, so its Taffy"
        + " classes mix with those of every other plugin that does the same. Its build needs"
        + " relocate(\"so.alaz.taffy\", \"<group>.libs.taffy\") in the shadowJar task, or the"
        + " so.alaz.taffy.paper-plugin Gradle plugin, which relocates Taffy.";

    @Test
    void theOriginalPackageUnderAPluginClassLoaderFailsWithThePluginName() {
        ClassLoader loader = new PluginLoader(named("Demo"));

        assertThatThrownBy(() -> new Relocation(ORIGINAL, () -> loader).verify())
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(MESSAGE);
    }

    @Test
    void theRootOfTheOriginalPackageFailsToo() {
        ClassLoader loader = new PluginLoader(named("Demo"));

        assertThatThrownBy(() -> new Relocation("so.alaz.taffy", () -> loader).verify())
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(MESSAGE);
    }

    @Test
    void aFailedCheckFailsAgainOnEveryCall() {
        ClassLoader loader = new PluginLoader(named("Demo"));
        Relocation relocation = new Relocation(ORIGINAL, () -> loader);

        assertThatThrownBy(relocation::verify).hasMessage(MESSAGE);
        assertThatThrownBy(relocation::verify).hasMessage(MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "com.example.shop.libs.taffy.platform", "so.alaz.demo.libs.taffy", "so.alaz.taffyshop", "so.alaz", ""})
    void anotherPackagePassesUnderAPluginClassLoader(String packageName) {
        ClassLoader loader = new PluginLoader(named("Demo"));

        assertThatCode(() -> new Relocation(packageName, () -> loader).verify()).doesNotThrowAnyException();
    }

    @Test
    void theOriginalPackagePassesUnderTheApplicationClassLoader() {
        ClassLoader application = ClassLoader.getSystemClassLoader();

        assertThatCode(() -> new Relocation(ORIGINAL, () -> application).verify()).doesNotThrowAnyException();
    }

    @Test
    void theOriginalPackagePassesUnderTheBootstrapClassLoader() {
        assertThatCode(() -> new Relocation(ORIGINAL, () -> null).verify()).doesNotThrowAnyException();
    }

    @Test
    void theClassLoaderIsInspectedOnceAndALaterCallReturnsAtOnce() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<@Nullable ClassLoader> loader = () -> {
            asked.incrementAndGet();
            return ClassLoader.getSystemClassLoader();
        };
        Relocation relocation = new Relocation(ORIGINAL, loader);

        relocation.verify();
        relocation.verify();
        relocation.verify();

        assertThat(asked).hasValue(1);
    }

    @Test
    void aPluginClassLoaderThatExtendsAnotherIsRecognized() {
        ClassLoader loader = new PluginLoader(named("Demo")) {
        };

        assertThatThrownBy(() -> new Relocation(ORIGINAL, () -> loader).verify()).hasMessage(MESSAGE);
    }

    @Test
    void aPluginClassLoaderWithoutANameFailsAsAPlugin() {
        ClassLoader blank = new PluginLoader(named(" "));
        ClassLoader failing = new PluginLoader(null);

        assertThatThrownBy(() -> new Relocation(ORIGINAL, () -> blank).verify())
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("A plugin ships Taffy in its original package so.alaz.taffy, ");
        assertThatThrownBy(() -> new Relocation(ORIGINAL, () -> failing).verify())
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("A plugin ships Taffy in its original package so.alaz.taffy, ");
    }

    @Test
    void theStartingTypesPassInAUnitTest() {
        assertThatCode(Relocation::check).doesNotThrowAnyException();
        assertThatCode(ErrorReports::new).doesNotThrowAnyException();
    }

    @Test
    void aStartingTypeLoadedByAPluginClassLoaderFailsAndTheCopyOfAnotherLoaderStillPasses() throws Exception {
        Class<?> plugin = new OwnCopy(named("Demo")).loadClass(ErrorReports.class.getName());
        Class<?> unitTest = new SimulatedCopy().loadClass(ErrorReports.class.getName());

        assertThat(plugin).isNotSameAs(ErrorReports.class);
        assertThat(unitTest).isNotSameAs(ErrorReports.class).isNotSameAs(plugin);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> plugin.getConstructor().newInstance())
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MESSAGE);
        }
        assertThatCode(() -> unitTest.getConstructor().newInstance()).doesNotThrowAnyException();
        assertThatCode(ErrorReports::new).doesNotThrowAnyException();
    }

    private static PluginMeta named(String name) {
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getName()).thenReturn(name);
        return meta;
    }

    /** Defines its own copy of the module's classes, as the class loader of a jar that holds them does. */
    private static Class<?> ownCopy(ClassLoader loader, String name, Definer definer) throws ClassNotFoundException {
        if (!name.startsWith(ORIGINAL + ".")) {
            return null;
        }
        String path = name.replace('.', '/') + ".class";
        try (InputStream stream = RelocationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = stream.readAllBytes();
            return definer.define(name, bytes);
        } catch (IOException ex) {
            throw new ClassNotFoundException(name, ex);
        }
    }

    private interface Definer {
        Class<?> define(String name, byte[] bytes);
    }

    private static class PluginLoader extends ClassLoader implements ConfiguredPluginClassLoader {

        private final @Nullable PluginMeta meta;

        PluginLoader(@Nullable PluginMeta meta) {
            super(RelocationTest.class.getClassLoader());
            this.meta = meta;
        }

        @Override
        public PluginMeta getConfiguration() {
            if (meta == null) {
                throw new UnsupportedOperationException("no configuration");
            }
            return meta;
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
            throws ClassNotFoundException {
            return loadClass(name, resolve);
        }

        @Override
        public void init(JavaPlugin plugin) {
        }

        @Override
        public @Nullable JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public @Nullable PluginClassLoaderGroup getGroup() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class OwnCopy extends PluginLoader {

        OwnCopy(PluginMeta meta) {
            super(meta);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = ownCopy(this, name, (className, bytes) -> defineClass(className, bytes, 0, bytes.length));
                }
                return loaded != null ? loaded : super.loadClass(name, resolve);
            }
        }
    }

    private static final class SimulatedCopy extends ClassLoader {

        SimulatedCopy() {
            super(RelocationTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = ownCopy(this, name, (className, bytes) -> defineClass(className, bytes, 0, bytes.length));
                }
                return loaded != null ? loaded : super.loadClass(name, resolve);
            }
        }
    }
}
