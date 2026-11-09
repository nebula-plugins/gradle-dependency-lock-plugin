/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package com.netflix.nebula.dependencylock

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import java.io.File
import java.util.jar.JarOutputStream

/** Real Gradle builds; external framework versions are pinned and changing test modules stay local. */
internal class IsolatedProjectsIntegrationFixture(val directory: File) {
    val repository: String = directory.resolve("repo").toURI().toString()

    init {
        write("gradle.properties", "org.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\n")
        for (module in listOf("widget", "other")) {
            for (version in listOf("1.0", "1.1", "1.2")) publish(module, version)
            metadata(module, listOf("1.0"))
        }
    }

    fun write(path: String, content: String) {
        directory.resolve(path).apply { parentFile.mkdirs(); writeText(content.trimIndent() + "\n") }
    }

    private fun publish(module: String, version: String) {
        val base = "repo/fixture/$module/$version/$module-$version"
        write("$base.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$module</artifactId><version>$version</version></project>")
        JarOutputStream(directory.resolve("$base.jar").outputStream()).use { }
    }

    fun metadata(module: String, versions: List<String>) {
        write("repo/fixture/$module/maven-metadata.xml", "<metadata><groupId>fixture</groupId><artifactId>$module</artifactId><versioning><latest>${versions.last()}</latest><release>${versions.last()}</release><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>")
    }

    fun lock(path: String): JsonNode = ObjectMapper().readTree(directory.resolve(path))

    fun version(path: String, module: String, configuration: String = "runtimeClasspath"): String =
        lock(path)[configuration]["fixture:$module"]["locked"].asText()

    fun run(
        gradle: SupportedGradleVersion,
        tasks: List<String>,
        enabled: Boolean = true,
        isolated: Boolean = true,
        expectFailure: Boolean = false
    ): BuildResult {
        val version = if (gradle == SupportedGradleVersion.GRADLE_9_2) "9.2.1" else GradleVersion.current().version
        val options = mutableListOf(
            "--parallel", "--configuration-cache", "--configuration-cache-problems=fail", "--console=plain", "--stacktrace",
            "-Dnebula.features.coreLockingSupport=false", "-Dnebula.features.coreAlignmentSupport=true",
            "-Dnebula.dependency-lock.isolatedProjectsSupport=$enabled"
        )
        if (isolated) options.add(if (GradleVersion.version(version) >= GradleVersion.version("9.7"))
            "--isolated-projects" else "-Dorg.gradle.unsafe.isolated-projects=true")
        val runner = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withGradleVersion(version).withArguments(tasks + options)
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    fun reused(result: BuildResult) {
        assertThat(result.output).contains("Reusing configuration cache", "Configuration cache entry reused")
            .doesNotContain("Configuration cache problems found")
    }
}
