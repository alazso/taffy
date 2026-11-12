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
    implementation(alaz.bstats.bukkit)
    implementation(alaz.faststats.bukkit)
    testImplementation(alaz.junit.jupiter)
    testRuntimeOnly(alaz.junit.platform.launcher)
}

paperPlugin {
    coverageExclusions.addAll("**/command/**", "**/hook/**")
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

<br>

## Modules

| Module | What it provides |
|---|---|
| `taffy-gradle` | The `so.alaz.settings`, `so.alaz.paper-plugin` and `so.alaz.paper-api` Gradle plugins. |
| `taffy-catalog` | The version catalog, built from [`gradle/libs.versions.toml`](gradle/libs.versions.toml). |

**`so.alaz.settings`** applies the foojay toolchain resolver, declares the Paper, repo.alaz.so and
integration repositories, and creates the `alaz` catalog at the same version as the plugin.

**`so.alaz.paper-plugin`** applies `java-library`, `jacoco` and Shadow, then configures:

- the Java 25 toolchain, UTF-8 and the compiler lint flags,
- JUnit Platform tests with a JaCoCo report and an instruction coverage gate on `check`
  (`paperPlugin.coverageMinimum`, default 0.80, minus `paperPlugin.coverageExclusions`),
- `${version}` expansion in `paper-plugin.yml` and `LICENSE` in `META-INF`,
- one shadow jar with no classifier and the plain `jar` disabled,
- relocation of `org.bstats`, `dev.faststats` and `so.alaz.taffy` to `<group>.libs.*`,
- `RuntimeLibraries.COORDINATES` in the `<group>` package when `paperPlugin.runtimeLibraries` is not empty.

**`so.alaz.paper-api`** applies `java-library` and `maven-publish`, adds sources and javadoc jars, and
creates the `library` publication as `<group>:<module name>` with the `alazso` repository
(`ALAZSO_REPO_USER`, `ALAZSO_REPO_TOKEN`).

<br>

## Found a bug? Have an idea?

Open a [GitHub issue](https://github.com/alazso/taffy-api/issues) or join the [Discord](https://discord.gg/m3AKQfrMS5).

<br>

<div align="center">

Released under the [MIT License](https://github.com/alazso/taffy-api/blob/main/LICENSE).

</div>
