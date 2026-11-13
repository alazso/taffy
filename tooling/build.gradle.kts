import java.util.Properties

plugins {
    base
}

val taffy = Properties().apply {
    load(providers.fileContents(layout.projectDirectory.file("../gradle.properties")).asText.get().reader())
}

allprojects {
    group = taffy.getProperty("group")
    version = taffy.getProperty("version")
}

subprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "alazso"
                    val isRelease = !project.version.toString().endsWith("-SNAPSHOT")
                    url = uri(
                        if (isRelease) "https://repo.alaz.so/releases"
                        else "https://repo.alaz.so/snapshots"
                    )
                    credentials {
                        username = System.getenv("ALAZSO_REPO_USER")
                        password = System.getenv("ALAZSO_REPO_TOKEN")
                    }
                }
                maven {
                    name = "test"
                    url = uri(rootProject.layout.buildDirectory.dir("test-repo"))
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set(taffy.getProperty("alaz.repositoryUrl"))
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
