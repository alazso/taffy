plugins {
    id("so.alaz.taffy.library")
}

description = "Taffy storage: JDBC over HikariCP for SQLite, MySQL, MariaDB and PostgreSQL"

dependencies {
    api(project(":taffy-platform"))
    compileOnly(libs.paper.api)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.hikari)

    testImplementation(project(":taffy-test"))
    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.hikari)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.mysql.connector)
    testRuntimeOnly(libs.mariadb.jdbc)
    testRuntimeOnly(libs.postgresql.jdbc)
}

// The TLS suite runs in a JVM of its own, because it sets the JVM's default trust store, which is read once.
val sslTest: SourceSet = sourceSets.create("sslTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[sslTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[sslTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val sslTestTask = tasks.register<Test>("sslTest") {
    description = "Connects to MySQL, MariaDB and PostgreSQL over TLS in each SSL mode."
    group = "verification"
    testClassesDirs = sslTest.output.classesDirs
    classpath = sslTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(sslTestTask)
}
