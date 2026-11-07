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

/** Covers opt-in plumbing only; multi-project isolation is still under development. */
internal class DependencyLockingFeatureFlagsTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `only a true system property enables the experimental mode`(gradle: SupportedGradleVersion) {
        fixture()
        listOf(null, "false", "true", "TRUE", "1", "", null).forEach { value ->
            val result = run(gradle, "help", value)
            assertMode(result, value?.equals("true", ignoreCase = true) == true)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `toggling the mode invalidates the cache without changing generated locks`(gradle: SupportedGradleVersion) {
        fixture()
        var originalLock: String? = null
        listOf(null, "true", "false").forEach { value ->
            val first = run(gradle, "generateLock", value)
            assertThat(first.output).contains("Configuration cache entry stored")
                .doesNotContain("Reusing configuration cache")
            assertThat(first.task(":generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertMode(first, value == "true")

            val lock = projectDir.resolve("build/dependencies.lock").readText()
            val json = ObjectMapper().readTree(lock)
            assertThat(json["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")
            if (originalLock == null) originalLock = lock
            assertThat(lock).isEqualTo(originalLock)

            val second = run(gradle, "generateLock", value)
            assertThat(second.output).contains("Reusing configuration cache", "Configuration cache entry reused")
            assertThat(second.task(":generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(projectDir.resolve("build/dependencies.lock").readText()).isEqualTo(lock)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `enabled single project generation still works with strict isolation`(gradle: SupportedGradleVersion) {
        fixture()
        projectDir.resolve("gradle.properties").appendText(
            "systemProp.nebula.dependency-lock.isolatedProjectsSupport=true\n"
        )
        val first = run(gradle, "generateLock", isolated = true)
        assertThat(first.output).contains("Configuration cache entry stored")
        assertMode(first, true)
        assertThat(first.task(":generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val lock = projectDir.resolve("build/dependencies.lock").readText()
        assertThat(ObjectMapper().readTree(lock)["runtimeClasspath"]["fixture:widget"]["locked"].asText())
            .isEqualTo("1.0")

        val second = run(gradle, "generateLock", isolated = true)
        assertThat(second.output).contains("Reusing configuration cache", "Configuration cache entry reused")
        assertThat(projectDir.resolve("build/dependencies.lock").readText()).isEqualTo(lock)
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `multi project rollout and rollback preserve locks with default filesystem watching`(gradle: SupportedGradleVersion) {
        fixture()
        projectDir.resolve("settings.gradle").appendText("include ':libraries:lib', ':tools:lib'\n")
        for (path in listOf("libraries/lib", "tools/lib")) {
            projectDir.resolve("$path/build.gradle").apply {
                parentFile.mkdirs()
                writeText("""
                    plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
                    repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
                    dependencies { implementation 'fixture:widget:1.0' }
                """.trimIndent())
            }
        }
        var reference: Map<String, String>? = null
        for ((value, isolated) in listOf("false" to false, "true" to false, "true" to true, "false" to false)) {
            fun execute() = run(gradle, "saveLock", value, isolated,
                additionalTasks = listOf("generateLock", "--parallel"), defaultWatching = true)
            execute()
            execute() // Saved-file presence may invalidate the entry created before the first save.
            val cached = execute()
            assertThat(cached.output).contains("Reusing configuration cache", "Configuration cache entry reused")
            val saved = listOf("dependencies.lock", "libraries/lib/dependencies.lock", "tools/lib/dependencies.lock")
                .associateWith { projectDir.resolve(it).readText() }
            if (reference == null) reference = saved
            assertThat(saved).isEqualTo(reference)
            for (path in saved.keys) {
                assertThat(ObjectMapper().readTree(saved.getValue(path))["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")
            }
        }
    }

    private fun fixture() {
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'flag-fixture'\n")
        projectDir.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n"
        )
        val module = projectDir.resolve("repo/fixture/widget/1.0")
        module.mkdirs()
        module.resolve("widget-1.0.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>widget</artifactId><version>1.0</version></project>"""
        )
        JarOutputStream(module.resolve("widget-1.0.jar").outputStream()).use { }
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.netflix.nebula.dependency-lock'
            }
            repositories { maven { url = uri('repo') } }
            dependencies { implementation 'fixture:widget:1.0' }
            """.trimIndent()
        )
    }

    private fun run(
        gradle: SupportedGradleVersion,
        task: String,
        value: String? = null,
        isolated: Boolean = false,
        additionalTasks: List<String> = emptyList(),
        defaultWatching: Boolean = false
    ): BuildResult {
        val version = when (gradle) {
            SupportedGradleVersion.GRADLE_9_2 -> "9.2.1"
            SupportedGradleVersion.CURRENT -> GradleVersion.current().version
        }
        val arguments = (listOf(task) + additionalTasks + listOf(
            "--configuration-cache", "--configuration-cache-problems=fail",
            "--console=plain", "--stacktrace", "--info",
            "-Dnebula.features.coreLockingSupport=false"
        )).toMutableList()
        if (!defaultWatching) arguments.add("--no-watch-fs")
        if (value != null) arguments.add("-Dnebula.dependency-lock.isolatedProjectsSupport=$value")
        if (isolated) arguments.add(
            if (GradleVersion.version(version) >= GradleVersion.version("9.7")) "--isolated-projects"
            else "-Dorg.gradle.unsafe.isolated-projects=true"
        )
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(version).withArguments(arguments).build()
    }

    private fun assertMode(result: BuildResult, enabled: Boolean) {
        val message = "isolatedProjectsSupport feature enabled (experimental)"
        if (enabled) assertThat(result.output).contains(message)
        else assertThat(result.output).doesNotContain(message)
    }
}
