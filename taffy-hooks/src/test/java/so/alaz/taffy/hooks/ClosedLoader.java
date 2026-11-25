package so.alaz.taffy.hooks;

/**
 * A class loader as Paper leaves the loader of a plugin that failed to enable once its jar is closed: a class the
 * server shares, of the JDK, Bukkit, Paper, Adventure or a library the server bundles, still loads through the
 * parent, and every other lookup throws the given exception, as Paper throws
 * {@code IllegalStateException: The plugin classloader for <plugin> has thrown a zip file error.}
 */
final class ClosedLoader extends ClassLoader {

    private static final String[] SHARED = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.bukkit.",
        "io.papermc.", "com.destroystokyo.", "net.kyori.", "net.md_5.", "org.slf4j.", "org.apache.logging.",
        "org.jetbrains.annotations.", "org.jspecify.", "com.google."};

    private final RuntimeException failure;

    private ClosedLoader(RuntimeException failure) {
        super(ClosedLoader.class.getClassLoader());
        this.failure = failure;
    }

    static ClassLoader of(RuntimeException failure) {
        return new ClosedLoader(failure);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String shared : SHARED) {
            if (name.startsWith(shared)) {
                return super.loadClass(name, resolve);
            }
        }
        throw failure;
    }
}
