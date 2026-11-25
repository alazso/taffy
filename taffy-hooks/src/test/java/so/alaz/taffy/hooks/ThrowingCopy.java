package so.alaz.taffy.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * A class loader that defines its own copy of the classes below {@code so.alaz.taffy.hooks.} and throws one failure
 * for every class below a broken prefix, as a plugin's loader does once its lookup reaches the closed loader of a
 * plugin that failed to enable. Every other class loads through the loader of this class.
 */
final class ThrowingCopy extends ClassLoader {

    private static final String OWN = "so.alaz.taffy.hooks.";

    private final String brokenPrefix;
    private final RuntimeException failure;

    private ThrowingCopy(String brokenPrefix, RuntimeException failure) {
        super(ThrowingCopy.class.getClassLoader());
        this.brokenPrefix = brokenPrefix;
        this.failure = failure;
    }

    static ClassLoader of(String brokenPrefix, RuntimeException failure) {
        return new ThrowingCopy(brokenPrefix, failure);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(brokenPrefix)) {
            throw failure;
        }
        if (!name.startsWith(OWN)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> own = findLoadedClass(name);
            if (own != null) {
                return own;
            }
            URL file = getParent().getResource(name.replace('.', '/') + ".class");
            if (file == null) {
                throw new ClassNotFoundException(name);
            }
            try (InputStream in = file.openStream()) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException unreadable) {
                throw new ClassNotFoundException(name, unreadable);
            }
        }
    }
}
