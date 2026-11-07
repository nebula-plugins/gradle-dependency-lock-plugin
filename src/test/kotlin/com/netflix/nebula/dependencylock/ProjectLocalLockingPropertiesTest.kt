/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.nebula.dependencylock

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.jar.JarOutputStream

/** Ordinary-CC integration checks until project ownership is migrated separately. */
internal class ProjectLocalLockingPropertiesTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `only legacy mode inherits parent locking properties`(gradle: SupportedGradleVersion) {
        fixture(root = """
            ext.set('dependencyLock.lockFile', 'inherited.lock')
            ext.set('dependencyLock.includeTransitives', true)
            ext.set('dependencyLock.override', 'fixture:widget:1.2')
        """.trimIndent())
        for (enabled in listOf(false, true)) {
            val first = run(gradle, enabled, listOf(":child:generateLock"))
            stored(first)
            val path = if (enabled) "dependencies.lock" else "inherited.lock"
            val lock = projectDir.resolve("child/build/$path").readText()
            val json = ObjectMapper().readTree(lock)["runtimeClasspath"]
            assertThat(json["fixture:widget"]["locked"].asText()).isEqualTo(if (enabled) "1.0" else "1.2")
            assertThat(json.has("fixture:leaf")).isEqualTo(!enabled)
            reused(run(gradle, enabled, listOf(":child:generateLock")))
            assertThat(projectDir.resolve("child/build/$path").readText()).isEqualTo(lock)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `local extras keep precedence over command line options`(gradle: SupportedGradleVersion) {
        fixture(local = """
            ext.set('dependencyLock.lockFile', 'local.lock')
            ext.set('dependencyLock.includeTransitives', false)
            ext.set('dependencyLock.ignore', false)
            ext.set('dependencyLock.override', 'fixture:widget:1.1')
        """.trimIndent())
        val arguments = listOf(
            ":child:generateLock", "-PdependencyLock.lockFile=cli.lock",
            "-PdependencyLock.includeTransitives=true", "-PdependencyLock.ignore=true",
            "-PdependencyLock.override=fixture:widget:1.2"
        )
        for (enabled in listOf(false, true)) {
            val first = run(gradle, enabled, arguments)
            stored(first)
            val lock = projectDir.resolve("child/build/local.lock").readText()
            val json = ObjectMapper().readTree(lock)["runtimeClasspath"]
            assertThat(json["fixture:widget"]["locked"].asText()).isEqualTo("1.1")
            assertThat(json.has("fixture:leaf")).isFalse()
            assertThat(projectDir.resolve("child/build/cli.lock")).doesNotExist()
            reused(run(gradle, enabled, arguments))
            assertThat(projectDir.resolve("child/build/local.lock").readText()).isEqualTo(lock)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `verifier policy uses only explicit local settings in enabled mode`(gradle: SupportedGradleVersion) {
        fixture(
            root = "ext.set('dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild', false)",
            dependency = "fixture:missing:1.0"
        )
        val arguments = listOf(":child:dependencies", "--configuration", "runtimeClasspath")
        val legacy = run(gradle, false, arguments)
        assertThat(legacy.output).contains("BUILD SUCCESSFUL", "fixture:missing:1.0")

        val enabled = run(gradle, true, arguments, expectFailure = true)
        assertThat(enabled.output).contains("BUILD FAILED", "fixture:missing:1.0")

        val explicit = arguments + "-PdependencyResolutionVerifier.unresolvedDependenciesFailTheBuild=false"
        stored(run(gradle, true, explicit))
        reused(run(gradle, true, explicit))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `resolution and verification share root relative file and inline overrides`(gradle: SupportedGradleVersion) {
        fixture()
        projectDir.resolve("child/dependencies.lock").writeText(
            """{"runtimeClasspath":{"fixture:widget":{"locked":"1.0"}}}"""
        )
        val overrides = projectDir.resolve("overrides.json")
        overrides.writeText("""{"fixture:widget":"1.1"}""")
        // A different child-relative file catches verifier/resolution disagreement.
        projectDir.resolve("child/overrides.json").writeText("""{"fixture:widget":"1.2"}""")
        val arguments = listOf(
            ":child:dependencies", "--configuration", "runtimeClasspath",
            "-PdependencyLock.overrideFile=overrides.json"
        )

        val first = run(gradle, true, arguments)
        stored(first)
        resolved(first, "1.1")
        val second = run(gradle, true, arguments)
        reused(second)
        resolved(second, "1.1")

        overrides.writeText("""{"fixture:widget":{"locked":"1.2"}}""")
        val changed = run(gradle, true, arguments)
        stored(changed)
        resolved(changed, "1.2")
        reused(run(gradle, true, arguments))

        val inline = arguments + "-PdependencyLock.override=fixture:widget:1.1"
        val inlineResult = run(gradle, true, inline)
        stored(inlineResult)
        resolved(inlineResult, "1.1")
        reused(run(gradle, true, inline))

        assertThat(overrides.delete()).isTrue()
        val missing = run(gradle, true, inline, expectFailure = true)
        assertThat(missing.output).contains("overrides.json is unreadable or invalid json")

        val withoutFile = inline.filterNot { it.startsWith("-PdependencyLock.overrideFile=") }
        val onlyInline = run(gradle, true, withoutFile)
        stored(onlyInline)
        resolved(onlyInline, "1.1")
        reused(run(gradle, true, withoutFile))

        overrides.writeText("""{"fixture:widget":"1.2"}""")
        val restored = run(gradle, true, arguments)
        stored(restored)
        resolved(restored, "1.2")
        reused(run(gradle, true, arguments))
    }

    private fun fixture(root: String = "", local: String = "", dependency: String = "fixture:widget:1.0") {
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'local-properties'\ninclude 'child'\n")
        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        projectDir.resolve("build.gradle").writeText(root)
        for (version in listOf("1.0", "1.1", "1.2")) {
            module("leaf", version)
            module("widget", version, "<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>$version</version></dependency></dependencies>")
        }
        projectDir.resolve("child").mkdirs()
        projectDir.resolve("child/build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.netflix.nebula.dependency-lock' apply false
            }
            $local
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
            dependencies { implementation '$dependency' }
            """.trimIndent()
        )
    }

    private fun module(name: String, version: String, dependencies: String = "") {
        val directory = projectDir.resolve("repo/fixture/$name/$version")
        directory.mkdirs()
        directory.resolve("$name-$version.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>$version</version>$dependencies</project>"
        )
        JarOutputStream(directory.resolve("$name-$version.jar").outputStream()).use { }
    }

    private fun run(
        gradle: SupportedGradleVersion,
        enabled: Boolean,
        arguments: List<String>,
        expectFailure: Boolean = false
    ): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(arguments + listOf(
                "--configuration-cache", "--configuration-cache-problems=fail", "--no-watch-fs", "--stacktrace", "--console=plain",
                "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled",
                "-Dnebula.features.coreLockingSupport=false", "-Dnebula.features.coreAlignmentSupport=true"
            ))
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored").doesNotContain("Reusing configuration cache")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }

    private fun resolved(result: BuildResult, version: String) {
        assertThat(result.output).contains("fixture:widget:1.0 -> $version")
        assertThat(result.task(":child:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}
