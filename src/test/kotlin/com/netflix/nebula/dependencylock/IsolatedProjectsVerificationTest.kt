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
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.jar.JarOutputStream

internal class IsolatedProjectsVerificationTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `duplicate leaf projects have independent reporting outcomes in parallel`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'verification'\ninclude ':libraries:lib', ':tools:lib'\n")
        write("build.gradle", "")
        write("libraries/lib/build.gradle", localBuild("fixture:missing") + """

            dependencyResolutionVerifierExtension {
                shouldFailTheBuild = false
                missingVersionsMessageAddition = 'library guidance'
            }
            tasks.named('verifyDependencyResolution') {
                mustRunAfter(':tools:lib:verifyDependencyResolution')
            }
        """.trimIndent())
        write("tools/lib/build.gradle", localBuild("fixture:widget:1.0"))
        val tasks = listOf(":tools:lib:dependencies", "--configuration", "runtimeClasspath",
            ":libraries:lib:dependencies", "--configuration", "runtimeClasspath", "--parallel")
        for (isolated in listOf(false, true)) {
            val first = run(gradle, tasks, isolated)
            stored(first)
            assertThat(first.output).contains("BUILD SUCCESSFUL", "library guidance", "Failed to resolve 'fixture:missing'")
            assertThat(first.tasks.map { it.path }).containsSubsequence(
                ":tools:lib:verifyDependencyResolution", ":libraries:lib:verifyDependencyResolution")
            for (path in listOf(":libraries:lib", ":tools:lib")) {
                assertThat(first.task("$path:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            }
            val second = run(gradle, tasks, isolated)
            reused(second)
            assertThat(second.output).contains("BUILD SUCCESSFUL", "library guidance", "Failed to resolve 'fixture:missing'")
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `reporting failures policies and corrections survive consecutive cached builds`(gradle: SupportedGradleVersion) {
        fixture()
        val broken = localBuild("fixture:missing") + """

            dependencyResolutionVerifierExtension.missingVersionsMessageAddition = 'missing version guidance'
        """.trimIndent()
        write("build.gradle", broken)
        val tasks = listOf("dependencies", "--configuration", "runtimeClasspath")
        for (isolated in listOf(false, true)) {
            repeat(2) { index ->
                val failed = run(gradle, tasks, isolated, expectFailure = true)
                if (index == 0) stored(failed) else reused(failed)
                assertThat(failed.output).contains("missing version guidance", "Failed to resolve 'fixture:missing'", "BUILD FAILED")
                assertThat(failed.task(":verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            }
            val warn = tasks + "-PdependencyResolutionVerifier.unresolvedDependenciesFailTheBuild=false"
            stored(run(gradle, warn, isolated))
            val warning = run(gradle, warn, isolated)
            reused(warning)
            assertThat(warning.output).contains("missing version guidance", "BUILD SUCCESSFUL")

            write("build.gradle", localBuild("fixture:widget:1.0"))
            stored(run(gradle, tasks, isolated))
            val fixed = run(gradle, tasks, isolated)
            reused(fixed)
            assertThat(fixed.output).doesNotContain("Failed to resolve", "missing version guidance")
            write("build.gradle", broken)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `lock mismatches fail tasks and reporting builds while overrides remain valid`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", localBuild("fixture:widget:1.0") + """

            dependencies {
                implementation 'fixture:leaf:1.1'
                components.all { details ->
                    if (details.id.group == 'fixture') details.belongsTo("fixture:platform:${'$'}{details.id.version}")
                }
            }
            dependencyResolutionVerifierExtension.resolvedVersionDoesNotEqualLockedVersionMessageAddition = 'lock mismatch guidance'
            dependencyLock.lockFile = 'custom.lock'
        """.trimIndent())
        write("custom.lock", """{"runtimeClasspath":{"fixture:widget":{"locked":"1.0"}}}""")
        for (tasks in listOf(listOf("verifyDependencyResolution"), listOf("dependencies", "--configuration", "runtimeClasspath"),
            listOf("dependencyInsight", "--dependency=fixture:widget", "--configuration=runtimeClasspath", "--single-path"))) {
            repeat(2) { index ->
                val failed = run(gradle, tasks, expectFailure = true)
                if (index == 0) stored(failed) else reused(failed)
                assertThat(failed.output).contains("lock mismatch guidance",
                    "Resolved 'fixture:widget:1.1' instead of locked version '1.0'", "BUILD FAILED")
                assertThat(failed.task(":verifyDependencyResolution")?.outcome).isEqualTo(
                    if (tasks.first() == "verifyDependencyResolution") TaskOutcome.FAILED else TaskOutcome.SUCCESS)
            }
            val overridden = tasks + "-PdependencyLock.override=fixture:widget:1.1"
            stored(run(gradle, overridden))
            val valid = run(gradle, overridden)
            reused(valid)
            assertThat(valid.output).doesNotContain("lock mismatch guidance", "instead of locked version")
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `report scoping and explicit exclusions avoid unrelated verification failures`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", localBuild("fixture:widget:1.0") + """

            configurations { broken { canBeResolved = true; canBeConsumed = false } }
            dependencies { broken 'fixture:missing:1.0' }
        """.trimIndent())
        val report = listOf("dependencies", "--configuration", "runtimeClasspath")
        stored(run(gradle, report))
        reused(run(gradle, report))
        val insight = listOf("dependencyInsight", "--dependency=fixture:widget", "--configuration=runtimeClasspath", "--single-path")
        stored(run(gradle, insight))
        reused(run(gradle, insight))
        val verify = listOf("verifyDependencyResolution")
        val failed = run(gradle, verify, expectFailure = true)
        assertThat(failed.output).contains("Failed to resolve 'fixture:missing:1.0'")
        val excluded = verify + "-PdependencyResolutionVerifier.configurationsToExclude=broken"
        stored(run(gradle, excluded))
        reused(run(gradle, excluded))

        projectDir.resolve("build.gradle").appendText("\ndependencyResolutionVerifierExtension.tasksToExclude = ['dependencies']\n")
        val allReports = listOf("dependencies")
        val skipped = run(gradle, allReports)
        stored(skipped)
        assertThat(skipped.task(":verifyDependencyResolution")).isNull()
        reused(run(gradle, allReports))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `compile and lifecycle finalizers remain available on cache hits`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", localBuild("fixture:widget:1.0"))
        write("src/main/java/Example.java", "public class Example {}")
        for (task in listOf("compileJava", "check")) {
            val first = run(gradle, listOf(task))
            stored(first)
            assertThat(first.task(":verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            val second = run(gradle, listOf(task))
            reused(second)
            assertThat(second.task(":verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `enabled locking leaves preexisting public legacy map entries untouched`(gradle: SupportedGradleVersion) {
        fixture()
        write("dependencies.lock", """{"runtimeClasspath":{"fixture:widget":{"locked":"1.0"}}}""")
        write("build.gradle", """
            import nebula.plugin.dependencylock.DependencyLockPlugin
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' apply false }
            DependencyLockPlugin.lockedDepsPerProjectForConfigurations['verification-rootproject'] = [marker: []]
            DependencyLockPlugin.overrideDepsPerProjectForConfigurations['verification-rootproject'] = [marker: []]
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
            dependencies { implementation 'fixture:widget:1.0' }
            tasks.register('inspectLegacyState') {
                // Resolve locally so both lock and override publication have happened before inspection.
                configurations.runtimeClasspath.incoming.resolutionResult.rootComponent.get()
                inputs.property('legacyLockStatePreserved', DependencyLockPlugin.lockedDepsPerProjectForConfigurations['verification-rootproject'] == [marker: []])
                inputs.property('legacyOverrideStatePreserved', DependencyLockPlugin.overrideDepsPerProjectForConfigurations['verification-rootproject'] == [marker: []])
                doLast {
                    inputs.properties.each { key, value -> println("${'$'}key=${'$'}value") }
                }
            }
        """.trimIndent())
        for (enabled in listOf(false, true)) {
            val result = run(gradle, listOf("dependencies", "--configuration", "runtimeClasspath", "inspectLegacyState",
                "-PdependencyLock.override=fixture:widget:1.0"), enabled = enabled)
            stored(result)
            assertThat(result.output).contains("legacyLockStatePreserved=$enabled", "legacyOverrideStatePreserved=$enabled")
        }
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'verification'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        for (name in listOf("widget", "leaf")) {
            for (version in listOf("1.0", "1.1")) {
                val base = "repo/fixture/$name/$version/$name-$version"
                write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>$version</version></project>")
                JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
            }
        }
    }

    private fun localBuild(dependency: String) = """
        plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
        repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
        dependencies { implementation '$dependency' }
    """.trimIndent()

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(
        gradle: SupportedGradleVersion, tasks: List<String>, isolated: Boolean = true,
        enabled: Boolean = true, expectFailure: Boolean = false
    ): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val options = mutableListOf("--configuration-cache", "--configuration-cache-problems=fail", "--no-watch-fs",
            "--console=plain", "--stacktrace", "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled",
            "-Dnebula.features.coreLockingSupport=false", "-Dnebula.features.coreAlignmentSupport=true")
        if (isolated) options.add(if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true")
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(version).withArguments(tasks + options)
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) {
        assertThat(result.output).contains("Configuration cache entry stored")
    }

    private fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
    }
}
