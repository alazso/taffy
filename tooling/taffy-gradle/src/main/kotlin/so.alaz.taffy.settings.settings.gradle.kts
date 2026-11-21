import so.alaz.taffy.gradle.MinimumGradle
import so.alaz.taffy.gradle.TaffyBuild

MinimumGradle.require("so.alaz.taffy.settings")

apply(plugin = "so.alaz.taffy.repositories")

dependencyResolutionManagement {
    versionCatalogs {
        create("alaz") {
            from("so.alaz.taffy:taffy-catalog:" + TaffyBuild.VERSION)
        }
    }
}
