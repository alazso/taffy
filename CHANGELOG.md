# Changelog

All notable changes to Taffy are documented here. This project follows
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Added
- `so.alaz.settings` settings plugin: applies `so.alaz.repositories` and exposes the shared version
  catalog as `alaz`.
- `so.alaz.repositories` settings plugin: applies the Java toolchain resolver and declares every
  repository the so.alaz plugins resolve from.
- `so.alaz.paper-plugin` convention plugin: one relocated shadow jar, `paper-plugin.yml` version
  expansion, the coverage gate, and the generated `RuntimeLibraries` class.
- `so.alaz.paper-api` convention plugin: sources and javadoc jars and the `library` publication to
  repo.alaz.so under the project group.
- `so.alaz.library` convention plugin: `so.alaz.paper-api` with tests and the coverage gate, for a
  published library module.
- `so.alaz.coverage` and `so.alaz.java` convention plugins: the coverage gate (`coverage.minimum`,
  `coverage.exclusions`) and the Java 25 toolchain, compiler flags and JUnit Platform on their own.
- `so.alaz.taffy:taffy-catalog` version catalog with the dependency versions of the so.alaz stack.
