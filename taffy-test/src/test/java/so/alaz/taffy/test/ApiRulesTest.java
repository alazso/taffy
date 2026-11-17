package so.alaz.taffy.test;

import org.assertj.core.api.SoftAssertions;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.exceptions.base.MockitoException;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRulesTest {

    private static final String BASE = "so.alaz.taffy.sample.Base";
    private static final String BROKEN = "so.alaz.taffy.sample.Broken";
    private static final String EXPOSED = "so.alaz.taffy.sample.Exposed";
    private static final String ORPHAN = "so.alaz.taffy.sample.Orphan";
    private static final String UNREADABLE = BROKEN + " names a type that is not on the test classpath: ";
    private static final String COMFICONF_API_TYPE = "com.comficonf.api.Thing";
    private static final String COMFICONF_OTHER_TYPE = "com.comficonf.internal.Other";
    private static final String COMFICONF_EXPOSED = "so.alaz.taffy.comficonf.Exposed";
    private static final String COMFICONF_SUB_EXPOSED = "so.alaz.taffy.comficonf.sub.Exposed";
    private static final String OTHER_EXPOSED = "so.alaz.taffy.other.Exposed";

    @Test
    void reportsADisallowedTypeInEachSignaturePosition() {
        String leaky = Leaky.class.getName();
        String testInfo = TestInfo.class.getName();
        String testReporter = TestReporter.class.getName();
        assertThat(ApiRules.signatureViolationsOf(Leaky.class, Leaky.class)).containsExactlyInAnyOrder(
            leaky + " type parameter names " + testInfo,
            leaky + " supertype names " + SoftAssertions.class.getName(),
            leaky + " supertype names " + testReporter,
            leaky + ".field names " + testInfo,
            leaky + ".<init>() names " + testInfo,
            leaky + ".returns() names " + testInfo,
            leaky + ".takes() names " + testInfo,
            leaky + ".takes() names " + testReporter,
            leaky + ".fails() names " + MockitoException.class.getName(),
            leaky + ".nests() names " + testInfo);

        String leakyRecord = LeakyRecord.class.getName();
        assertThat(ApiRules.signatureViolationsOf(LeakyRecord.class, LeakyRecord.class)).containsExactlyInAnyOrder(
            leakyRecord + ".info names " + testInfo,
            leakyRecord + ".<init>() names " + testInfo,
            leakyRecord + ".info() names " + testInfo);
    }

    @Test
    void exportsOnlyAPublicClassWhoseEnclosingClassesArePublic() {
        assertThat(ApiRules.exported(CapturedLog.class)).isTrue();
        assertThat(ApiRules.exported(LeakyRecord.class)).isFalse();
    }

    @Test
    void anAnchorWithNoCodeSourceFailsEachCheck() {
        assertThatThrownBy(() -> ApiRules.classes(String.class)).isExactlyInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ApiRules.signatureViolations(String.class)).isExactlyInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ApiRules.staticHolderViolations(String.class)).isExactlyInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ApiRules.resourceFiles(String.class)).isExactlyInstanceOf(NullPointerException.class);
    }

    @Test
    void anAnchorOutsideTaffyFailsEachCheck() {
        String message = Test.class.getName() + " is not in a Taffy module: ApiRules checks only the classes below"
            + " so/alaz/taffy";

        assertThatThrownBy(() -> ApiRules.classes(Test.class))
            .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> ApiRules.signatureViolations(Test.class))
            .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> ApiRules.staticHolderViolations(Test.class))
            .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> ApiRules.resourceFiles(Test.class))
            .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void reportsEachStaticHolder() {
        String holder = StaticHolder.class.getName();
        assertThat(ApiRules.staticHolderViolationsOf(StaticHolder.class)).containsExactlyInAnyOrder(
            holder + ".plugin is static and holds " + Plugin.class.getName(),
            holder + ".logger is static and holds " + Logger.class.getName(),
            holder + ".executor is static and holds " + Executor.class.getName(),
            holder + ".thread is static and holds " + Thread.class.getName(),
            holder + ".threads is static and holds " + Thread.class.getName());
    }

    @Test
    void listsTheModuleFilesOfADirectoryAndOfAJar(@TempDir Path temp) throws IOException {
        Path directory = temp.resolve("classes");
        fill(directory);
        Path jar = temp.resolve("module.jar");
        try (FileSystem zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            fill(zip.getPath("/"));
        }

        for (Path output : List.of(directory, jar)) {
            assertThat(ApiRules.moduleFiles(output))
                .containsExactly("so/alaz/taffy/platform/Some.class", "so/alaz/taffy/platform/lang/en.yml");
            assertThat(ApiRules.resources(output)).containsExactly(
                "META-INF/services/java.sql.Driver",
                "config.yml",
                "lang/en.yml",
                "so/alaz/taffy/platform/lang/en.yml");
        }
    }

    @Test
    void aClassesDirectoryIsFoundThroughTheAnchorWithTheResourcesDirectoryBesideIt(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        sampleModule(classes);
        Files.createFile(classes.resolve("so/alaz/taffy/sample/notes.txt"));
        Files.createDirectories(temp.resolve("build/resources/main/lang"));
        Files.createFile(temp.resolve("build/resources/main/lang/en.yml"));

        try (URLClassLoader loader = loaderOf(classes)) {
            Class<?> anchor = Class.forName(EXPOSED, false, loader);

            List<Class<?>> found = ApiRules.classes(anchor);

            assertThat(found.stream().map(Class::getName)).containsExactly(BASE, BROKEN, EXPOSED);
            assertThat(found).allSatisfy(type -> assertThat(type.getClassLoader()).isSameAs(loader));
            assertThat(ApiRules.resourceFiles(anchor))
                .containsExactly("so/alaz/taffy/sample/notes.txt", "lang/en.yml");
        }
    }

    @Test
    void aClassesDirectoryWithNoResourcesDirectoryBesideItIsTheWholeOutput(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        sampleModule(classes);

        try (URLClassLoader loader = loaderOf(classes)) {
            Class<?> anchor = Class.forName(EXPOSED, false, loader);

            assertThat(ApiRules.classes(anchor).stream().map(Class::getName)).containsExactly(BASE, BROKEN, EXPOSED);
            assertThat(ApiRules.resourceFiles(anchor)).isEmpty();
        }
    }

    @Test
    void aJarIsFoundThroughTheAnchor(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("module.jar");
        try (FileSystem zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Path root = zip.getPath("/");
            sampleModule(root);
            Files.createDirectories(root.resolve("META-INF"));
            Files.createFile(root.resolve("META-INF/MANIFEST.MF"));
            Files.createFile(root.resolve("META-INF/LICENSE"));
            Files.createFile(root.resolve("config.yml"));
        }

        try (URLClassLoader loader = loaderOf(jar)) {
            Class<?> anchor = Class.forName(EXPOSED, false, loader);

            List<Class<?>> found = ApiRules.classes(anchor);

            assertThat(found.stream().map(Class::getName)).containsExactly(BASE, BROKEN, EXPOSED);
            assertThat(found).allSatisfy(type -> assertThat(type.getClassLoader()).isSameAs(loader));
            assertThat(ApiRules.resourceFiles(anchor)).containsExactly("config.yml");
        }
    }

    @Test
    void theSignatureRuleCoversEveryExportedClassOfTheOutputAndWhatItInherits(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        sampleModule(classes);

        try (URLClassLoader loader = loaderOf(classes)) {
            List<String> violations = List.copyOf(ApiRules.signatureViolations(Class.forName(EXPOSED, false, loader)));

            assertThat(violations).hasSize(2);
            assertThat(violations.get(0)).startsWith(UNREADABLE).contains("missing/library/Gone");
            assertThat(violations.get(1)).isEqualTo(BASE + ".info names " + TestInfo.class.getName());
        }
    }

    @Test
    void theStaticRuleCoversEveryClassOfTheOutput(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        sampleModule(classes);

        try (URLClassLoader loader = loaderOf(classes)) {
            List<String> violations = List.copyOf(ApiRules.staticHolderViolations(Class.forName(EXPOSED, false, loader)));

            assertThat(violations).hasSize(2);
            assertThat(violations.get(0)).startsWith(UNREADABLE).contains("missing/library/Gone");
            assertThat(violations.get(1)).isEqualTo(EXPOSED + ".worker is static and holds " + Thread.class.getName());
        }
    }

    @Test
    void aClassWithASupertypeOnNoClasspathFailsEachCheckThatLoadsTheClasses(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        sampleModule(classes);
        writeClass(classes, ORPHAN, type -> type.withSuperclass(ClassDesc.of("missing.library.Gone")));

        try (URLClassLoader loader = loaderOf(classes)) {
            Class<?> anchor = Class.forName(EXPOSED, false, loader);

            assertThatThrownBy(() -> ApiRules.classes(anchor)).isExactlyInstanceOf(NoClassDefFoundError.class);
            assertThatThrownBy(() -> ApiRules.signatureViolations(anchor)).isExactlyInstanceOf(NoClassDefFoundError.class);
            assertThatThrownBy(() -> ApiRules.staticHolderViolations(anchor)).isExactlyInstanceOf(NoClassDefFoundError.class);
            assertThat(ApiRules.resourceFiles(anchor)).isEmpty();
        }
    }

    @Test
    void onlyTheComficonfPackageMayNameComficonfApiTypesAndOnlyWhenTheAnchorIsInIt(@TempDir Path temp)
        throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        comficonfSample(classes);

        try (URLClassLoader loader = loaderOf(classes)) {
            Class<?> comficonf = Class.forName(COMFICONF_EXPOSED, false, loader);
            Class<?> sub = Class.forName(COMFICONF_SUB_EXPOSED, false, loader);
            Class<?> other = Class.forName(OTHER_EXPOSED, false, loader);

            assertThat(ApiRules.signatureViolationsOf(comficonf, comficonf))
                .containsExactly(COMFICONF_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
            assertThat(ApiRules.signatureViolationsOf(comficonf, sub)).containsExactlyInAnyOrder(
                COMFICONF_SUB_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                COMFICONF_SUB_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
            assertThat(ApiRules.signatureViolationsOf(comficonf, other)).containsExactlyInAnyOrder(
                OTHER_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                OTHER_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
            assertThat(ApiRules.signatureViolationsOf(other, comficonf)).containsExactlyInAnyOrder(
                COMFICONF_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                COMFICONF_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
            assertThat(ApiRules.signatureViolationsOf(sub, comficonf)).containsExactlyInAnyOrder(
                COMFICONF_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                COMFICONF_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
        }
    }

    @Test
    void theComficonfAllowanceFollowsTheAnchorThroughAWholeOutput(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("build/classes/java/main");
        comficonfSample(classes);

        try (URLClassLoader loader = loaderOf(classes)) {
            assertThat(ApiRules.signatureViolations(Class.forName(COMFICONF_EXPOSED, false, loader)))
                .containsExactlyInAnyOrder(
                    COMFICONF_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE,
                    COMFICONF_SUB_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                    COMFICONF_SUB_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE,
                    OTHER_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                    OTHER_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
            assertThat(ApiRules.signatureViolations(Class.forName(OTHER_EXPOSED, false, loader)))
                .containsExactlyInAnyOrder(
                    COMFICONF_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                    COMFICONF_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE,
                    COMFICONF_SUB_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                    COMFICONF_SUB_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE,
                    OTHER_EXPOSED + ".thing() names " + COMFICONF_API_TYPE,
                    OTHER_EXPOSED + ".other() names " + COMFICONF_OTHER_TYPE);
        }
    }

    private static void fill(Path root) throws IOException {
        Files.createDirectories(root.resolve("so/alaz/taffy/platform/lang"));
        Files.createFile(root.resolve("so/alaz/taffy/platform/Some.class"));
        Files.createFile(root.resolve("so/alaz/taffy/platform/lang/en.yml"));
        Files.createDirectories(root.resolve("META-INF/services"));
        Files.createFile(root.resolve("META-INF/LICENSE"));
        Files.createFile(root.resolve("META-INF/MANIFEST.MF"));
        Files.createFile(root.resolve("META-INF/services/java.sql.Driver"));
        Files.createDirectories(root.resolve("lang"));
        Files.createFile(root.resolve("lang/en.yml"));
        Files.createFile(root.resolve("config.yml"));
        Files.createDirectories(root.resolve("other/library"));
        Files.createFile(root.resolve("other/library/Other.class"));
    }

    /**
     * Writes three classes of a module that exists nowhere else, plus one class from outside the module.
     * {@code Base} is package-private with a public field of a type from outside the allowed packages.
     * {@code Exposed} is public, extends {@code Base}, holds a private static thread and fails when it is
     * initialized. {@code Broken} is public with a field whose type is on no classpath.
     */
    private static void sampleModule(Path root) throws IOException {
        writeClass(root, BASE, type -> type
            .withFlags(0)
            .withField("info", ClassDesc.of(TestInfo.class.getName()), ClassFile.ACC_PUBLIC));
        writeClass(root, EXPOSED, type -> type
            .withSuperclass(ClassDesc.of(BASE))
            .withField("worker", ClassDesc.of(Thread.class.getName()), ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC)
            .withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
                code -> code.aconst_null().athrow()));
        writeClass(root, BROKEN, type -> type
            .withField("gone", ClassDesc.of("missing.library.Gone"), ClassFile.ACC_PUBLIC));
        writeClass(root, "other.library.Other", type -> {
        });
    }

    /**
     * Writes {@code com.comficonf.api.Thing}, {@code com.comficonf.internal.Other}, and three public classes, one in
     * {@code so.alaz.taffy.comficonf}, one in a subpackage of it and one in {@code so.alaz.taffy.other}, each with a
     * public method {@code thing()} returning {@code Thing} and a public method {@code other()} returning
     * {@code Other}.
     */
    private static void comficonfSample(Path root) throws IOException {
        writeClass(root, COMFICONF_API_TYPE, type -> {
        });
        writeClass(root, COMFICONF_OTHER_TYPE, type -> {
        });
        for (String exposed : List.of(COMFICONF_EXPOSED, COMFICONF_SUB_EXPOSED, OTHER_EXPOSED)) {
            writeClass(root, exposed, type -> type
                .withMethodBody("thing", MethodTypeDesc.of(ClassDesc.of(COMFICONF_API_TYPE)), ClassFile.ACC_PUBLIC,
                    code -> code.aconst_null().areturn())
                .withMethodBody("other", MethodTypeDesc.of(ClassDesc.of(COMFICONF_OTHER_TYPE)), ClassFile.ACC_PUBLIC,
                    code -> code.aconst_null().areturn()));
        }
    }

    private static void writeClass(Path root, String name, Consumer<ClassBuilder> body) throws IOException {
        Path file = root.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, ClassFile.of().build(ClassDesc.of(name), body));
    }

    /** A class loader over one classes directory or jar. Closing it releases a jar. */
    private static URLClassLoader loaderOf(Path output) throws IOException {
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, ApiRulesTest.class.getClassLoader());
    }

    /** Names a type from outside the allowed packages in each position of a signature. */
    abstract static class Leaky<V extends TestInfo> extends SoftAssertions implements TestReporter {

        public TestInfo field;
        TestInfo packagePrivateField;

        protected Leaky(TestInfo parameter) {
        }

        public abstract TestInfo returns();

        protected abstract void takes(TestInfo[] plain, List<TestReporter>[] generic);

        public abstract void fails() throws MockitoException;

        public abstract List<Map<String, ? extends TestInfo>> nests();

        public abstract <T extends Comparable<T>> T allowed(T value);

        abstract TestInfo packagePrivateMethod();

        private TestInfo privateMethod() {
            return null;
        }
    }

    public record LeakyRecord(TestInfo info) {
    }

    static class StaticHolder {

        static JavaPlugin plugin;
        static Logger logger;
        static ExecutorService executor;
        static Thread thread;
        static Map<String, Thread[]> threads;
        static String name;
        Logger perInstance;
    }
}
