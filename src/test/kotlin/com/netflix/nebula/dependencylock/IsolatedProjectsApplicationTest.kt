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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

internal class IsolatedProjectsApplicationTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `child only application leaves root ownership unchanged only in enabled mode`(gradle: SupportedGradleVersion) {
        fixture(listOf("child"))
        projectDir.resolve("build.gradle").writeText("""
            tasks.register('rootState') {
                inputs.property('verifier', project.extensions.findByName('dependencyResolutionVerifierExtension') != null)
                inputs.property('commitExtension', project.extensions.findByName('commitDependencyLock') != null)
                inputs.property('commitTask', project.tasks.names.contains('commitLock'))
                doLast { inputs.properties.sort().each { key, value -> println("${'$'}key=${'$'}value") } }
            }
        """.trimIndent())

        for ((enabled, isolated) in listOf(false to false, true to false, true to true)) {
            val arguments = listOf("rootState", ":child:help")
            val first = run(gradle, arguments, enabled, isolated)
            stored(first)
            for (property in listOf("verifier", "commitExtension", "commitTask")) {
                assertThat(first.output).contains("$property=${!enabled}")
            }
            reused(run(gradle, arguments, enabled, isolated))
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `local application in flat and nested projects remains lazy and reuses`(gradle: SupportedGradleVersion) {
        val children = listOf("app", "lib", "libraries:lib", "tools:lib")
        fixture(children, rootPlugin = true)
        val rootBuild = projectDir.resolve("build.gradle")
        rootBuild.appendText("""

            dependencyResolutionVerifierExtension.shouldFailTheBuild = false
            tasks.register('rootSettings') {
                inputs.property('rootPolicy', project.extensions.getByName('dependencyResolutionVerifierExtension').shouldFailTheBuild)
                inputs.property('commitTask', project.tasks.names.contains('commitLock'))
                doLast { inputs.properties.each { key, value -> println("${'$'}key=${'$'}value") } }
            }
        """.trimIndent())
        children.forEach { path ->
            projectDir.resolve("${path.replace(':', '/')}/build.gradle").appendText("""

                tasks.register('localSettings') {
                    inputs.property('localPolicy', project.extensions.getByName('dependencyResolutionVerifierExtension').shouldFailTheBuild)
                    inputs.property('localCommit', project.extensions.findByName('commitDependencyLock') != null)
                    doLast { inputs.properties.each { key, value -> println("${'$'}key=${'$'}value") } }
                }
            """.trimIndent())
        }
        val arguments = listOf("rootSettings") + children.map { ":$it:localSettings" }
        val first = run(gradle, arguments)
        stored(first)
        assertThat(first.output).contains("rootPolicy=false", "commitTask=true", "localPolicy=true", "localCommit=false")
        reused(run(gradle, arguments))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `excluded task requests fail explicitly without modifying locks`(gradle: SupportedGradleVersion) {
        fixture(emptyList(), rootPlugin = true)
        val legacy = projectDir.resolve("dependencies.lock").apply { writeText("legacy marker") }
        val global = projectDir.resolve("global.lock").apply { writeText("global marker") }
        val core = projectDir.resolve("gradle.lockfile").apply { writeText("core marker") }
        val tasks = listOf(
            "generateGlobalLock", "updateGlobalLock", "saveGlobalLock", "deleteGlobalLock",
            "migrateToCoreLocks", "migrateLockeDepsToCoreLocks"
        )
        for (task in tasks) {
            val first = run(gradle, listOf(task), expectFailure = true)
            assertThat(first.output).contains("$task is not supported when", "nebula.dependency-lock.isolatedProjectsSupport=true")
                .doesNotContain("Configuration cache problems found", "cannot access", "cache entry discarded")
            assertThat(first.output).contains("Configuration cache entry stored")
            val second = run(gradle, listOf(task), expectFailure = true)
            assertThat(second.output).contains("Reusing configuration cache", "$task is not supported when")
            assertThat(legacy.readText()).isEqualTo("legacy marker")
            assertThat(global.readText()).isEqualTo("global marker")
            assertThat(core.readText()).isEqualTo("core marker")
        }
        stored(run(gradle, listOf("help")))
        reused(run(gradle, listOf("help")))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `global consumption is rejected and a help request stays usable`(gradle: SupportedGradleVersion) {
        fixture(listOf("child"))
        val global = projectDir.resolve("global.lock").apply { writeText("global marker") }
        val help = listOf(":child:help")
        stored(run(gradle, help))
        reused(run(gradle, help))
        val failure = run(
            gradle, listOf(":child:dependencies", "--configuration", "runtimeClasspath"), expectFailure = true
        )
        assertThat(failure.output).contains("Global lock consumption", global.absolutePath, "is not supported when")
            .doesNotContain("cannot access")
        assertThat(global.readText()).isEqualTo("global marker")
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `automatic core migration is rejected before legacy files are rewritten`(gradle: SupportedGradleVersion) {
        fixture(emptyList(), rootPlugin = true)
        val legacy = projectDir.resolve("dependencies.lock").apply { writeText("legacy marker") }
        val core = projectDir.resolve("gradle.lockfile").apply { writeText("core marker") }
        val failure = run(
            gradle, listOf("dependencies", "--write-locks"), core = true, expectFailure = true
        )
        assertThat(failure.output).contains("Automatic migration of legacy locks", "is not supported when")
        assertThat(legacy.readText()).isEqualTo("legacy marker")
        assertThat(core.readText()).isEqualTo("core marker")
    }

    private fun fixture(children: List<String>, rootPlugin: Boolean = false) {
        projectDir.resolve("settings.gradle").writeText(
            "rootProject.name = 'application'\n" + children.joinToString("\n") { "include ':$it'" }
        )
        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        projectDir.resolve("build.gradle").writeText(if (rootPlugin) localBuild() else "")
        children.forEach { path ->
            val directory = projectDir.resolve(path.replace(':', '/'))
            directory.mkdirs()
            directory.resolve("build.gradle").writeText(localBuild())
        }
    }

    private fun localBuild() = """
        plugins {
            id 'java-library'
            id 'com.netflix.nebula.dependency-lock'
        }
        tasks.withType(nebula.plugin.dependencylock.tasks.GenerateLockTask).configureEach {
            throw new GradleException('Generation task was unexpectedly configured')
        }
    """.trimIndent()

    private fun run(
        gradle: SupportedGradleVersion,
        arguments: List<String>,
        enabled: Boolean = true,
        isolated: Boolean = true,
        core: Boolean = false,
        expectFailure: Boolean = false
    ): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val options = mutableListOf(
            "--configuration-cache", "--configuration-cache-problems=fail", "--no-watch-fs", "--stacktrace", "--console=plain",
            "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled", "-Dnebula.features.coreLockingSupport=$core"
        )
        if (isolated) options.add(
            if (GradleVersion.version(version) >= GradleVersion.version("9.7")) "--isolated-projects"
            else "-Dorg.gradle.unsafe.isolated-projects=true"
        )
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(version).withArguments(arguments + options)
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }
}
