plugins {
    base
    `version-catalog`
    `maven-publish`
}

description = "Version catalog shared by the so.alaz plugins"

catalog {
    versionCatalog {
        from(files(rootProject.layout.projectDirectory.file("../gradle/libs.versions.toml")))
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
