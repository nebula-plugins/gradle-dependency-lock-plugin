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
import java.util.jar.JarOutputStream

internal class IsolatedProjectsCustomizationTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `extension filters fail before generate or update writes even after task realization`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", buildScript() + """

            tasks.named('generateLock').get()
            tasks.named('updateLock').get()
            dependencyLock.dependencyFilter = { group, name, version -> throw new GradleException('callback was invoked') }
        """.trimIndent())
        stored(run(gradle, listOf("help")))
        reused(run(gradle, listOf("help")))
        for (task in listOf("generateLock", "updateLock")) {
            assertFilterRejected(gradle, task, "dependencyLock.dependencyFilter")
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `raw task filters are rejected whether configured before or after the plugin`(gradle: SupportedGradleVersion) {
        fixture()
        for (early in listOf(true, false)) {
            val callback = "tasks.withType(nebula.plugin.dependencylock.tasks.GenerateLockTask).configureEach { filter = { g, n, v -> false } }"
            val script = if (early) """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' apply false }
                $callback
                apply plugin: 'com.netflix.nebula.dependency-lock'
                repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
                dependencies { implementation 'fixture:widget:1.0' }
                dependencyLock { configurationNames = ['runtimeClasspath'] }
            """.trimIndent() else buildScript() + "\n$callback\n"
            write("build.gradle", script)
            for (task in listOf("generateLock", "updateLock")) {
                assertFilterRejected(gradle, task, "GenerateLockTask.filter")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `custom tag callbacks reject before staging on first and reused builds`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", buildScript() + """

            tasks.named('commitLock').get()
            commitDependencyLock {
                shouldCreateTag = true
                tag = 'explicit-tag'
                tagClosure = { throw new GradleException('callback was invoked') }
            }
        """.trimIndent())
        write(".gitignore", ".gradle/\n.gradle-test-kit/\n**/build/\n")
        git("init")
        git("config", "user.name", "Lock Test")
        git("config", "user.email", "lock-test@example.invalid")
        git("config", "commit.gpgsign", "false")
        git("add", ".")
        git("commit", "-m", "Initial fixture")
        val head = git("rev-parse", "HEAD")
        write("dependencies.lock", "{}")
        val status = git("status", "--porcelain")
        for (attempt in 0..1) {
            val failed = run(gradle, listOf("commitLock"), expectFailure = true)
            if (attempt == 0) stored(failed) else reused(failed)
            assertThat(failed.output).contains("commitDependencyLock.tagClosure", "nebula.dependency-lock.isolatedProjectsSupport=true", "Set commitDependencyLock.tag explicitly")
                .doesNotContain("callback was invoked", "Configuration cache problems found")
            assertThat(git("rev-parse", "HEAD")).isEqualTo(head)
            assertThat(git("status", "--porcelain")).isEqualTo(status)
            assertThat(git("diff", "--cached", "--name-only")).isEmpty()
            assertThat(git("tag", "--list")).isEmpty()
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `declarative exclusions and callback bridge access work in Groovy and Kotlin DSL`(gradle: SupportedGradleVersion) {
        fixture()
        for (kotlin in listOf(false, true)) {
            projectDir.resolve(if (kotlin) "build.gradle" else "build.gradle.kts").delete()
            val name = if (kotlin) "build.gradle.kts" else "build.gradle"
            val script = if (kotlin) """
                import nebula.plugin.dependencylock.DependencyLockExtension
                import nebula.plugin.dependencylock.DependencyLockCommitExtension
                plugins { `java-library`; id("com.netflix.nebula.dependency-lock") }
                repositories { maven { url = uri("${projectDir.resolve("repo").toURI()}") } }
                dependencies { implementation("fixture:widget:1.0"); implementation("fixture:other:1.0") }
                configure<DependencyLockExtension> {
                    setConfigurationNames(setOf("runtimeClasspath"))
                    setSkippedDependencies(setOf("fixture:widget"))
                    setLockFile("custom.lock")
                    check(configurationNames == setOf("runtimeClasspath"))
                    check(skippedDependencies == setOf("fixture:widget"))
                    check(dependencyFilter != null)
                }
                configure<DependencyLockCommitExtension> {
                    saveTaskPaths.set(emptyList())
                    tag.set("dsl-tag")
                    check(tagClosure != null)
                }
            """.trimIndent() else buildScript() + """

                dependencyLock {
                    skippedDependencies = ['fixture:widget']
                    lockFile = 'custom.lock'
                    assert configurationNames == ['runtimeClasspath'] as Set
                    assert skippedDependencies == ['fixture:widget'] as Set
                    assert dependencyFilter != null
                }
                commitDependencyLock { saveTaskPaths = []; tag = 'dsl-tag'; assert tagClosure != null }
            """.trimIndent()
            write(name, script)
            val tasks = listOf("generateLock", "saveLock")
            stored(run(gradle, tasks))
            run(gradle, tasks)
            reused(run(gradle, tasks))
            assertThat(projectDir.resolve("custom.lock").readText()).doesNotContain("fixture:widget")
            assertThat(projectDir.resolve("custom.lock").readText()).contains("runtimeClasspath", "fixture:other")

            val customization = if (kotlin) """

                configure<DependencyLockExtension> {
                    setDependencyFilter(object : groovy.lang.Closure<Boolean>(null) {
                        fun doCall(group: String, name: String, version: String): Boolean = false
                    })
                }
            """.trimIndent() else "\ndependencyLock.dependencyFilter = { g, n, v -> false }\n"
            write(name, script + "\n" + customization)
            val saved = projectDir.resolve("custom.lock").readText()
            for (attempt in 0..1) {
                val failed = run(gradle, tasks, expectFailure = true)
                if (attempt == 0) stored(failed) else reused(failed)
                assertThat(failed.output).contains("dependencyLock.dependencyFilter", "skippedDependencies")
                assertThat(projectDir.resolve("custom.lock").readText()).isEqualTo(saved)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `flag off retains callback filtering and extension APIs`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", buildScript() + """

            dependencyLock.dependencyFilter = { g, n, v -> n != 'widget' }
            commitDependencyLock.tagClosure = { 'consumer-tag' }
            assert commitDependencyLock.tagClosure.call() == 'consumer-tag'
        """.trimIndent())
        run(gradle, listOf("generateLock"), enabled = false)
        assertThat(projectDir.resolve("build/dependencies.lock").readText()).doesNotContain("fixture:widget")
        assertThat(projectDir.resolve("build/dependencies.lock").readText()).contains("runtimeClasspath", "fixture:other")
    }

    private fun assertFilterRejected(gradle: SupportedGradleVersion, task: String, option: String) {
        write("build/dependencies.lock", "generated marker")
        write("dependencies.lock", "{}")
        val options = if (task == "updateLock") listOf("-PdependencyLock.updateDependencies=fixture:widget") else emptyList()
        for (attempt in 0..1) {
            val failed = run(gradle, listOf(task, "saveLock") + options, expectFailure = true)
            if (attempt == 0) stored(failed) else reused(failed)
            assertThat(failed.output).contains(option, "nebula.dependency-lock.isolatedProjectsSupport=true", "skippedDependencies")
                .doesNotContain("callback was invoked", "Configuration cache problems found")
            assertThat(projectDir.resolve("build/dependencies.lock").readText()).isEqualTo("generated marker")
            assertThat(projectDir.resolve("dependencies.lock").readText()).isEqualTo("{}")
            assertThat(failed.task(":saveLock")).isNull()
        }
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'customization'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        for (name in listOf("widget", "other")) {
            val base = "repo/fixture/$name/1.0/$name-1.0"
            write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>1.0</version></project>")
            JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
        }
    }

    private fun buildScript() = """
        plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
        repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
        dependencies { implementation 'fixture:widget:1.0'; implementation 'fixture:other:1.0' }
        dependencyLock { configurationNames = ['runtimeClasspath'] }
    """.trimIndent()

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(gradle: SupportedGradleVersion, tasks: List<String>, expectFailure: Boolean = false, enabled: Boolean = true): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true"
        val options = if (enabled) listOf(isolation, "--configuration-cache", "--configuration-cache-problems=fail") else listOf("--no-configuration-cache")
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + options + listOf("--no-watch-fs", "--stacktrace", "--console=plain",
                "-Dnebula.features.coreLockingSupport=false", "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled"))
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) = assertThat(result.output).contains("Configuration cache entry stored")
    private fun reused(result: BuildResult) = assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
}
