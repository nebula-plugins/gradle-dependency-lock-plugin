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

internal class IsolatedProjectsCommitTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `root commit follows declared child saves and preserves local publication integration`(gradle: SupportedGradleVersion) {
        fixture()
        write("settings.gradle", "rootProject.name = 'commit-locks'\ninclude ':libraries:lib', ':tools:lib', ':untouched'\n")
        write("build.gradle", """
            plugins { id 'java-library'; id 'maven-publish'; id 'com.netflix.nebula.dependency-lock' }
            group = 'fixture'
            version = '1.0'
            commitDependencyLock {
                message = 'Refresh normal locks'
                remoteRetries = 1
                saveTaskPaths = [':libraries:lib:saveLock', ':tools:lib:saveLock']
            }
            tasks.named('commitLock') { patternsToCommit = ['dependencies.lock', 'custom.lock'] }
            publishing {
                publications { library(MavenPublication) { from components.java } }
                repositories { maven { url = layout.buildDirectory.dir('published') } }
            }
            tasks.named('publish') { dependsOn tasks.named('commitLock') }
        """.trimIndent())
        for ((path, name) in listOf("libraries/lib" to "dependencies.lock", "tools/lib" to "custom.lock")) {
            write("$path/build.gradle", """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
                repositories { maven { url = uri('${projectDir.resolve("repo").toURI()}') } }
                dependencies { implementation 'fixture:widget:1.0' }
                dependencyLock { lockFile = '$name'; configurationNames = ['runtimeClasspath'] }
            """.trimIndent())
        }
        write("untouched/build.gradle", "")
        initGit()
        write("unrelated.txt", "leave untracked")
        stored(run(gradle, listOf("help")))
        reused(run(gradle, listOf("help")))
        val tasks = listOf("publish", "saveLock", "generateLock", "--parallel")
        val first = run(gradle, tasks)
        stored(first)
        val paths = first.tasks.map { it.path }
        for (save in listOf(":saveLock", ":libraries:lib:saveLock", ":tools:lib:saveLock")) {
            assertThat(paths.indexOf(save)).isGreaterThanOrEqualTo(0).isLessThan(paths.indexOf(":commitLock"))
        }
        assertThat(paths.indexOf(":commitLock")).isLessThan(paths.indexOf(":publish"))
        assertThat(git("log", "-1", "--format=%s")).isEqualTo("Refresh normal locks")
        val committed = git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").lines()
        assertThat(committed).containsExactlyInAnyOrder("dependencies.lock", "libraries/lib/dependencies.lock", "tools/lib/custom.lock")
        for (path in committed) assertThat(git("show", "HEAD:$path")).isEqualTo(projectDir.resolve(path).readText().trim())
        assertThat(git("status", "--porcelain")).isEqualTo("?? unrelated.txt")
        assertThat(projectDir.resolve("build/published/fixture/commit-locks/1.0/commit-locks-1.0.pom")).exists()

        val head = git("rev-parse", "HEAD")
        run(gradle, tasks)
        reused(run(gradle, tasks))
        assertThat(git("rev-parse", "HEAD")).isEqualTo(head)
        val commitOnly = run(gradle, listOf("commitLock"))
        assertThat(commitOnly.tasks.map { it.path }).doesNotContain(":saveLock", ":generateLock", ":libraries:lib:saveLock", ":tools:lib:saveLock")
        reused(run(gradle, listOf("commitLock")))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `explicit commit properties and tags work with recursive patterns and cache reuse`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            commitDependencyLock { message = 'extension message'; tag = 'extension-tag'; remoteRetries = 2 }
            tasks.named('commitLock') { patternsToCommit = ['custom.lock'] }
        """.trimIndent())
        initGit()
        write("nested/custom.lock", "first lock")
        val tasks = listOf("commitLock", "-PcommitDependencyLock.message=CLI message", "-PcommitDependencyLock.tag=explicit-lock-tag")
        stored(run(gradle, tasks))
        assertThat(git("log", "-1", "--format=%s")).isEqualTo("CLI message")
        assertThat(git("show", "HEAD:nested/custom.lock")).isEqualTo("first lock")
        assertThat(git("tag", "--list")).isEqualTo("explicit-lock-tag")
        assertThat(git("cat-file", "-t", "refs/tags/explicit-lock-tag")).isEqualTo("tag")
        assertThat(git("rev-list", "-n", "1", "explicit-lock-tag")).isEqualTo(git("rev-parse", "HEAD"))
        val head = git("rev-parse", "HEAD")
        val duplicateTag = run(gradle, tasks, expectFailure = true)
        reused(duplicateTag)
        assertThat(duplicateTag.output).contains("Git tag failed", "already exists")
        assertThat(git("rev-parse", "HEAD")).isEqualTo(head)
        git("tag", "-d", "explicit-lock-tag")
        write("nested/custom.lock", "updated lock")
        reused(run(gradle, tasks))
        assertThat(git("show", "HEAD:nested/custom.lock")).isEqualTo("updated lock")
        assertThat(git("rev-list", "-n", "1", "explicit-lock-tag")).isEqualTo(git("rev-parse", "HEAD"))
        assertThat(git("status", "--porcelain")).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `ordinary cache commits additions changes and deletions and preserves Git failures`(gradle: SupportedGradleVersion) {
        fixture()
        write("build.gradle", """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            tasks.named('commitLock') { patternsToCommit = ['custom.lock'] }
        """.trimIndent())
        initGit()
        write("nested/custom.lock", "first lock")
        val tasks = listOf("commitLock")
        stored(run(gradle, tasks, isolated = false))
        // Successful Git hooks may write diagnostics to stderr without failing the command.
        write(".git/hooks/pre-commit", "#!/bin/sh\necho 'fixture diagnostic' >&2\nexit 0\n")
        assertThat(projectDir.resolve(".git/hooks/pre-commit").setExecutable(true)).isTrue()
        write("nested/custom.lock", "updated lock")
        reused(run(gradle, tasks, isolated = false))
        assertThat(git("show", "HEAD:nested/custom.lock")).isEqualTo("updated lock")
        assertThat(projectDir.resolve("nested/custom.lock").delete()).isTrue()
        reused(run(gradle, tasks, isolated = false))
        assertThat(git("ls-tree", "-r", "--name-only", "HEAD")).doesNotContain("nested/custom.lock")

        val head = git("rev-parse", "HEAD")
        write(".git/hooks/pre-commit", "#!/bin/sh\necho 'fixture refuses commit' >&2\nexit 1\n")
        write("nested/custom.lock", "blocked lock")
        val failed = run(gradle, tasks, isolated = false, expectFailure = true)
        reused(failed)
        assertThat(failed.output).contains("Git commit failed", "fixture refuses commit")
        assertThat(git("rev-parse", "HEAD")).isEqualTo(head)
        assertThat(projectDir.resolve("nested/custom.lock").readText()).isEqualTo("blocked lock")
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'commit-locks'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        write(".gitignore", ".gradle/\n.gradle-test-kit/\n**/build/\n")
        val base = "repo/fixture/widget/1.0/widget-1.0"
        write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>widget</artifactId><version>1.0</version></project>")
        JarOutputStream(projectDir.resolve("$base.jar").outputStream()).use { }
    }

    private fun initGit() {
        git("init")
        git("config", "user.name", "Lock Test")
        git("config", "user.email", "lock-test@example.invalid")
        git("config", "commit.gpgsign", "false")
        git("config", "tag.gpgsign", "false")
        git("add", ".")
        git("commit", "-m", "Initial fixture")
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun run(gradle: SupportedGradleVersion, tasks: List<String>, isolated: Boolean = true, expectFailure: Boolean = false): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true"
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + (if (isolated) listOf(isolation) else emptyList()) + listOf("--configuration-cache", "--configuration-cache-problems=fail",
                "--no-watch-fs", "--stacktrace", "--console=plain", "-Dnebula.features.coreLockingSupport=false",
                "-Dnebula.dependency-lock.isolatedProjectsSupport=true"))
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private fun stored(result: BuildResult) = assertThat(result.output).contains("Configuration cache entry stored")
    private fun reused(result: BuildResult) = assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
}
