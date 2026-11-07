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

internal class IsolatedProjectsGenerationTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `save notices new global conflicts even after it was up to date`(gradle: SupportedGradleVersion) {
        fixture()
        // Root does not apply the plugin: saving must work without foreign task/extension access.
        write("child/build.gradle", "plugins { id 'com.netflix.nebula.dependency-lock' }")
        write("child/build/dependencies.lock", "{}")
        for ((globalName, options) in listOf(
            "global.lock" to emptyList(),
            "custom-global.lock" to listOf("-PdependencyLock.globalLockFile=custom-global.lock"),
            "custom-global.lock" to listOf("-PdependencyLock.globalLockFile=custom-global.lock", "-PdependencyLock.disableGlobalLock=true")
        )) {
            val tasks = listOf(":child:saveLock") + options
            val first = run(gradle, tasks)
            if (options.isEmpty()) stored(first)
            val steady = run(gradle, tasks)
            assertThat(steady.task(":child:saveLock")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
            val cached = run(gradle, tasks)
            reused(cached)
            assertThat(cached.task(":child:saveLock")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

            val conflict = projectDir.resolve(globalName).apply { writeText("global marker") }
            repeat(2) {
                val failed = run(gradle, tasks, expectFailure = true)
                assertThat(failed.task(":child:saveLock")?.outcome).isEqualTo(TaskOutcome.FAILED)
                assertThat(failed.output).contains("Global lock consumption", globalName,
                    "nebula.dependency-lock.isolatedProjectsSupport=true", "Use normal per-project locking")
                    .doesNotContain("run deleteGlobalLock task", "Configuration cache problems found")
                if (it == 1) reused(failed)
                assertThat(projectDir.resolve("child/dependencies.lock").readText()).isEqualTo("{}")
                assertThat(conflict.readText()).isEqualTo("global marker")
            }
            assertThat(conflict.delete()).isTrue()
            run(gradle, tasks)
            reused(run(gradle, tasks))
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `child generation keeps options metadata bytes and explicit save ordering`(gradle: SupportedGradleVersion) {
        fixture()
        module("leaf", "1.0")
        module("widget", "1.0", "<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency></dependencies>")
        write("child/build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
            repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
            dependencies { implementation 'fixture:widget:1.0' }
            dependencyLock {
                lockFile = 'custom.lock'
                globalLockFile = 'extension-global.lock'
                configurationNames = ['runtimeClasspath']
                includeTransitives = true
            }
        """.trimIndent())
        val tasks = listOf(":child:saveLock", ":child:generateLock", "-PdependencyLock.override=fixture:widget:1.0")
        val first = run(gradle, tasks)
        stored(first)
        assertThat(first.tasks.map { it.path }).containsSubsequence(":child:generateLock", ":child:saveLock")
        assertThat(first.task(":child:generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val generated = projectDir.resolve("child/build/custom.lock").readText()
        val saved = projectDir.resolve("child/custom.lock")
        assertThat(saved.readText()).isEqualTo(generated)
        val lock = ObjectMapper().readTree(saved)
        assertThat(lock.fieldNames().asSequence().toList()).containsExactly("runtimeClasspath")
        assertThat(lock["runtimeClasspath"]["fixture:widget"]["viaOverride"].asText()).isEqualTo("1.0")
        assertThat(lock["runtimeClasspath"]["fixture:leaf"]["locked"].asText()).isEqualTo("1.0")
        assertThat(lock["runtimeClasspath"]["fixture:leaf"]["transitive"][0].asText()).isEqualTo("fixture:widget")
        run(gradle, tasks)
        val cached = run(gradle, tasks)
        reused(cached)
        assertThat(cached.task(":child:generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(saved.readText()).isEqualTo(generated)
        assertThat(projectDir.resolve("child/dependencies.lock")).doesNotExist()
        assertThat(projectDir.resolve("build/dependencies.lock")).doesNotExist()

        projectDir.resolve("extension-global.lock").writeText("global marker")
        val failed = run(gradle, listOf(":child:saveLock"), expectFailure = true)
        assertThat(failed.output).contains("Global lock consumption", "extension-global.lock")
        assertThat(failed.task(":child:generateLock")).isNull()
        assertThat(saved.readText()).isEqualTo(generated)
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'generation'\ninclude ':child'\n")
        write("build.gradle", "")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
    }

    private fun module(name: String, version: String, dependencies: String = "") {
        val base = "repo/fixture/$name/$version/$name-$version"
        write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>$version</version>$dependencies</project>")
        JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
    }

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(gradle: SupportedGradleVersion, tasks: List<String>, expectFailure: Boolean = false): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true"
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + listOf(isolation, "--configuration-cache", "--configuration-cache-problems=fail",
                "--no-watch-fs", "--stacktrace", "--console=plain", "-Dnebula.features.coreLockingSupport=false",
                "-Dnebula.dependency-lock.isolatedProjectsSupport=true"))
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }
}
