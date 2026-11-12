# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Added
- `so.alaz.settings` settings plugin: applies the Java toolchain resolver, declares every repository
  the so.alaz plugins resolve from, and exposes the shared version catalog as `alaz`.
- `so.alaz.paper-plugin` convention plugin: Java 25, one relocated shadow jar, `paper-plugin.yml`
  version expansion, the coverage gate, and the generated `RuntimeLibraries` class.
- `so.alaz.paper-api` convention plugin: sources and javadoc jars and the `library` publication to
  repo.alaz.so under the project group.
- `so.alaz.taffy:taffy-catalog` version catalog with the dependency versions of the so.alaz stack.
