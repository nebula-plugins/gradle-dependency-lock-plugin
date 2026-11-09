/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package com.netflix.nebula.dependencylock

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

internal class IsolatedProjectsCompositeBuildTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `included build task selection updates only its locks and substitution remains a project dependency`(gradle: SupportedGradleVersion) {
        val fixture = fixture()
        val includedWrites = listOf(":first-build:lib:generateLock", ":first-build:lib:saveLock",
            ":second-build:lib:generateLock", ":second-build:lib:saveLock")
        fixture.run(gradle, includedWrites)
        val firstLock = "first-build/lib/first.lock"
        val secondLock = "second-build/lib/second.lock"
        val secondBytes = projectDir.resolve(secondLock).readText()
        assertThat(fixture.version(firstLock, "widget")).isEqualTo("1.0")
        fixture.run(gradle, listOf("generateLock", "saveLock"))
        val rootLock = fixture.lock("dependencies.lock")["runtimeClasspath"]
        for (coordinate in listOf("fixture.first:lib", "fixture.second:lib")) {
            assertThat(rootLock[coordinate]["project"].asBoolean()).isTrue()
            assertThat(rootLock[coordinate].has("locked")).isFalse()
        }

        fixture.metadata("widget", listOf("1.0", "1.1"))
        fixture.metadata("other", listOf("1.0", "1.1"))
        // Main-build requests must not unlock or write any included-build project.
        val rootWrites = listOf("generateLock", "saveLock")
        val rootResult = fixture.run(gradle, rootWrites)
        assertThat(rootResult.tasks.map { it.path }).doesNotContain(
            ":first-build:lib:generateLock", ":second-build:lib:generateLock")
        assertThat(fixture.version(firstLock, "widget")).isEqualTo("1.0")
        assertThat(projectDir.resolve(secondLock).readText()).isEqualTo(secondBytes)

        val update = listOf(":first-build:lib:uL", ":first-build:lib:diffL", ":first-build:lib:sL",
            "-PdependencyLock.updateDependencies=fixture:widget")
        val updated = fixture.run(gradle, update)
        assertThat(fixture.version(firstLock, "widget")).describedAs(updated.output).isEqualTo("1.1")
        assertThat(fixture.version(firstLock, "other")).isEqualTo("1.0")
        assertThat(projectDir.resolve(secondLock).readText()).isEqualTo(secondBytes)
        fixture.run(gradle, update)
        fixture.reused(fixture.run(gradle, update))
        fixture.metadata("widget", listOf("1.0", "1.1", "1.2"))
        fixture.run(gradle, update)
        assertThat(fixture.version(firstLock, "widget")).isEqualTo("1.2")
        assertThat(projectDir.resolve(secondLock).readText()).isEqualTo(secondBytes)

        // Exact regeneration must discard the second build's existing constraints.
        val regenerate = listOf(":second-build:lib:generateLock", ":second-build:lib:saveLock")
        fixture.run(gradle, regenerate)
        assertThat(fixture.version(secondLock, "widget")).isEqualTo("1.2")
        assertThat(fixture.version(secondLock, "other")).isEqualTo("1.1")
        fixture.run(gradle, regenerate)
        fixture.reused(fixture.run(gradle, regenerate))

        val build = listOf("jar")
        fixture.run(gradle, build)
        val cached = fixture.run(gradle, build)
        fixture.reused(cached)
        for (path in listOf(":verifyDependencyResolution", ":first-build:lib:verifyDependencyResolution",
            ":second-build:lib:verifyDependencyResolution")) {
            assertThat(cached.task(path)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }
        assertThat(projectDir.resolve("build/classes/java/main/fixture/Consumer.class")).exists()
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `duplicate project paths in included builds retain independent verifier policies on cache reuse`(gradle: SupportedGradleVersion) {
        val fixture = fixture()
        fixture.write("first-build/lib/build.gradle", localBuild(fixture, "first", "fixture:first-missing") + """

            dependencyResolutionVerifierExtension {
                shouldFailTheBuild = false
                missingVersionsMessageAddition = 'first build guidance'
            }
        """.trimIndent())
        val report = listOf(":first-build:lib:dependencies", "--configuration", "runtimeClasspath",
            ":second-build:lib:dependencies", "--configuration", "runtimeClasspath")
        fixture.run(gradle, report)
        val cached = fixture.run(gradle, report)
        fixture.reused(cached)
        assertThat(cached.output).contains("first build guidance", "Failed to resolve 'fixture:first-missing'", "BUILD SUCCESSFUL")
        for (name in listOf("first", "second")) {
            assertThat(cached.task(":$name-build:lib:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }

        fixture.write("second-build/lib/build.gradle", localBuild(fixture, "second", "fixture:second-missing") + """

            dependencyResolutionVerifierExtension.missingVersionsMessageAddition = 'second build guidance'
        """.trimIndent())
        fixture.run(gradle, report, expectFailure = true)
        val failed = fixture.run(gradle, report, expectFailure = true)
        fixture.reused(failed)
        assertThat(failed.output).contains("second build guidance", "Failed to resolve 'fixture:second-missing'", "BUILD FAILED")

        fixture.write("second-build/lib/build.gradle", localBuild(fixture, "second"))
        fixture.run(gradle, report)
        val repaired = fixture.run(gradle, report)
        fixture.reused(repaired)
        assertThat(repaired.output).contains("first build guidance", "BUILD SUCCESSFUL")
            .doesNotContain("second build guidance", "second-missing")
    }

    private fun fixture(): IsolatedProjectsIntegrationFixture {
        val fixture = IsolatedProjectsIntegrationFixture(projectDir)
        fixture.write("settings.gradle", """
            rootProject.name = 'consumer'
            includeBuild('first-build')
            includeBuild('second-build')
        """)
        fixture.write("build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
            repositories { maven { url = uri('${fixture.repository}') } }
            dependencyLock { configurationNames = ['runtimeClasspath']; includeTransitives = true }
            dependencies { implementation 'fixture.first:lib:1.0'; implementation 'fixture.second:lib:1.0' }
        """)
        fixture.write("src/main/java/fixture/Consumer.java", """
            package fixture;
            public class Consumer { String message = fixture.first.Library.message() + fixture.second.Library.message(); }
        """)
        for (name in listOf("first", "second")) {
            fixture.write("$name-build/settings.gradle", "rootProject.name = '$name-build'\ninclude 'lib'")
            fixture.write("$name-build/build.gradle", "")
            fixture.write("$name-build/gradle.properties", projectDir.resolve("gradle.properties").readText())
            fixture.write("$name-build/lib/build.gradle", localBuild(fixture, name))
            fixture.write("$name-build/lib/src/main/java/fixture/$name/Library.java", """
                package fixture.$name;
                public class Library { public static String message() { return "$name"; } }
            """)
        }
        return fixture
    }

    private fun localBuild(fixture: IsolatedProjectsIntegrationFixture, name: String,
        dependency: String = "fixture:widget:1.+") = """
        plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
        group = 'fixture.$name'
        version = '1.0'
        repositories { maven { url = uri('${fixture.repository}') } }
        dependencyLock { lockFile = '$name.lock'; configurationNames = ['runtimeClasspath']; includeTransitives = true }
        dependencies { api '$dependency'; implementation 'fixture:other:1.+' }
    """.trimIndent()
}
