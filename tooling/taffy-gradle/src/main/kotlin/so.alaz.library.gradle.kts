import so.alaz.taffy.gradle.CoverageExtension

plugins {
    id("so.alaz.paper-api")
    id("so.alaz.coverage")
}

extensions.configure<CoverageExtension> {
    classMinimum.convention("0.50".toBigDecimal())
}
