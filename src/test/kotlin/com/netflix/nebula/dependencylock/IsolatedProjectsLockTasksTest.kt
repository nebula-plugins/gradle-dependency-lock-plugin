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

internal class IsolatedProjectsLockTasksTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `qualified relative and abbreviated requests affect only the selected nested project`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':libraries:lib', ':tools:lib'\n")
        for (path in listOf("libraries/lib", "tools/lib")) {
            write("$path/build.gradle", localBuild())
            write("$path/dependencies.lock", locks("1.0"))
        }
        for (selector in listOf(listOf(":libraries:lib:generateLock"), listOf("libraries:lib:gL"), listOf(":l:lib:gL"),
            listOf("-p", "libraries", "lib:gL"), listOf("-p", "libraries", "gL"))) {
            val tasks = selector + listOf(":tools:lib:dependencies", "--configuration", "runtimeClasspath")
            val first = run(gradle, tasks)
            stored(first)
            assertThat(first.output).contains("fixture:widget:1.+ -> 1.0")
            assertThat(version("libraries/lib/build/dependencies.lock", "widget")).isEqualTo("1.1")
            assertThat(first.task(":tools:lib:generateLock")).isNull()
            reused(run(gradle, tasks))
        }
        val all = listOf("gL")
        stored(run(gradle, all))
        reused(run(gradle, all))
        assertThat(version("tools/lib/build/dependencies.lock", "widget")).isEqualTo("1.1")

        val mixed = listOf(":libraries:lib:updateLock", ":tools:lib:generateLock", "-PdependencyLock.updateDependencies=fixture:widget")
        stored(run(gradle, mixed))
        reused(run(gradle, mixed))
        assertThat(version("libraries/lib/build/dependencies.lock", "other")).isEqualTo("1.0")
        assertThat(version("tools/lib/build/dependencies.lock", "other")).isEqualTo("1.1")

        // The selected project's verifier must recognize the same abbreviated generation intent.
        val verified = listOf(":l:lib:gL", ":libraries:lib:dependencies", "--configuration", "runtimeClasspath")
        stored(run(gradle, verified, alignment = true))
        reused(run(gradle, verified, alignment = true))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `generate and update see newly published dynamic versions after cache reuse`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        write("child/build.gradle", localBuild())
        for (task in listOf("generateLock", "updateLock")) {
            metadata("widget", listOf("1.0"))
            write("child/dependencies.lock", locks("1.0"))
            val tasks = listOf(":child:$task", "-PdependencyLock.updateDependencies=fixture:widget")
            stored(run(gradle, tasks))
            reused(run(gradle, tasks))
            assertThat(version("child/build/dependencies.lock", "widget")).isEqualTo("1.0")
            metadata("widget", listOf("1.0", "1.1"))
            run(gradle, tasks)
            assertThat(version("child/build/dependencies.lock", "widget")).isEqualTo("1.1")
            reused(run(gradle, tasks))
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `generation refreshes changing modules after cache reuse`(gradle: SupportedGradleVersion) {
        fixture()
        module("changing", "1.0-SNAPSHOT", "1.0")
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        write("child/build.gradle", localBuild("implementation('fixture:changing:1.0-SNAPSHOT') { changing = true }") +
            "\ndependencyLock.includeTransitives = true\n")
        val tasks = listOf(":child:generateLock")
        stored(run(gradle, tasks))
        reused(run(gradle, tasks))
        assertThat(version("child/build/dependencies.lock", "leaf")).isEqualTo("1.0")
        module("changing", "1.0-SNAPSHOT", "1.1")
        run(gradle, tasks)
        assertThat(version("child/build/dependencies.lock", "leaf")).isEqualTo("1.1")
        reused(run(gradle, tasks))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `selective updates diff before saving and retain unselected locks`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        write("child/build.gradle", localBuild())
        for (pathAware in listOf(false, true)) {
            write("child/dependencies.lock", locks("1.0"))
            val tasks = listOf(":child:saveLock", ":child:diffLock", ":child:updateLock",
                "-PdependencyLock.updateDependencies=fixture:widget", "-Dnebula.features.pathAwareDependencyDiff=$pathAware")
            val first = run(gradle, tasks)
            stored(first)
            assertThat(first.tasks.map { it.path }).containsSubsequence(":child:updateLock", ":child:diffLock", ":child:saveLock")
            assertThat(version("child/dependencies.lock", "widget")).isEqualTo("1.1")
            assertThat(version("child/dependencies.lock", "other")).isEqualTo("1.0")
            val diff = projectDir.resolve("child/build/dependency-lock/lockdiff.${if (pathAware) "json" else "txt"}")
            assertThat(diff.readText()).contains("widget", "1.0", "1.1")
            run(gradle, tasks)
            reused(run(gradle, tasks))
            val overridden = tasks + "-PdependencyLock.override=fixture:other:1.1"
            run(gradle, overridden)
            assertThat(version("child/dependencies.lock", "other")).isEqualTo("1.1")
            assertThat(ObjectMapper().readTree(projectDir.resolve("child/dependencies.lock"))["runtimeClasspath"]["fixture:other"]["viaOverride"].asText()).isEqualTo("1.1")
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `diff tracks generated contents without implicitly generating or saving`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        write("child/build.gradle", localBuild())
        write("child/dependencies.lock", locks("1.0"))
        write("child/build/dependencies.lock", locks("1.1"))
        val tasks = listOf(":child:diffLock", "-Dnebula.features.pathAwareDependencyDiff=false")
        stored(run(gradle, tasks))
        val cached = run(gradle, tasks)
        reused(cached)
        assertThat(cached.task(":child:diffLock")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        write("child/build/dependencies.lock", locks("1.0"))
        val changed = run(gradle, tasks)
        reused(changed)
        assertThat(changed.task(":child:diffLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(changed.task(":child:generateLock")).isNull()
        assertThat(changed.task(":child:saveLock")).isNull()
        assertThat(projectDir.resolve("child/build/dependency-lock/lockdiff.txt").readText()).doesNotContain("widget", "updated:")
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `delete supports custom filenames and repeated invocation without generation`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        write("child/build.gradle", localBuild())
        write("child/custom.lock", locks("1.0"))
        val tasks = listOf(":child:deleteLock", "-PdependencyLock.lockFile=custom.lock")
        val first = run(gradle, tasks)
        stored(first)
        assertThat(first.task(":child:saveLock")).isNull()
        assertThat(first.task(":child:generateLock")).isNull()
        assertThat(projectDir.resolve("child/custom.lock")).doesNotExist()
        reused(run(gradle, tasks))
        write("child/custom.lock", locks("1.1"))
        reused(run(gradle, tasks))
        assertThat(projectDir.resolve("child/custom.lock")).doesNotExist()
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `late configurations and plugin order preserve lazy help and generation`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'lock-tasks'\ninclude ':child'\n")
        for (plugins in listOf("id 'java-library'; id 'com.netflix.nebula.dependency-lock'",
            "id 'com.netflix.nebula.dependency-lock'; id 'java-library'")) {
            write("child/build.gradle", """
                plugins { $plugins }
                repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
                def forbidResolution = providers.gradleProperty('forbidResolution').isPresent()
                configurations.configureEach {
                    incoming.beforeResolve { if (forbidResolution) throw new GradleException('Unexpected resolution on help') }
                }
                tasks.withType(nebula.plugin.dependencylock.tasks.GenerateLockTask).configureEach {
                    if (forbidResolution) throw new GradleException('Unexpected lock task realization on help')
                }
                afterEvaluate {
                    configurations.create('late') { canBeConsumed = false; canBeResolved = true }
                    dependencies.add('late', 'fixture:widget:1.0')
                    dependencyLock.configurationNames = ['late']
                }
            """.trimIndent())
            val help = listOf(":child:help", "-PforbidResolution=true")
            stored(run(gradle, help))
            reused(run(gradle, help))
            val generation = listOf(":child:generateLock")
            stored(run(gradle, generation))
            reused(run(gradle, generation))
            val lock = ObjectMapper().readTree(projectDir.resolve("child/build/dependencies.lock"))
            assertThat(lock.fieldNames().asSequence().toList()).containsExactly("late")
            assertThat(lock["late"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")
        }
    }

    private fun fixture() {
        write("build.gradle", "")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        for (name in listOf("widget", "leaf", "other")) {
            for (version in listOf("1.0", "1.1")) module(name, version)
            metadata(name, listOf("1.0", "1.1"))
        }
    }

    private fun localBuild(dependencies: String = "implementation 'fixture:widget:1.+'; implementation 'fixture:other:1.+'") = """
        plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
        repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
        dependencyLock.configurationNames = ['runtimeClasspath']
        configurations.configureEach {
            resolutionStrategy.cacheDynamicVersionsFor(24, 'hours')
            resolutionStrategy.cacheChangingModulesFor(24, 'hours')
        }
        dependencies { $dependencies }
    """.trimIndent()

    private fun locks(version: String) = """{"runtimeClasspath":{"fixture:widget":{"locked":"$version"},"fixture:other":{"locked":"$version"}}}"""

    private fun version(path: String, name: String) = ObjectMapper().readTree(projectDir.resolve(path))["runtimeClasspath"]["fixture:$name"]["locked"].asText()

    private fun module(name: String, version: String, leafVersion: String? = null) {
        val dependencies = leafVersion?.let { "<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>$it</version></dependency></dependencies>" } ?: ""
        val base = "repo/fixture/$name/$version/$name-$version"
        write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>$version</version>$dependencies</project>")
        JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
    }

    private fun metadata(name: String, versions: List<String>) {
        write("repo/fixture/$name/maven-metadata.xml", "<metadata><groupId>fixture</groupId><artifactId>$name</artifactId><versioning><latest>${versions.last()}</latest><release>${versions.last()}</release><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>")
    }

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(gradle: SupportedGradleVersion, tasks: List<String>, alignment: Boolean = false): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true"
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + listOf(isolation, "--configuration-cache", "--configuration-cache-problems=fail",
                "--no-watch-fs", "--stacktrace", "--console=plain", "-Dnebula.features.coreLockingSupport=false",
                "-Dnebula.features.coreAlignmentSupport=$alignment", "-Dnebula.dependency-lock.isolatedProjectsSupport=true")).build()
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }
}
