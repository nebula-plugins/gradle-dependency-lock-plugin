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

internal class DependencyLockingPropertyLookupTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `local precedence and types are preserved without parent lookup`(gradle: SupportedGradleVersion) {
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'lookup'\ninclude 'child'\n")
        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\n")
        projectDir.resolve("build.gradle").writeText(
            """
            ext.set('probe.parent', 'inherited')
            ext.set('probe.local', 'parent')
            ext.set('probe.null', 'parent')
            """.trimIndent()
        )
        projectDir.resolve("child").mkdirs()
        projectDir.resolve("child/build.gradle").writeText(
            """
            plugins { id 'com.netflix.nebula.dependency-lock' apply false }
            ext.set('probe.local', 'local')
            ext.set('probe.empty', '')
            ext.set('probe.boolean', true)
            ext.set('probe.integer', 17)
            ext.set('probe.null', null)

            def enabled = nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags.isIsolatedProjectsSupportEnabled(project)
            def values = [:]
            ['parent', 'local', 'cli', 'empty', 'boolean', 'integer', 'null', 'absent'].each { name ->
                def value = nebula.plugin.dependencylock.utils.DependencyLockingPropertyLookup.findProperty(project, "probe.${'$'}name", enabled)
                values[name] = value == null ? '<absent>' : value.getClass().simpleName + ':' + value.toString()
            }
            tasks.register('reportProperties') {
                inputs.properties(values)
                doLast {
                    inputs.properties.sort().each { name, value -> println("${'$'}name=${'$'}value") }
                }
            }
            """.trimIndent()
        )
        for ((enabled, isolated) in listOf(false to false, true to false, true to true)) {
            val arguments = listOf("-Pprobe.local=cli", "-Pprobe.cli=cli")
            val first = run(gradle, enabled, isolated, arguments)
            assertThat(first.output).contains(
                "parent=${if (enabled) "<absent>" else "String:inherited"}",
                "local=String:local", "cli=String:cli", "empty=String:",
                "boolean=Boolean:true", "integer=Integer:17", "null=<absent>", "absent=<absent>"
            )
            assertThat(first.output).contains("Configuration cache entry stored")

            val second = run(gradle, enabled, isolated, arguments)
            assertThat(second.output).contains("Reusing configuration cache", "cli=String:cli")

            val changed = run(gradle, enabled, isolated, listOf("-Pprobe.local=cli", "-Pprobe.cli=changed"))
            assertThat(changed.output).contains("Configuration cache entry stored", "cli=String:changed")
                .doesNotContain("Reusing configuration cache")
        }
    }

    private fun run(
        gradle: SupportedGradleVersion,
        enabled: Boolean,
        isolated: Boolean,
        properties: List<String>
    ): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val arguments = mutableListOf(
            ":child:reportProperties", "--configuration-cache", "--configuration-cache-problems=fail",
            "--no-watch-fs", "--stacktrace", "--console=plain",
            "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled"
        )
        arguments.addAll(properties)
        if (isolated) arguments.add(
            if (GradleVersion.version(version) >= GradleVersion.version("9.7")) "--isolated-projects"
            else "-Dorg.gradle.unsafe.isolated-projects=true"
        )
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(version).withArguments(arguments).build()
    }
}
