<div align="center">

# Taffy

[![Build](https://img.shields.io/github/actions/workflow/status/alazso/taffy-api/ci.yml?branch=main&style=for-the-badge&label=build)](https://github.com/alazso/taffy-api/actions)
[![License](https://img.shields.io/badge/license-MIT-2b2d31?style=for-the-badge)](https://github.com/alazso/taffy-api/blob/main/LICENSE)
[![Support Discord](https://img.shields.io/discord/1510890328943628350?style=for-the-badge&logo=discord&logoColor=white&label=discord&color=5865F2)](https://discord.gg/m3AKQfrMS5)

</div>

<br>

Taffy is the shared library behind the so.alaz Paper plugins. Each plugin **shades and relocates** the
modules it uses, so a server owner installs one jar per plugin and nothing else.

<br>

## Using it

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven("https://repo.alaz.so/releases")
        gradlePluginPortal()
    }
}

plugins {
    id("so.alaz.settings") version "0.1.0"
}

rootProject.name = "myplugin"
```

`build.gradle.kts`:

```kotlin
plugins {
    id("so.alaz.paper-plugin")
}

dependencies {
    compileOnly(alaz.paper.api)
    implementation(alaz.taffy.metrics)
    testImplementation(alaz.junit.jupiter)
    testRuntimeOnly(alaz.junit.platform.launcher)
}

coverage {
    exclusions.addAll("**/command/**", "**/hook/**")
}

paperPlugin {
    runtimeLibrary(alaz.hikari)
    runtimeLibrary(alaz.sqlite.jdbc)
}
```

A published API module (`myplugin-api/build.gradle.kts`):

```kotlin
plugins {
    id("so.alaz.paper-api")
}

description = "MyPlugin public API"
```

Gradle itself can run on Java 17 or newer. The Java 25 toolchain the plugins compile with is downloaded when
it is not installed.

To try an unpublished build, run `./gradlew publishToMavenLocal` here and add `mavenLocal()` to both
`pluginManagement.repositories` and `dependencyResolutionManagement.repositories` in the plugin's
`settings.gradle.kts`.

<br>

## Modules

| Module | What it provides |
|---|---|
| [`taffy-platform`](taffy-platform) | The classes every other module builds on: the Folia-safe `Scheduler`, shutdown helpers, `Text` and `Messages`, item and skull builders, durations and cooldowns, rate-limited logging and a Discord webhook. Published as `so.alaz.taffy:taffy-platform`, `alaz.taffy.platform` in the catalog. |
| [`taffy-config`](taffy-config) | Strict YAML config loading: `ConfigLoader` writes and upgrades `config.yml`, `ConfigReader` reads typed values and reports every problem at once, `ConfigHolder` keeps the settings in effect when a reload fails, `YamlFolder` loads a folder of files. `alaz.taffy.config` in the catalog. |
| [`taffy-storage`](taffy-storage) | Database access over HikariCP for SQLite, MySQL, MariaDB and PostgreSQL: `Storage` runs work in transactions on its own threads and migrates the schema from the plugin's own scripts, and can take over the version table of a plugin's own migration runner once (`Baseline`). HikariCP and the drivers are not bundled: list them in `paperPlugin.runtimeLibraries`. `alaz.taffy.storage` in the catalog. |
| [`taffy-hooks`](taffy-hooks) | Integrations behind Taffy interfaces: `CustomItems` (ItemsAdder, Nexo, Oraxen, HeadDatabase), a Vault economy through `EconomyProvider.vault`, LuckPerms through `PermissionProvider`, WorldGuard through `RegionProvider`, and `PlaceholderExpansionBuilder` for PlaceholderAPI and MiniPlaceholders. The integration APIs are not bundled or published; a plugin lists each integration in `paper-plugin.yml` under `dependencies.server` with `load: BEFORE` and `required: false`. `alaz.taffy.hooks` in the catalog. |
| [`taffy-gui`](taffy-gui) | Chest menus: layouts built in code or from the plugin's own files, icons, pagination and a footer, menus opened on the viewer's thread, click safety that keeps shown items from leaking, and text prompts in chat or an anvil. `Messages` lists from `taffy-platform` give the lore. `alaz.taffy.gui` in the catalog. |
| [`taffy-metrics`](taffy-metrics) | bStats and FastStats from one set of charts through `MetricsBuilder`, with the plugin's handled errors sent from `ErrorReports` to FastStats error tracking. The SDKs come as runtime dependencies, resolve from repo.faststats.dev and are relocated by `so.alaz.paper-plugin`. A server owner turns them off in `plugins/bStats/config.yml` and `plugins/faststats/config.properties` (`enabled`, `submitMetrics`, `submitErrors`, `submitAdditionalMetrics`). FastStats data is pseudonymous. `alaz.taffy.metrics` in the catalog. |
| [`taffy-comficonf`](taffy-comficonf) | Settings edited in the ComfiConf web editor: `ComfiConfConnection` checks for the bridge and registers the plugin's settings, and `YamlFileProvider` reads, validates and writes one YAML file and runs the plugin's own reload. The API comes from the bridge: declare `compileOnly(alaz.comficonf.api)` and `testImplementation(alaz.comficonf.api)`, never shade or relocate it, and list `ComfiConf` in `paper-plugin.yml` under `dependencies.server` with `load: BEFORE`, `required: false` and `join-classpath: true`. Builds on `taffy-config`. `alaz.taffy.comficonf` in the catalog. |
| [`taffy-test`](taffy-test) | Test helpers: `ApiRules`, `CapturedLog`, `FakeItems`, `FakeFolders`, `FaultyDataSource`, `JvmDefaults`, `RaceRunner`, `SimulatedClasspath`. `alaz.taffy.test` in the catalog. |
| [`tooling/taffy-gradle`](tooling/taffy-gradle) | The Gradle plugins listed below, published as `so.alaz.taffy:taffy-gradle`. |
| [`tooling/taffy-catalog`](tooling/taffy-catalog) | The version catalog, built from [`gradle/libs.versions.toml`](gradle/libs.versions.toml). |

`tooling` is an included build, so the modules of this repository apply the same plugins they publish.
`./gradlew clean` does not reach it; `./gradlew -p tooling clean` does.

[`ROADMAP.md`](ROADMAP.md) lists what each module holds, the design defaults, and the modules still to
come.

| Plugin | Applied in | What it does |
|---|---|---|
| `so.alaz.settings` | `settings.gradle.kts` | `so.alaz.repositories` plus the `alaz` catalog at the same version as the plugin. |
| `so.alaz.repositories` | `settings.gradle.kts` | The foojay toolchain resolver and the Paper, repo.alaz.so and integration repositories. |
| `so.alaz.paper-plugin` | a plugin module | `so.alaz.coverage` plus the shaded plugin jar. |
| `so.alaz.paper-api` | a published API module | `so.alaz.java` plus sources and javadoc jars and the `library` publication. |
| `so.alaz.library` | a tested library module | `so.alaz.paper-api` plus `so.alaz.coverage`, with a per-class floor of 0.50. |
| `so.alaz.coverage` | any Java module | `so.alaz.java` plus a JaCoCo report and an instruction coverage gate on `check`. |
| `so.alaz.java` | any Java module | The Java 25 toolchain, UTF-8, the compiler lint flags, JUnit Platform, the test defaults below and the root project's `LICENSE` file, when it has one, in `META-INF`. |

**`so.alaz.coverage`** reads `coverage.minimum` (default 0.80) and leaves out the class files matched by
`coverage.exclusions`. `coverage.classMinimum` (default 0, off) is the minimum each class must hold. `check`
fails when the module has classes under the gate and no test ran.

**`so.alaz.java`** runs every test task under the `tr-TR` locale and the `Pacific/Auckland` time zone, so
code that depends on the machine's defaults fails here instead of on a server. A test task can override
them with `systemProperty`. It passes the `mockito-core` jar on a test task's classpath as a Java agent,
and runs javadoc with `-Xdoclint:all,-missing` and `--no-fonts`, so a broken comment fails the build.

**`so.alaz.paper-plugin`** adds:

- `${version}` expansion in `paper-plugin.yml`,
- one shadow jar with no classifier and the plain `jar` disabled,
- relocation of `org.bstats`, `dev.faststats` and `so.alaz.taffy` to `<group>.libs.*`,
- `RuntimeLibraries.COORDINATES` in the `<group>` package when `paperPlugin.runtimeLibraries` is not empty.

**`so.alaz.paper-api`** creates the `library` publication as `<group>:<module name>` with the `alazso` repository
(`ALAZSO_REPO_USER`, `ALAZSO_REPO_TOKEN`). The POM url is `https://github.com/alazso/<root project name>`
unless the Gradle property `alaz.repositoryUrl` is set.

<br>

## Found a bug? Have an idea?

Open a [GitHub issue](https://github.com/alazso/taffy-api/issues) or join the [Discord](https://discord.gg/m3AKQfrMS5).

<br>

<div align="center">

Released under the [MIT License](https://github.com/alazso/taffy-api/blob/main/LICENSE).

</div>
