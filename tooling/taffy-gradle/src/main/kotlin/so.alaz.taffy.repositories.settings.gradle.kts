plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroupAndSubgroups("io.papermc")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
        exclusiveContent {
            forRepositories(
                maven("https://repo.alaz.so/releases") {
                    name = "alazso"
                    mavenContent { releasesOnly() }
                },
                maven("https://repo.alaz.so/snapshots") {
                    name = "alazsoSnapshots"
                    mavenContent { snapshotsOnly() }
                },
            )
            filter { includeGroupAndSubgroups("so.alaz") }
        }
        maven("https://repo.extendedclip.com/releases/") {
            name = "placeholderapi"
            content { includeGroup("me.clip") }
        }
        maven("https://repo.faststats.dev/releases") {
            name = "faststats"
            content { includeGroup("dev.faststats.metrics") }
        }
        maven("https://repo.codemc.io/repository/creatorfromhell/") {
            name = "vaultunlocked"
            content { includeGroup("net.milkbowl.vault") }
        }
        maven("https://repo.rosewooddev.io/repository/public/") {
            name = "playerpoints"
            content { includeGroup("org.black_ixx") }
        }
        maven("https://repo.oraxen.com/releases") {
            name = "oraxen"
            content {
                includeGroup("io.th0rgal")
                includeGroup("md.thomas.hopper")
            }
        }
        maven("https://repo.nexomc.com/releases") {
            name = "nexo"
            content {
                includeGroup("com.nexomc")
                includeGroup("com.tcoded")
                includeGroup("me.gabytm.util")
                includeGroup("team.unnamed")
            }
        }
        maven("https://maven.enginehub.org/repo/") {
            name = "enginehub"
            content {
                includeGroupAndSubgroups("com.sk89q")
                includeGroupAndSubgroups("org.enginehub")
            }
        }
        maven("https://repo.opencollab.dev/main/") {
            name = "opencollab"
            content {
                includeGroup("org.geysermc.floodgate")
                includeGroup("org.geysermc.geyser")
                includeGroup("org.geysermc.api")
                includeGroup("org.geysermc.cumulus")
                includeGroup("org.geysermc.event")
                includeGroup("org.cloudburstmc.math")
            }
        }
        maven("https://maven.fancyspaces.net/fancyinnovations/releases") {
            name = "fancyinnovations"
            content { includeGroup("de.oliver") }
        }
        maven("https://repo.thenextlvl.net/releases") {
            name = "thenextlvl"
            content { includeGroup("net.thenextlvl") }
        }
        maven("https://repo.codemc.io/repository/maven-releases/") {
            name = "packetevents"
            content { includeGroup("com.github.retrooper") }
        }
        maven("https://jitpack.io") {
            name = "jitpack"
            content {
                includeGroup("com.github.MilkBowl")
                includeGroup("com.github.Arcaniax-Development")
                includeGroup("com.github.decentsoftware-eu")
                includeGroup("com.github.HologramLib")
                includeGroup("com.github.maximjsx.EntityLib")
            }
        }
    }
}
