import so.alaz.taffy.gradle.CoverageExtension

plugins {
    id("so.alaz.taffy.paper-api")
    id("so.alaz.taffy.coverage")
}

extensions.configure<CoverageExtension> {
    classMinimum.convention("0.50".toBigDecimal())
}
