package so.alaz.taffy.test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.Set;

/** Class loaders on which chosen classes are present without their library being on the classpath. */
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
}
