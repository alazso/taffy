import so.alaz.taffy.gradle.TaffyBuild

plugins {
    id("so.alaz.taffy.repositories")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("alaz") {
            from("so.alaz.taffy:taffy-catalog:" + TaffyBuild.VERSION)
        }
    }
}
