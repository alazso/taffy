import so.alaz.taffy.gradle.TaffyBuild

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")   { name = "papermc" }
        maven("https://repo.alaz.so/releases")                      { name = "alazso" }
        if (TaffyBuild.VERSION.endsWith("-SNAPSHOT")) {
            maven("https://repo.alaz.so/snapshots")                 { name = "alazsoSnapshots" }
        }
        maven("https://repo.extendedclip.com/releases/")            { name = "placeholderapi" }
        maven("https://repo.faststats.dev/releases")                { name = "faststats" }
        maven("https://jitpack.io")                                 { name = "jitpack" }
        maven("https://repo.codemc.io/repository/creatorfromhell/") { name = "vaultunlocked" }
        maven("https://repo.rosewooddev.io/repository/public/")     { name = "playerpoints" }
        maven("https://repo.oraxen.com/releases")                   { name = "oraxen" }
        maven("https://repo.nexomc.com/releases")                   { name = "nexo" }
        maven("https://maven.enginehub.org/repo/")                  { name = "enginehub" }
    }
    versionCatalogs {
        create("alaz") {
            from("so.alaz.taffy:taffy-catalog:" + TaffyBuild.VERSION)
        }
    }
}
