package so.alaz.taffy.test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class loaders on which chosen classes are present without their library, or absent although their library is
 * on the classpath.
 */
public final class SimulatedClasspath {

    private SimulatedClasspath() {
    }

    /**
     * A new class loader on which every class named in {@code classNames} is found.
     *
     * <p>Its parent is the class loader of this class and is asked first, so a name the parent knows loads the
     * real class. A listed name the parent does not know loads as a class with no fields, methods or constructors.
     * Any other name the parent does not know is not found.
     *
     * @param classNames binary class names, as {@link Class#forName(String)} takes them
     */
    public static ClassLoader with(String... classNames) {
        return new Simulated(Set.copyOf(List.of(classNames)));
    }

    /**
     * A new class loader that defines its own copy of the classes below {@code ownPrefix} and on which the classes
     * below each of {@code hiddenPrefixes} are absent.
     *
     * <p>Its parent is the class loader of this class. A name that starts with a hidden prefix is not found, even
     * when it also starts with {@code ownPrefix}. A name that starts with {@code ownPrefix} is defined by the new
     * loader from the class file its parent finds as a resource, so the copy resolves every class it names through
     * the new loader and a class it names below a hidden prefix fails with a {@link NoClassDefFoundError} when it
     * is resolved. Such a name is not found when the parent has no class file for it. Every other name loads
     * through the parent, so the new loader shares those classes with the code that created it.
     *
     * <p>Each copy is defined with the code source its class file was found in: the jar, or the directory the class
     * file sits below. A coverage agent such as JaCoCo, which skips a class with no code source, therefore counts
     * what a copy runs toward the original class.
     *
     * @param ownPrefix the start of the binary names the new loader defines itself, such as
     *     {@code "so.alaz.taffy.hooks."}
     * @param hiddenPrefixes the starts of the binary names that are not found
     * @throws IllegalArgumentException if {@code ownPrefix} or any of {@code hiddenPrefixes} is blank
     * @throws NullPointerException if {@code ownPrefix}, {@code hiddenPrefixes} or any of its elements is
     *     {@code null}
     */
    public static ClassLoader hiding(String ownPrefix, String... hiddenPrefixes) {
        if (ownPrefix.isBlank()) {
            throw new IllegalArgumentException("the own prefix is blank, so every class would be copied");
        }
        List<String> hidden = List.of(hiddenPrefixes);
        if (hidden.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("a hidden prefix is blank, so every class would be hidden");
        }
        return new Hiding(ownPrefix, hidden);
    }

    private static final class Simulated extends ClassLoader {

        private final Set<String> classNames;

        Simulated(Set<String> classNames) {
            super(SimulatedClasspath.class.getClassLoader());
            this.classNames = classNames;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!classNames.contains(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = ClassFile.of().build(ClassDesc.of(name), builder -> {
            });
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final class Hiding extends ClassLoader {

        private final String ownPrefix;
        private final List<String> hiddenPrefixes;
        private final Map<String, ProtectionDomain> domains = new ConcurrentHashMap<>();

        Hiding(String ownPrefix, List<String> hiddenPrefixes) {
            super(SimulatedClasspath.class.getClassLoader());
            this.ownPrefix = ownPrefix;
            this.hiddenPrefixes = hiddenPrefixes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hiddenPrefixes.stream().anyMatch(name::startsWith)) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith(ownPrefix)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> own = findLoadedClass(name);
                if (own != null) {
                    return own;
                }
                String path = name.replace('.', '/') + ".class";
                URL file = getParent().getResource(path);
                if (file == null) {
                    throw new ClassNotFoundException(name);
                }
                try (InputStream in = file.openStream()) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length, domain(file, path));
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
        }

        // A class file in a jar is read through a jar: URL that ends in !/ and the entry; one in a directory through
        // a URL that ends in the class file's path. A location that is not a valid URL gives no code source.
        private ProtectionDomain domain(URL file, String path) {
            String text = file.toExternalForm();
            String location;
            int entry = text.indexOf("!/");
            if (text.startsWith("jar:") && entry > 0) {
                location = text.substring("jar:".length(), entry);
            } else if (text.endsWith(path)) {
                location = text.substring(0, text.length() - path.length());
            } else {
                location = text;
            }
            return domains.computeIfAbsent(location, key -> {
                CodeSource source;
                try {
                    source = new CodeSource(URI.create(key).toURL(), (CodeSigner[]) null);
                } catch (MalformedURLException | IllegalArgumentException invalid) {
                    source = null;
                }
                return new ProtectionDomain(source, null, this, null);
            });
        }
    }
}
