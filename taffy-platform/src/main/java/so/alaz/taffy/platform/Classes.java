package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/** Class-presence detection for soft (optional) integrations, used to degrade when a plugin is absent. */
public final class Classes {

    private Classes() {
    }

    /**
     * {@code true} if the named class is loadable from the caller's classloader, without initializing it.
     *
     * <p>A lookup can fail for reasons other than the class being absent: the server searches every
     * plugin's classloader, so a plugin that failed to enable and had its jar closed makes the lookup
     * throw. The class then counts as absent, with a warning written to {@code logger}, rather than
     * failing the caller. An absent class is not logged.
     */
    public static boolean present(String className, Class<?> caller, Logger logger) {
        return present(className, caller.getClassLoader(), logger);
    }

    /**
     * {@code true} if the named class is loadable from {@code loader}, without initializing it. A
     * {@code null} loader is the bootstrap classloader. A failed lookup is handled as in
     * {@link #present(String, Class, Logger)}.
     */
    static boolean present(String className, @Nullable ClassLoader loader, Logger logger) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        } catch (LinkageError | RuntimeException ex) {
            logger.warning("Could not look up " + className + ", so it is treated as absent. "
                + "Another plugin on this server is likely broken: " + ex);
            return false;
        }
    }
}
