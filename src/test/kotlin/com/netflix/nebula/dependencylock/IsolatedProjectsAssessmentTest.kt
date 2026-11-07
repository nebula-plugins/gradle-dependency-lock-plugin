package com.netflix.nebula.dependencylock

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.jar.JarOutputStream

/** Strict compatibility checks and explicitly named blocker characterizations; no warnings mode. */
internal class IsolatedProjectsAssessmentTest {
    @TempDir
    lateinit var projectDir: File

    private var invocation = 0

    data class Mode(val version: String, val isolated: Boolean) {
        override fun toString() = "Gradle $version / ${if (isolated) "isolated" else "ordinary CC"}"

        fun arguments(): List<String> = buildList {
            addAll(listOf("--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace", "--console=plain"))
            // TestKit edits files outside the daemon. Force fresh snapshots on all tested versions.
            add("--no-watch-fs")
            add("-Dnebula.features.coreLockingSupport=false")
            if (isolated) {
                // The stable option was introduced in 9.7; 9.2 requires the experimental property.
                add(if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
                    "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true")
            }
        }
    }

    companion object {
        @JvmStatic
        fun modes() = listOf("9.2.1", GradleVersion.current().version).distinct().flatMap { version ->
            listOf(Mode(version, false), Mode(version, true))
        }
    }

    @ParameterizedTest(name = "single project generation, reuse and property invalidation: {0}")
    @MethodSource("modes")
    fun `single project generation preserves observable behavior`(mode: Mode) {
        fixture()
        write("build.gradle", localBuild("implementation 'fixture:widget:1.0'"))

        val first = run(mode, "generateLock")
        stored(first)
        assertThat(first.task(":generateLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(lock()["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")
        assertThat(lock()["runtimeClasspath"].has("fixture:leaf")).isFalse()
        val original = projectDir.resolve("build/dependencies.lock").readText()

        reused(run(mode, "generateLock"))
        assertThat(projectDir.resolve("build/dependencies.lock").readText()).isEqualTo(original)

        val changed = run(mode, "generateLock", "-PdependencyLock.includeTransitives=true")
        stored(changed)
        assertThat(changed.output).doesNotContain("Reusing configuration cache")
        assertThat(lock()["runtimeClasspath"]["fixture:leaf"]["locked"].asText()).isEqualTo("1.0")
        reused(run(mode, "generateLock", "-PdependencyLock.includeTransitives=true"))
    }

    @ParameterizedTest(name = "single project lock consumption, reuse and file invalidation: {0}")
    @MethodSource("modes")
    fun `single project lock edits invalidate cached resolution`(mode: Mode) {
        fixture()
        write("build.gradle", localBuild("implementation 'fixture:widget:1.1'"))
        writeLock("1.0")

        val first = run(mode, "dependencies", "--configuration", "runtimeClasspath")
        stored(first)
        assertThat(first.output).contains("fixture:widget:1.1 -> 1.0")
        assertThat(first.task(":verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val second = run(mode, "dependencies", "--configuration", "runtimeClasspath")
        reused(second)
        assertThat(second.output).contains("fixture:widget:1.1 -> 1.0")

        writeLock("1.1")
        val changed = run(mode, "dependencies", "--configuration", "runtimeClasspath")
        stored(changed)
        assertThat(changed.output).doesNotContain("Reusing configuration cache", "fixture:widget:1.1 -> 1.0")
        assertThat(changed.output).contains("fixture:widget:1.1")
        reused(run(mode, "dependencies", "--configuration", "runtimeClasspath"))
    }

    @ParameterizedTest(name = "flat projects: ordinary success or expected isolation blocker: {0}")
    @MethodSource("modes")
    fun `flat project application characterizes root mutable state blocker`(mode: Mode) {
        projectWorkflow(mode, nested = false)
    }

    @ParameterizedTest(name = "nested projects with duplicate leaf names: ordinary success or expected isolation blocker: {0}")
    @MethodSource("modes")
    fun `nested project application characterizes root mutable state blocker`(mode: Mode) {
        projectWorkflow(mode, nested = true)
    }

    private fun projectWorkflow(mode: Mode, nested: Boolean) {
        fixture()
        val app = if (nested) "services:app" else "app"
        val lib = if (nested) "libraries:lib" else "lib"
        val projects = if (nested) listOf(app, lib, "tools:lib") else listOf(app, lib)
        write("settings.gradle", "rootProject.name = 'assessment'\n" + projects.joinToString("\n") { "include ':$it'" })
        write("build.gradle", "plugins { id 'com.netflix.nebula.dependency-lock' }\n")
        projects.forEach { path ->
            val dependencies = if (path == app) "implementation project(':$lib')" else "implementation 'fixture:widget:1.0'"
            write("${path.replace(':', '/')}/build.gradle", localBuild(dependencies, "fixture.${path.substringBefore(':')}"))
        }
        val tasks = arrayOf(":$app:generateLock", ":$app:saveLock")
        if (mode.isolated) {
            // Characterization ONLY: this must fail, and can never establish compatibility.
            val failure = run(mode, *tasks, expectFailure = true)
            assertThat(failure.output).contains("cannot access", "extension", "another project ':'")
            assertThat(failure.output).doesNotContain("Configuration cache entry stored", "Configuration cache entry reused")
            return
        }
        val first = run(mode, *tasks)
        stored(first)
        assertThat(first.task(":$app:saveLock")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val saved = projectDir.resolve("${app.replace(':', '/')}/dependencies.lock")
        val json = ObjectMapper().readTree(saved)
        assertThat(json["runtimeClasspath"]["fixture.${lib.substringBefore(':')}:lib"]["project"].asBoolean()).isTrue()
        assertThat(json["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")

        // Saving creates a configuration input, so warm again before asserting steady-state reuse.
        val bytes = saved.readText()
        run(mode, *tasks)
        reused(run(mode, *tasks))
        assertThat(saved.readText()).isEqualTo(bytes)
        write("${lib.replace(':', '/')}/build.gradle", localBuild("implementation 'fixture:widget:1.1'", "fixture.${lib.substringBefore(':')}"))
        val changed = run(mode, *tasks)
        stored(changed)
        assertThat(changed.output).doesNotContain("Reusing configuration cache")
        assertThat(ObjectMapper().readTree(saved)["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.1")
    }

    @ParameterizedTest(name = "root-only application: ordinary success or expected peer group blocker: {0}")
    @MethodSource("modes")
    fun `root only generation characterizes peer coordinate access`(mode: Mode) {
        fixture()
        write("settings.gradle", "rootProject.name = 'assessment'\ninclude ':lib'\n")
        write("build.gradle", localBuild("implementation 'fixture:widget:1.0'"))
        // No lock plugin on the child: root-only application does not opt children into locking.
        write("lib/build.gradle", "plugins { id 'java-library' }\ngroup = 'fixture.child'\n")
        if (mode.isolated) {
            val failure = run(mode, "generateLock", expectFailure = true)
            assertThat(failure.output).contains("cannot access 'Project.group'", "via 'allprojects'")
            assertThat(failure.output).doesNotContain("Configuration cache entry stored", "Configuration cache entry reused")
        } else {
            stored(run(mode, "generateLock"))
            assertThat(lock()["runtimeClasspath"]["fixture:widget"]["locked"].asText()).isEqualTo("1.0")
            reused(run(mode, "generateLock"))
            assertThat(projectDir.resolve("lib/build/dependencies.lock")).doesNotExist()
        }
    }

    @ParameterizedTest(name = "root-only help preserves lazy lock task configuration: {0}")
    @MethodSource("modes")
    fun `root only help works without configuring generation tasks`(mode: Mode) {
        fixture()
        write("settings.gradle", "rootProject.name = 'assessment'\ninclude ':lib'\n")
        write("build.gradle", localBuild("implementation 'fixture:widget:1.0'") + """

            tasks.withType(nebula.plugin.dependencylock.tasks.GenerateLockTask).configureEach {
                throw new GradleException('Generation task was unexpectedly configured')
            }
        """.trimIndent())
        write("lib/build.gradle", "plugins { id 'java-library' }\ngroup = 'fixture.child'\n")
        stored(run(mode, "help"))
        reused(run(mode, "help"))
        assertThat(projectDir.resolve("build/dependencies.lock")).doesNotExist()
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'assessment'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        listOf("1.0", "1.1").forEach { version ->
            module("leaf", version)
            module("widget", version, "<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>$version</version></dependency></dependencies>")
        }
    }

    private fun module(name: String, version: String, dependencies: String = "") {
        val base = "repo/fixture/$name/$version/$name-$version"
        write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>$version</version>$dependencies</project>")
        JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
    }

    private fun localBuild(dependencies: String, group: String = "fixture.app") = """
        plugins {
            id 'java-library'
            id 'com.netflix.nebula.dependency-lock'
        }
        group = '$group'
        repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
        dependencies { $dependencies }
    """.trimIndent()

    private fun writeLock(version: String) {
        val entries = listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
            .associateWith { mapOf("fixture:widget" to mapOf("locked" to version)) }
        write("dependencies.lock", ObjectMapper().writeValueAsString(entries))
    }

    private fun lock(): JsonNode = ObjectMapper().readTree(projectDir.resolve("build/dependencies.lock"))

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(mode: Mode, vararg tasks: String, expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(mode.version).withArguments(mode.arguments() + tasks)
        val result = if (expectFailure) runner.buildAndFail() else runner.build()
        // Keep complete strict-mode reproductions outside repository sources for compatibility analysis.
        val evidence = File("build/reports/isolated-projects-assessment/${projectDir.name}")
        evidence.mkdirs()
        evidence.resolve("${++invocation}-${mode.version}.txt").writeText(
            "gradle ${mode.version} ${(mode.arguments() + tasks).joinToString(" ")}\n${result.output}"
        )
        return result
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }
}
