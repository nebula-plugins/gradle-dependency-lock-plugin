/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.netflix.nebula.dependencylock

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.Properties

internal class IsolatedProjectsBuildLogicTest {
    @TempDir
    lateinit var projectDir: File

    enum class Convention { JAVA, GROOVY, KOTLIN }

    companion object {
        @JvmStatic
        fun conventions() = SupportedGradleVersion.values().flatMap { gradle ->
            Convention.entries.map { convention -> Arguments.of(gradle, convention) }
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("conventions")
    fun `included conventions generate update and verify isolated cached JSON locks`(
        gradle: SupportedGradleVersion, convention: Convention
    ) {
        val (fixture, conventionFile) = fixture(convention)
        val generated = fixture.run(gradle, listOf("generateLock", "saveLock"))
        assertThat(generated.task(":build-logic:jar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        for (path in listOf(":", ":plain:", ":build-logic:")) {
            assertThat(generated.task("${path}generateLock")).isNull()
            assertThat(generated.task("${path}saveLock")).isNull()
        }
        val appLock = "apps/service/service.lock"
        val libraryLock = "libraries/library/convention.lock"
        val libraryBytes = projectDir.resolve(libraryLock).readText()
        assertThat(fixture.version(appLock, "widget")).isEqualTo("1.0")
        assertThat(fixture.lock(appLock).fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("compileClasspath", "runtimeClasspath", "testRuntimeClasspath")
        assertThat(fixture.lock(appLock)["runtimeClasspath"]["fixture:library"]["project"].asBoolean()).isTrue()

        val build = listOf(":apps:service:jar", ":plain:jar")
        fixture.run(gradle, build)
        val cachedBuild = fixture.run(gradle, build)
        fixture.reused(cachedBuild)
        for (path in listOf(":apps:service", ":libraries:library")) {
            assertThat(cachedBuild.task("$path:verifyDependencyResolution")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }
        assertThat(cachedBuild.task(":plain:verifyDependencyResolution")).isNull()
        assertThat(projectDir.resolve("apps/service/build/classes/java/main/fixture/Consumer.class")).exists()

        fixture.metadata("widget", listOf("1.0", "1.1"))
        fixture.metadata("other", listOf("1.0", "1.1"))
        val update = listOf(":apps:service:uL", ":apps:service:diffLock", ":apps:service:saveLock",
            "-PdependencyLock.updateDependencies=fixture:widget")
        fixture.run(gradle, update)
        assertThat(fixture.version(appLock, "widget")).isEqualTo("1.1")
        assertThat(fixture.version(appLock, "other")).isEqualTo("1.0")
        assertThat(projectDir.resolve(libraryLock).readText()).isEqualTo(libraryBytes)
        fixture.run(gradle, update)
        fixture.reused(fixture.run(gradle, update))
        fixture.metadata("widget", listOf("1.0", "1.1", "1.2"))
        fixture.run(gradle, update)
        assertThat(fixture.version(appLock, "widget")).isEqualTo("1.2")
        val appBytes = projectDir.resolve(appLock).readText()

        val report = listOf(":apps:service:dependencies", "--configuration", "runtimeClasspath")
        val broken = report + "-PbrokenDependency=true"
        val failed = fixture.run(gradle, broken, expectFailure = true)
        assertThat(failed.output).contains("convention missing-version guidance", "Failed to resolve 'fixture:missing'", "BUILD FAILED")
        fixture.reused(fixture.run(gradle, broken, expectFailure = true))

        // Recompile the included convention: cached consumers must see the new verifier policy.
        conventionFile.writeText(conventionFile.readText().replace(
            "convention missing-version guidance", "updated convention guidance"))
        val changed = fixture.run(gradle, broken, expectFailure = true)
        assertThat(changed.output).contains("updated convention guidance")
            .doesNotContain("convention missing-version guidance", "Reusing configuration cache")
        fixture.reused(fixture.run(gradle, broken, expectFailure = true))

        fixture.run(gradle, report)
        val repaired = fixture.run(gradle, report)
        fixture.reused(repaired)
        assertThat(repaired.output).doesNotContain("Failed to resolve", "updated convention guidance")
        assertThat(projectDir.resolve(appLock).readText()).isEqualTo(appBytes)
        assertThat(projectDir.resolve(libraryLock).readText()).isEqualTo(libraryBytes)
    }

    private fun fixture(convention: Convention): Pair<IsolatedProjectsIntegrationFixture, File> {
        val fixture = IsolatedProjectsIntegrationFixture(projectDir, injectPluginClasspath = false)
        fixture.write("settings.gradle", """
            pluginManagement { includeBuild('build-logic') }
            rootProject.name = 'convention-consumer'
            include ':apps:service', ':libraries:library', ':plain'
        """)
        fixture.write("build.gradle", "")
        fixture.write("plain/build.gradle", "plugins { id 'java-library' }")
        fixture.write("libraries/library/build.gradle", "plugins { id 'fixture.java-conventions' }")
        fixture.write("apps/service/build.gradle", """
            plugins { id 'fixture.java-conventions' }
            dependencyLock.lockFile = 'service.lock'
            dependencies {
                implementation project(':libraries:library')
                if (providers.gradleProperty('brokenDependency').isPresent()) implementation 'fixture:missing'
            }
        """)
        fixture.write("libraries/library/src/main/java/fixture/Library.java", """
            package fixture;
            public class Library { public static String message() { return "from convention library"; } }
        """)
        fixture.write("apps/service/src/main/java/fixture/Consumer.java", """
            package fixture;
            public class Consumer { String message = Library.message(); }
        """)
        fixture.write("build-logic/settings.gradle", "rootProject.name = 'build-logic'")
        fixture.write("build-logic/gradle.properties", projectDir.resolve("gradle.properties").readText())

        // Supply the implementation as a real convention dependency, with no TestKit injection
        // into the consumer. This exercises compilation and the convention's runtime classloader.
        val metadata = Properties()
        requireNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties"))
            .use { metadata.load(it) }
        // Metadata can include output directories for source sets with no source files.
        val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
            .map(::File).filter { it.exists() }
        assertThat(classpath).isNotEmpty()
        fixture.write("build-logic/dependency-lock-classpath.txt", classpath.joinToString("\n") { it.absolutePath })

        val source: String
        if (convention == Convention.KOTLIN) {
            fixture.write("build-logic/build.gradle.kts", """
                plugins { `kotlin-dsl` }
                repositories { gradlePluginPortal(); mavenCentral() }
                dependencies { implementation(files(file("dependency-lock-classpath.txt").readLines())) }
            """)
            source = "build-logic/src/main/kotlin/fixture.java-conventions.gradle.kts"
            fixture.write(source, """
                plugins { `java-library`; id("com.netflix.nebula.dependency-lock") }
                group = "fixture"
                version = "1.0"
                repositories { maven { url = uri("${fixture.repository}") } }
                dependencyLock {
                    lockFileProperty.convention("convention.lock")
                    configurationNamesProperty.convention(setOf("compileClasspath", "runtimeClasspath", "testRuntimeClasspath"))
                    includeTransitivesProperty.convention(true)
                }
                dependencyResolutionVerifierExtension {
                    missingVersionsMessageAdditionProperty.convention("convention missing-version guidance")
                }
                dependencies { api("fixture:widget:1.+"); implementation("fixture:other:1.+") }
            """)
        } else {
            fixture.write("build-logic/build.gradle", """
                plugins { id '${if (convention == Convention.JAVA) "java-gradle-plugin" else "groovy-gradle-plugin"}' }
                dependencies { implementation files(file('dependency-lock-classpath.txt').readLines()) }
                ${if (convention == Convention.JAVA) """
                    gradlePlugin {
                        plugins {
                            javaConventions {
                                id = 'fixture.java-conventions'
                                implementationClass = 'fixture.JavaConventionsPlugin'
                            }
                        }
                    }
                """ else ""}
            """)
            if (convention == Convention.JAVA) {
                source = "build-logic/src/main/java/fixture/JavaConventionsPlugin.java"
                fixture.write(source, """
                    package fixture;
                    import org.gradle.api.Plugin;
                    import org.gradle.api.Project;
                    import nebula.plugin.dependencylock.DependencyLockExtension;
                    import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension;
                    public class JavaConventionsPlugin implements Plugin<Project> {
                        public void apply(Project project) {
                            project.getPluginManager().apply("java-library");
                            project.getPluginManager().apply("com.netflix.nebula.dependency-lock");
                            project.setGroup("fixture");
                            project.setVersion("1.0");
                            project.getRepositories().maven(repo -> repo.setUrl("${fixture.repository}"));
                            DependencyLockExtension locks = project.getExtensions().getByType(DependencyLockExtension.class);
                            locks.getLockFileProperty().convention("convention.lock");
                            locks.getConfigurationNamesProperty().convention(java.util.Set.of("compileClasspath", "runtimeClasspath", "testRuntimeClasspath"));
                            locks.getIncludeTransitivesProperty().convention(true);
                            project.getExtensions().getByType(DependencyResolutionVerifierExtension.class)
                                .getMissingVersionsMessageAdditionProperty().convention("convention missing-version guidance");
                            project.getDependencies().add("api", "fixture:widget:1.+");
                            project.getDependencies().add("implementation", "fixture:other:1.+");
                        }
                    }
                """)
            } else {
                source = "build-logic/src/main/groovy/fixture.java-conventions.gradle"
                fixture.write(source, """
                    plugins { id 'java-library'; id 'com.netflix.nebula.dependency-lock' }
                    group = 'fixture'
                    version = '1.0'
                    repositories { maven { url = uri('${fixture.repository}') } }
                    dependencyLock {
                        lockFileProperty.convention('convention.lock')
                        configurationNamesProperty.convention(['compileClasspath', 'runtimeClasspath', 'testRuntimeClasspath'] as Set)
                        includeTransitivesProperty.convention(true)
                    }
                    dependencyResolutionVerifierExtension {
                        missingVersionsMessageAdditionProperty.convention('convention missing-version guidance')
                    }
                    dependencies { api 'fixture:widget:1.+'; implementation 'fixture:other:1.+' }
                """)
            }
        }
        return fixture to projectDir.resolve(source)
    }
}
