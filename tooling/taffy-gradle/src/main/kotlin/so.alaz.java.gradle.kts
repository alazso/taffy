import java.util.concurrent.Callable
import so.alaz.taffy.gradle.MockitoAgent

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-processing,-serial")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        addBooleanOption("-no-fonts", true)
        encoding = "UTF-8"
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // Mockito's inline mock maker attaches itself to the running JVM unless its jar is passed as a Java agent.
    // The classpath is read lazily, so the one a build sets after this action is the one searched.
    val task = this
    jvmArgumentProviders.add(MockitoAgent(files(Callable { task.classpath })))
    // Tests run under Turkish case mapping and a zone ahead of UTC: code that reads the default locale, or that
    // reads an instant late in the UTC day in the default zone, gives another result than under English and UTC.
    systemProperty("user.language", "tr")
    systemProperty("user.country", "TR")
    systemProperty("user.timezone", "Pacific/Auckland")
}

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}
