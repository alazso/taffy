package so.alaz.taffy.test;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Checks a Taffy module's design rules against every class and file of its compiled main output.
 *
 * <p>The checks serve Taffy modules only: an anchor whose package is not a subpackage of {@code so.alaz.taffy}, such
 * as {@code so.alaz.taffy.platform}, fails each check with an {@link IllegalArgumentException}, so a scan of other
 * code cannot pass by finding nothing. A class in {@code so.alaz.taffy} itself is not in a module and fails too.
 *
 * <p>Each check takes an anchor, which is any class of the module's main source set. The output is found through
 * the anchor's code source: the jar the anchor was loaded from, or its classes directory. Beside a classes
 * directory {@code classes/java/main}, the directory {@code resources/main} belongs to the output when it exists.
 * The module's classes are the class files below {@code so/alaz/taffy} in the output. They are loaded with the
 * anchor's class loader and are not initialized.
 *
 * <p>An anchor with no code source, which is the case for every JDK class, fails each check with a
 * {@link NullPointerException}. A module class with a supertype that the anchor's class loader does not find
 * cannot be loaded, so each check that loads the classes fails with a {@link NoClassDefFoundError}.
 *
 * <p>This class names {@code org.bukkit.plugin.Plugin}, so a caller needs paper-api on its test runtime classpath.
 */
public final class ApiRules {

    private static final String MODULE_PACKAGE = "so.alaz.taffy.";
    private static final String MODULE_DIRECTORY = "so/alaz/taffy";

    private static final List<String> ALLOWED_PACKAGES = List.of(
        "java.", "javax.", "org.bukkit.", "io.papermc.", "com.destroystokyo.paper.", "com.mojang.brigadier.",
        "net.kyori.", "org.jetbrains.annotations.", MODULE_PACKAGE);

    /** The package of the one module allowed to name {@link #COMFICONF_API} types. */
    private static final String COMFICONF_PACKAGE = "so.alaz.taffy.comficonf";
    private static final String COMFICONF_API = "com.comficonf.api.";

    private static final List<String> COMFICONF_ALLOWED_PACKAGES =
        Stream.concat(ALLOWED_PACKAGES.stream(), Stream.of(COMFICONF_API)).toList();

    /** The files the build's Jar task puts into every jar. */
    private static final Set<String> JAR_TASK_FILES = Set.of("META-INF/MANIFEST.MF", "META-INF/LICENSE");

    private static final List<Class<?>> NEVER_STATIC =
        List.of(Plugin.class, Logger.class, Executor.class, Thread.class);

    private ApiRules() {
    }

    /**
     * The module's classes, nested and non-public ones included, in the order of their class file paths.
     *
     * @throws IllegalArgumentException if the anchor is not in a Taffy module
     * @throws IOException if the output cannot be read
     * @throws ClassNotFoundException if the anchor's class loader does not find a class of the output
     */
    public static List<Class<?>> classes(Class<?> anchor) throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        for (Path output : mainOutput(anchor)) {
            for (String file : moduleFiles(output)) {
                if (file.endsWith(".class")) {
                    String name = file.substring(0, file.length() - ".class".length()).replace('/', '.');
                    classes.add(Class.forName(name, false, anchor.getClassLoader()));
                }
            }
        }
        return classes;
    }

    /**
     * One line per type from outside the allowed packages that a public or protected signature of the module
     * names. An empty set means the rule holds.
     *
     * <p>The allowed packages are {@code java}, {@code javax}, {@code org.bukkit}, {@code io.papermc},
     * {@code com.destroystokyo.paper}, {@code com.mojang.brigadier} (the command library Paper's command API is
     * built on), {@code net.kyori}, {@code org.jetbrains.annotations} and {@code so.alaz.taffy}, each with its
     * subpackages. One exception: when the anchor and the checked class are both
     * in the package {@code so.alaz.taffy.comficonf}, not a subpackage of it, {@code com.comficonf.api} and its
     * subpackages are allowed too. A class is checked when it and its enclosing classes are
     * public or protected, together with the module superclasses and superinterfaces it inherits members from,
     * whatever their visibility: its superclasses up to the first one outside the module, and every module
     * interface that it or one of those implements or extends, directly or through another module interface.
     *
     * <p>The signatures of a class are its type parameters, its supertypes, its record components, its public and
     * protected fields, and the type parameters, parameters, exceptions and return types of its public and
     * protected constructors and methods. The static methods of an inherited interface that is not itself public
     * or protected are left out, because a class does not inherit them. A class whose members cannot be read,
     * because one names a type that is not on the classpath, gives a line of its own.
     *
     * @throws IllegalArgumentException if the anchor is not in a Taffy module
     * @throws IOException if the output cannot be read
     * @throws ClassNotFoundException if the anchor's class loader does not find a class of the output
     */
    public static Set<String> signatureViolations(Class<?> anchor) throws IOException, ClassNotFoundException {
        return violations(anchor, type -> exposedSignatureViolations(anchor, type));
    }

    /**
     * One line per static field of the module, of any visibility, whose type names a plugin, logger, executor or
     * thread. An empty set means the rule holds.
     *
     * <p>A field names a type when it is that type or a subtype of it, an array of it, or a generic type with it
     * as a type argument or bound. A static field that names a module class also gives one line per instance field
     * of that class or of its module superclasses that names one of the four, and the instance fields that name a
     * module class are followed the same way. A module class named only as a type argument, as {@code Keeper} is
     * in {@code Class<Keeper>} or {@code Comparator<Keeper>}, is followed too, whether or not the field holds an
     * instance of it. A class whose fields cannot be read, because one names a type that is not on the classpath,
     * gives a line of its own.
     *
     * @throws IllegalArgumentException if the anchor is not in a Taffy module
     * @throws IOException if the output cannot be read
     * @throws ClassNotFoundException if the anchor's class loader does not find a class of the output
     */
    public static Set<String> staticHolderViolations(Class<?> anchor) throws IOException, ClassNotFoundException {
        return violations(anchor, ApiRules::staticHolderViolationsOf);
    }

    /**
     * The files of the output, at any path, that are neither class files nor {@code META-INF/MANIFEST.MF} or
     * {@code META-INF/LICENSE}, as slash-separated paths from the root of the directory or jar that holds them.
     * An empty list means the output holds no resource file.
     *
     * @throws IllegalArgumentException if the anchor is not in a Taffy module
     * @throws IOException if the output cannot be read
     */
    public static List<String> resourceFiles(Class<?> anchor) throws IOException {
        List<String> files = new ArrayList<>();
        for (Path output : mainOutput(anchor)) {
            files.addAll(resources(output));
        }
        return files;
    }

    /** The jar holding the anchor's module, or its classes directory and the resources directory beside it. */
    private static List<Path> mainOutput(Class<?> anchor) {
        Path classes = Path.of(URI.create(anchor.getProtectionDomain().getCodeSource().getLocation().toString()));
        if (!anchor.getPackageName().startsWith(MODULE_PACKAGE)) {
            throw new IllegalArgumentException(anchor.getName() + " is not in a Taffy module: ApiRules checks only"
                + " the classes below " + MODULE_DIRECTORY);
        }
        // Gradle writes build/classes/java/main and build/resources/main.
        Path resources = classes.resolve("../../../resources").resolve(classes.getFileName()).normalize();
        return Files.isDirectory(classes) && Files.isDirectory(resources) ? List.of(classes, resources) : List.of(classes);
    }

    /** Applies {@code rule} to every module class. A class whose members cannot be read is itself a violation. */
    private static Set<String> violations(Class<?> anchor, Function<Class<?>, Set<String>> rule)
        throws IOException, ClassNotFoundException {
        Set<String> violations = new LinkedHashSet<>();
        for (Class<?> type : classes(anchor)) {
            try {
                violations.addAll(rule.apply(type));
            } catch (LinkageError | TypeNotPresentException e) {
                violations.add(type.getName() + " names a type that is not on the test classpath: " + e);
            }
        }
        return violations;
    }

    /** The files below {@code so/alaz/taffy} in an output directory or jar, as sorted slash-separated paths from its root. */
    static List<String> moduleFiles(Path output) throws IOException {
        return files(output, MODULE_DIRECTORY);
    }

    /** The files below {@code directory}, where the empty path is the root, in an output directory or jar. */
    private static List<String> files(Path output, String directory) throws IOException {
        if (Files.isDirectory(output)) {
            return filesBelow(output, directory);
        }
        try (FileSystem jar = FileSystems.newFileSystem(output)) {
            return filesBelow(jar.getPath("/"), directory);
        }
    }

    private static List<String> filesBelow(Path root, String directory) throws IOException {
        Path start = root.resolve(directory);
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(start)) {
            return files.filter(Files::isRegularFile)
                .map(file -> root.relativize(file).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    /** The files of an output directory or jar, at any path, that are neither class files nor added by the Jar task. */
    static List<String> resources(Path output) throws IOException {
        return files(output, "").stream()
            .filter(file -> !file.endsWith(".class") && !JAR_TASK_FILES.contains(file))
            .toList();
    }

    /** Whether code outside the module's package can name {@code type}. */
    static boolean exported(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (!visible(c.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The signature violations of {@code type} and of the module superclasses and superinterfaces it inherits
     * members from, when it is exported.
     */
    private static Set<String> exposedSignatureViolations(Class<?> anchor, Class<?> type) {
        Set<String> violations = new LinkedHashSet<>();
        if (exported(type)) {
            for (Class<?> inherited : moduleSupertypes(type)) {
                violations.addAll(signatureViolationsOf(anchor, inherited));
            }
        }
        return violations;
    }

    /**
     * {@code type} when it is a module class, with its superclasses up to the first one outside the module and
     * every module interface that it, one of those superclasses or one of those interfaces implements or extends.
     */
    private static Set<Class<?>> moduleSupertypes(Class<?> type) {
        Set<Class<?>> supertypes = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && moduleClass(c); c = c.getSuperclass()) {
            supertypes.add(c);
            collectModuleInterfaces(c, supertypes);
        }
        return supertypes;
    }

    private static void collectModuleInterfaces(Class<?> type, Set<Class<?>> supertypes) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (moduleClass(implemented) && supertypes.add(implemented)) {
                collectModuleInterfaces(implemented, supertypes);
            }
        }
    }

    private static boolean moduleClass(Class<?> type) {
        return type.getName().startsWith(MODULE_PACKAGE);
    }

    /**
     * One line per type from outside the allowed packages that a public or protected signature of {@code type}
     * names, where the allowed packages are those {@link #signatureViolations} allows for this anchor and class.
     * The static methods of an interface that is not exported are not signatures.
     */
    static Set<String> signatureViolationsOf(Class<?> anchor, Class<?> type) {
        List<String> allowed = anchor.getPackageName().equals(COMFICONF_PACKAGE)
            && type.getPackageName().equals(COMFICONF_PACKAGE) ? COMFICONF_ALLOWED_PACKAGES : ALLOWED_PACKAGES;
        Set<String> violations = new LinkedHashSet<>();
        String owner = type.getName();
        check(violations, allowed, owner + " type parameter", type.getTypeParameters());
        check(violations, allowed, owner + " supertype", type.getGenericSuperclass());
        check(violations, allowed, owner + " supertype", type.getGenericInterfaces());
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                check(violations, allowed, owner + "." + component.getName(), component.getGenericType());
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (visible(field.getModifiers())) {
                check(violations, allowed, owner + "." + field.getName(), field.getGenericType());
            }
        }
        List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredConstructors()));
        executables.addAll(List.of(type.getDeclaredMethods()));
        // A class does not inherit the static methods of its superinterfaces.
        boolean staticsHidden = type.isInterface() && !exported(type);
        for (Executable executable : executables) {
            if (!visible(executable.getModifiers()) || staticsHidden && Modifier.isStatic(executable.getModifiers())) {
                continue;
            }
            String member = owner + "." + (executable instanceof Method ? executable.getName() : "<init>") + "()";
            check(violations, allowed, member, executable.getTypeParameters());
            check(violations, allowed, member, executable.getGenericParameterTypes());
            check(violations, allowed, member, executable.getGenericExceptionTypes());
            if (executable instanceof Method method) {
                check(violations, allowed, member, method.getGenericReturnType());
            }
        }
        return violations;
    }

    /**
     * One line per static field of {@code type} whose type names a plugin, logger, executor or thread, and one per
     * instance field that names one in a module class the static field's type names, directly or through the
     * instance fields of module classes.
     */
    static Set<String> staticHolderViolationsOf(Class<?> type) {
        Set<String> violations = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String holder = type.getName() + "." + field.getName() + " is static and holds ";
            Set<Class<?>> followed = new HashSet<>();
            for (Class<?> named : named(field.getGenericType())) {
                for (Class<?> banned : NEVER_STATIC) {
                    if (banned.isAssignableFrom(named)) {
                        violations.add(holder + banned.getName());
                    }
                }
                collectInstanceHolders(named, holder, violations, followed);
            }
        }
        return violations;
    }

    // followed ends the walk of a module class that reaches itself through its instance fields.
    private static void collectInstanceHolders(Class<?> held, String holder, Set<String> violations,
                                               Set<Class<?>> followed) {
        for (Class<?> c = held; c != null && moduleClass(c) && followed.add(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                for (Class<?> named : named(field.getGenericType())) {
                    for (Class<?> banned : NEVER_STATIC) {
                        if (banned.isAssignableFrom(named)) {
                            violations.add(holder + banned.getName() + " in " + c.getName() + "." + field.getName());
                        }
                    }
                    collectInstanceHolders(named, holder, violations, followed);
                }
            }
        }
    }

    private static void check(Set<String> violations, List<String> allowed, String member, Type... signature) {
        for (Type type : signature) {
            for (Class<?> named : named(type)) {
                if (!named.isPrimitive() && allowed.stream().noneMatch(named.getName()::startsWith)) {
                    violations.add(member + " names " + named.getName());
                }
            }
        }
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /** Every class {@code type} names: itself, array components, type arguments and the bounds of wildcards and type variables. */
    static Set<Class<?>> named(Type type) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        collect(type, classes, new HashSet<>());
        return classes;
    }

    // seen ends the walk of a type variable bounded by itself, as in T extends Comparable<T>.
    private static void collect(Type type, Set<Class<?>> classes, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        switch (type) {
            case Class<?> c when c.isArray() -> collect(c.getComponentType(), classes, seen);
            case Class<?> c -> classes.add(c);
            case GenericArrayType array -> collect(array.getGenericComponentType(), classes, seen);
            case ParameterizedType parameterized -> {
                collect(parameterized.getRawType(), classes, seen);
                collect(parameterized.getOwnerType(), classes, seen);
                collectAll(parameterized.getActualTypeArguments(), classes, seen);
            }
            case WildcardType wildcard -> {
                collectAll(wildcard.getUpperBounds(), classes, seen);
                collectAll(wildcard.getLowerBounds(), classes, seen);
            }
            case TypeVariable<?> variable -> collectAll(variable.getBounds(), classes, seen);
            default -> throw new IllegalArgumentException("Unknown kind of type: " + type);
        }
    }

    private static void collectAll(Type[] types, Set<Class<?>> classes, Set<Type> seen) {
        for (Type type : types) {
            collect(type, classes, seen);
        }
    }
}
