package com.netflix.nebula.dependencylock

import nebula.test.dsl.*
import nebula.test.dsl.TestKitAssertions.assertThat
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

internal class DependencyLockPluginTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `test migrateToCoreLocks`(gradle: SupportedGradleVersion) {
        val runner = testProject(projectDir) {
            properties {
                buildCache(true)
                configurationCache(false) // migrateToCoreLocks is not CC-compatible
            }
            settings {
                name("root")
            }
            subProject("sub") {
                plugins {
                    id("java")
                    id("com.netflix.nebula.dependency-lock")
                }
                repositories{
                    mavenCentral()
                }
                dependencies {
                    implementation(project(":sub2"))
                    implementation("org.jspecify:jspecify:0.+")
                }
            }
            subProject("sub2") {
                plugins {
                    id("java")
                }
            }
        }
        val gLsL = runner.run("gL", "sL"){
            withGradle(gradle.version)
        }
        assertThat(gLsL)
            .hasNoProblemsReport()
            .hasNoDeprecationWarnings()
            .hasNoMutableStateWarnings()
        assertThat(gLsL.task(":sub:saveLock"))
            .hasOutcome(TaskOutcome.SUCCESS)
        val legacyLockFile = projectDir.resolve("sub/dependencies.lock")
        assertThat(legacyLockFile).exists()
        assertThatJson(legacyLockFile.readText())
            .inPath("$.compileClasspath[\"root:sub2\"]")
            .isObject
            .containsEntry("project", true)
        assertThatJson(legacyLockFile.readText())
            .inPath("$.compileClasspath[\"org.jspecify:jspecify\"]")
            .isObject
            .containsEntry("locked", "0.3.0")

        projectDir.resolve("gradle.properties").appendText(
            """
systemProp.nebula.features.coreLockingSupport=true
"""
        )
        val migrateToCoreLocks = runner.run("migrateToCoreLocks") {
            forwardOutput()
            withGradle(gradle.version)
        }
        assertThat(migrateToCoreLocks)
            .hasNoProblemsReport()
            .hasNoDeprecationWarnings()
            .hasNoMutableStateWarnings()
        val coreLockFile = projectDir.resolve("sub/gradle.lockfile")
        assertThat(coreLockFile).exists()
            .content()
            .contains("org.jspecify:jspecify:0.3.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath")
            .doesNotContain("sub2")
    }
}