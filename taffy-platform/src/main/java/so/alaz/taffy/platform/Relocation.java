package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * The check that a plugin jar holds its copy of Taffy under a package of its own.
 *
 * <p>A server searches the class loaders of every plugin for a class one of them lacks. Two plugins that ship
 * Taffy in its original package therefore share classes: a class missing from one plugin's older copy loads from
 * the other plugin's copy and meets classes of another version. A plugin's build prevents that by relocating
 * Taffy, which the {@code so.alaz.taffy.paper-plugin} Gradle plugin does.
 *
 * <p>{@link ErrorReports}, {@link Text} and {@link Messages} call {@link #check()} when one is created, and so do
 * {@code ConfigLoader} and {@code YamlFolder} of {@code taffy-config}. Every other Taffy module takes an
 * {@link ErrorReports}, so a plugin that creates one of these five, or uses any other module, runs the check. A
 * plugin that uses only the other classes of {@code taffy-platform} and {@code taffy-config} does not.
 *
 * <p>A copy relocated to a package inside {@code so.alaz.taffy}, such as {@code so.alaz.taffy.shop.libs.taffy},
 * counts as not relocated, so a plugin whose own package is inside {@code so.alaz.taffy} cannot relocate Taffy
 * under it.
 *
 * <p>Thread-safe: {@link #check()} may be called from any thread.
 */
public final class Relocation {

    /** Assembled when the class is initialized, because a relocating build rewrites a constant that holds it. */
    private static final String ORIGINAL = String.join(".", "so", "alaz", "taffy");

    private static final String PLUGIN_LOADER =
        "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader";

    private static final Relocation OWN =
        new Relocation(Relocation.class.getPackageName(), Relocation.class::getClassLoader);

    private final String packageName;
    private final Supplier<@Nullable ClassLoader> loader;
    private volatile boolean passed;

    /** A check of a copy of Taffy in {@code packageName}, loaded by the class loader {@code loader} gives. */
    Relocation(String packageName, Supplier<@Nullable ClassLoader> loader) {
        this.packageName = packageName;
        this.loader = loader;
    }

    /**
     * Fails when this copy of Taffy sits in its original package and the class loader of a plugin loaded it.
     *
     * <p>Does nothing when the copy was relocated, and nothing when any other class loader loaded it, as in a
     * unit test or on a classpath the plugin's build starts. A class loader counts as a plugin's when it
     * implements Paper's {@code ConfiguredPluginClassLoader}, which the loaders of {@code paper-plugin.yml} and
     * of {@code plugin.yml} plugins both do. The class loader is inspected until the check first passes, once
     * for each copy of Taffy, and a later call returns at once. A failed check fails again on every call.
     *
     * @throws IllegalStateException when the copy is not relocated inside a plugin, with the message
     *     {@code Demo ships Taffy in its original package so.alaz.taffy, so its Taffy classes mix with those
     *     of every other plugin that does the same. Its build needs relocate("so.alaz.taffy",
     *     "<group>.libs.taffy") in the shadowJar task, or the so.alaz.taffy.paper-plugin Gradle plugin, which
     *     relocates Taffy.} for a plugin named {@code Demo}
     */
    public static void check() {
        OWN.verify();
    }

    void verify() {
        if (passed) {
            return;
        }
        if (original(packageName)) {
            String plugin = pluginOf(loader.get());
            if (plugin != null) {
                throw new IllegalStateException(message(plugin));
            }
        }
        passed = true;
    }

    /** Whether {@code packageName} is Taffy's original package or one inside it. */
    static boolean original(String packageName) {
        return packageName.equals(ORIGINAL) || packageName.startsWith(ORIGINAL + ".");
    }

    static String message(String plugin) {
        return plugin + " ships Taffy in its original package " + ORIGINAL + ", so its Taffy classes mix with those"
            + " of every other plugin that does the same. Its build needs relocate(\"" + ORIGINAL
            + "\", \"<group>.libs.taffy\") in the shadowJar task, or the " + ORIGINAL
            + ".paper-plugin Gradle plugin, which relocates Taffy.";
    }

    /**
     * The name of the plugin {@code loader} belongs to, or {@code null} when it is not a plugin's class loader.
     * A plugin's class loader that does not give its name counts as one of {@code A plugin}.
     */
    private static @Nullable String pluginOf(@Nullable ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        Class<?> pluginLoader = pluginLoaderType(loader.getClass());
        if (pluginLoader == null) {
            return null;
        }
        try {
            Method configuration = pluginLoader.getMethod("getConfiguration");
            Object meta = configuration.invoke(loader);
            Object name = configuration.getReturnType().getMethod("getName").invoke(meta);
            if (name instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // The loader is a plugin's whether or not it names the plugin.
        }
        return "A plugin";
    }

    /** Paper's plugin class loader interface among the supertypes of {@code type}, compared by name. */
    private static @Nullable Class<?> pluginLoaderType(@Nullable Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.getName().equals(PLUGIN_LOADER)) {
            return type;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            Class<?> found = pluginLoaderType(implemented);
            if (found != null) {
                return found;
            }
        }
        return pluginLoaderType(type.getSuperclass());
    }
}
