package com.netflix.nebula.dependencylock

import com.netflix.nebula.dependencylock.testutil.TestHelpers
import nebula.test.dsl.*
import nebula.test.dsl.TestKitAssertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DependencyLockCommitTest {
    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var remoteGitDir: File

    fun TestProjectBuilder.rootProjectSetup() {
        properties {
            buildCache(true)
            configurationCache(true)
            property("org.gradle.warning.mode", "fail")
            property("systemProp.nebula.features.coreLockingSupport", "false")
        }
        configureGitignore()
    }

    fun configureGitignore() {
        File(projectDir, ".gitignore").writeText(
            """
.gradle-test-kit/
.gradle/
build/
gradle.properties"""
        )
    }

    @Test
    fun test() {
        TestHelpers.withRemoteGit(remoteGitDir, projectDir) { remote, local ->
            val runner = testProject(projectDir) {
                rootProjectSetup()
                rootProject {
                    plugins {
                        id("java")
                        id("com.netflix.nebula.dependency-lock")
                    }
                    repositories {
                        mavenCentral()
                    }
                    dependencies("""implementation("org.slf4j:slf4j-api:1.6.+")""")
                }
            }
            local.commit().setMessage("initial").call()
            runner.run("generateLock", "saveLock", "commitLock")

            assertThat(File(projectDir, "dependencies.lock")).exists()
                .content()
                .isEqualToIgnoringWhitespace(
                    //language=json
                    """{
  "compileClasspath": {
      "org.slf4j:slf4j-api": {
          "locked": "1.6.6"
      }
  },
  "runtimeClasspath": {
      "org.slf4j:slf4j-api": {
          "locked": "1.6.6"
      }
  },
  "testCompileClasspath": {
      "org.slf4j:slf4j-api": {
          "locked": "1.6.6"
      }
  },
  "testRuntimeClasspath": {
      "org.slf4j:slf4j-api": {
          "locked": "1.6.6"
      }
  }
}""")
            assertThat(local.status().call().hasUncommittedChanges()).isFalse
        }
    }
}