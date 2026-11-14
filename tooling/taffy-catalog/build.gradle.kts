import java.util.Properties

plugins {
    base
    `version-catalog`
    `maven-publish`
}

description = "Version catalog shared by the so.alaz plugins"

val taffyModules = Properties()
    .apply {
        load(providers.fileContents(rootProject.layout.projectDirectory.file("../gradle.properties")).asText.get().reader())
    }
    .getProperty("taffy.modules")
    .split(",")
    .map { it.trim() }

catalog {
    versionCatalog {
        from(files(rootProject.layout.projectDirectory.file("../gradle/libs.versions.toml")))
        version("taffy", project.version.toString())
        taffyModules.forEach { module ->
            library(module, project.group.toString(), module).versionRef("taffy")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("catalog") {
            from(components["versionCatalog"])
            pom {
                name.set("Taffy Catalog")
                description.set(project.description)
            }
        }
    }
}
