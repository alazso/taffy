plugins {
    id("so.alaz.taffy.java")
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set(
                    providers.gradleProperty("alaz.repositoryUrl")
                        .orElse("https://github.com/alazso/" + rootProject.name),
                )
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "alazso"
            setUrl(
                provider {
                    if (project.version.toString().endsWith("-SNAPSHOT")) "https://repo.alaz.so/snapshots"
                    else "https://repo.alaz.so/releases"
                },
            )
            credentials {
                username = System.getenv("ALAZSO_REPO_USER")
                password = System.getenv("ALAZSO_REPO_TOKEN")
            }
        }
    }
}
