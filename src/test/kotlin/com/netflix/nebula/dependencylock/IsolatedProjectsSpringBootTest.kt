/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package com.netflix.nebula.dependencylock

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.jar.JarFile

internal class IsolatedProjectsSpringBootTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `Spring Boot packaging selective updates verification and rollback retain JSON locks`(gradle: SupportedGradleVersion) {
        val fixture = fixture()
        fixture.run(gradle, listOf("generateLock", "saveLock"))
        val appLock = "apps/service/dependencies.lock"
        val libraryLock = "libraries/library/dependencies.lock"
        val libraryBytes = projectDir.resolve(libraryLock).readText()
        assertThat(fixture.version(appLock, "widget")).isEqualTo("1.0")
        assertThat(fixture.lock(appLock)["runtimeClasspath"].has("org.springframework.boot:spring-boot")).isTrue()
        assertThat(fixture.lock(appLock)["runtimeClasspath"]["fixture:library"]["project"].asBoolean()).isTrue()
        assertThat(fixture.lock(appLock)["runtimeClasspath"]["fixture:library"].has("locked")).isFalse()

        fixture.metadata("widget", listOf("1.0", "1.1"))
        fixture.metadata("other", listOf("1.0", "1.1"))
        val update = listOf(":apps:service:updateLock", ":apps:service:diffLock", ":apps:service:saveLock",
            "-PdependencyLock.updateDependencies=fixture:widget")
        val updated = fixture.run(gradle, update)
        assertThat(updated.tasks.map { it.path }).containsSubsequence(
            ":apps:service:updateLock", ":apps:service:diffLock", ":apps:service:saveLock")
        assertThat(fixture.version(appLock, "widget")).isEqualTo("1.1")
        assertThat(fixture.version(appLock, "other")).isEqualTo("1.0")
        assertThat(projectDir.resolve(libraryLock).readText()).isEqualTo(libraryBytes)
        fixture.run(gradle, update)
        fixture.reused(fixture.run(gradle, update))

        val appBytes = projectDir.resolve(appLock).readText()
        val build = listOf(":apps:service:bootJar", ":apps:service:test")
        // A real consuming application rolls out and rolls back without regenerating its locks.
        for ((enabled, isolated) in listOf(false to false, true to false, true to true, false to false)) {
            // BootJar 4.0.9 reads other projects' coordinates in its constructor.
            // Locking and dependency reports can run isolated; packaging/tests use ordinary CC.
            val tasks = if (isolated) listOf(":apps:service:dependencies", "--configuration", "runtimeClasspath",
                ":libraries:library:dependencies", "--configuration", "runtimeClasspath") else build
            fixture.run(gradle, tasks, enabled, isolated)
            // Boot checks for compiled classes while configuring main-class discovery.
            // Let creation of those directories invalidate the initial cache entry.
            fixture.run(gradle, tasks, enabled, isolated)
            val cached = fixture.run(gradle, tasks, enabled, isolated)
            fixture.reused(cached)
            assertThat(cached.task(":apps:service:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(cached.task(":libraries:library:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(projectDir.resolve(appLock).readText()).isEqualTo(appBytes)
            assertThat(projectDir.resolve(libraryLock).readText()).isEqualTo(libraryBytes)
        }
        JarFile(projectDir.resolve("apps/service/build/libs/service-1.0.jar")).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertThat(entries).contains("BOOT-INF/classes/fixture/ExampleApplication.class")
            assertThat(entries.any { it.startsWith("BOOT-INF/lib/spring-boot-4.0.9") }).isTrue()
            assertThat(entries).contains("BOOT-INF/lib/library-1.0.jar")
        }

        val broken = listOf(":apps:service:dependencies", "--configuration", "runtimeClasspath", "-PbrokenDependency=true")
        val failure = fixture.run(gradle, broken, expectFailure = true)
        assertThat(failure.output).contains("Failed to resolve 'fixture:missing:1.0'", "BUILD FAILED")
        fixture.reused(fixture.run(gradle, broken, expectFailure = true))
        val repaired = broken.filterNot { it.startsWith("-PbrokenDependency=") }
        fixture.run(gradle, repaired)
        fixture.reused(fixture.run(gradle, repaired))
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `Spring Boot packaging isolation limitation exists without the locking plugin`(gradle: SupportedGradleVersion) {
        val fixture = fixture()
        for (path in listOf("apps/service/build.gradle", "libraries/library/build.gradle")) {
            val file = projectDir.resolve(path)
            file.writeText(file.readText().replace("id 'com.netflix.nebula.dependency-lock'", ""))
        }
        val conventions = projectDir.resolve("gradle/locking.gradle")
        conventions.writeText(conventions.readText().substringBefore("dependencyLock {"))
        val failed = fixture.run(gradle, listOf(":apps:service:bootJar"), expectFailure = true)
        assertThat(failed.output).contains("org.springframework.boot.gradle.tasks.bundling.ResolvedDependencies",
            "cannot access 'Project.group'", "BUILD FAILED")
    }

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `Spring dependency management BOM imports generate and consume cached locks`(gradle: SupportedGradleVersion) {
        val fixture = fixture(springDependencyManagement = true)
        val writes = listOf("generateLock", "saveLock")
        // Spring dependency-management 1.1.7 reads other projects' mutable state.
        // Exercise ordinary configuration caching without claiming strict isolation support.
        fixture.run(gradle, writes, isolated = false)
        assertThat(fixture.lock("apps/service/dependencies.lock").path("runtimeClasspath").has("org.springframework.boot:spring-boot")).isTrue()
        val build = listOf(":apps:service:bootJar", ":apps:service:test")
        fixture.run(gradle, build, isolated = false)
        fixture.run(gradle, build, isolated = false)
        val cached = fixture.run(gradle, build, isolated = false)
        fixture.reused(cached)
        assertThat(cached.task(":apps:service:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun fixture(springDependencyManagement: Boolean = false): IsolatedProjectsIntegrationFixture {
        val fixture = IsolatedProjectsIntegrationFixture(projectDir)
        fixture.write("settings.gradle", "rootProject.name = 'spring-locking'\ninclude ':apps:service', ':libraries:library'")
        fixture.write("build.gradle", "")
        fixture.write("gradle/locking.gradle", """
            repositories {
                maven { url = uri('${fixture.repository}') }
                mavenCentral()
            }
            group = 'fixture'
            version = '1.0'
            dependencyLock {
                configurationNames = ['compileClasspath', 'runtimeClasspath', 'testRuntimeClasspath']
                includeTransitives = true
            }
        """)
        fixture.write("libraries/library/build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
            apply from: new File(rootDir, 'gradle/locking.gradle')
            dependencies { api 'fixture:widget:1.+' }
        """)
        fixture.write("libraries/library/src/main/java/fixture/Library.java", """
            package fixture;
            public class Library { public static String message() { return "from library"; } }
        """)
        fixture.write("apps/service/build.gradle", """
            plugins {
                id 'java'
                id 'org.springframework.boot' version '4.0.9'
                ${if (springDependencyManagement) "id 'io.spring.dependency-management' version '1.1.7'" else ""}
                id 'com.netflix.nebula.dependency-lock'
            }
            apply from: new File(rootDir, 'gradle/locking.gradle')
            dependencies {
                ${if (springDependencyManagement) "" else "implementation platform('org.springframework.boot:spring-boot-dependencies:4.0.9')"}
                implementation 'org.springframework.boot:spring-boot-starter'
                implementation project(':libraries:library')
                implementation 'fixture:widget:1.+'
                implementation 'fixture:other:1.+'
                testImplementation 'org.springframework.boot:spring-boot-starter-test'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                if (providers.gradleProperty('brokenDependency').isPresent()) implementation 'fixture:missing:1.0'
            }
            tasks.named('test') { useJUnitPlatform() }
        """)
        fixture.write("apps/service/src/main/java/fixture/ExampleApplication.java", """
            package fixture;
            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            @SpringBootApplication
            public class ExampleApplication {
                public static void main(String[] args) {
                    System.out.println(Library.message());
                    SpringApplication.run(ExampleApplication.class, args);
                }
            }
        """)
        fixture.write("apps/service/src/test/java/fixture/LibraryTest.java", """
            package fixture;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class LibraryTest { @Test void libraryIsOnClasspath() { assertEquals("from library", Library.message()); } }
        """)
        return fixture
    }
}
