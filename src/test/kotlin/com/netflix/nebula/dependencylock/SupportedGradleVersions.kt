package com.netflix.nebula.dependencylock

import nebula.test.dsl.Gradle

enum class SupportedGradleVersion(val version: Gradle) {
    GRADLE_9_2(Gradle.ofVersion("9.2.1")),
    CURRENT(Gradle.current())
}
